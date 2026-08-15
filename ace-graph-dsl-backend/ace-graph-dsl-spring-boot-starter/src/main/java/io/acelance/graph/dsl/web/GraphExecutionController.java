package io.acelance.graph.dsl.web;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.exception.SubGraphInterruptionException;
import com.alibaba.cloud.ai.graph.internal.node.ResumableSubGraphAction;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.autoconfigure.AceGraphDslBeans;
import io.acelance.graph.dsl.execution.GraphExecutionEventAdapter;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 通用图执行 REST/SSE 端点（可选）。
 *
 * <p>受 {@code ace.graph.dsl.web.execution.enabled} 控制（默认关闭）。开启后，纯编排类业务
 * 无需自写 Controller 即可运行已发布的图：同步 {@code invoke}、流式 {@code stream}（SSE）、
 * HITL {@code resume}。复杂业务仍可自写 Controller。</p>
 *
 * <p>事件结构由 {@link GraphExecutionEventAdapter} 决定，宿主可自定义。</p>
 */
@RestController
@RequestMapping("/execution")
@ConditionalOnProperty(prefix = "ace.graph.dsl.web.execution", name = "enabled", havingValue = "true")
public class GraphExecutionController {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutionController.class);

    private final GraphRuntime runtime;
    private final GraphExecutionEventAdapter eventAdapter;
    private final ObjectMapper objectMapper;

    public GraphExecutionController(GraphRuntime runtime,
                                    GraphExecutionEventAdapter eventAdapter,
                                    @Qualifier(AceGraphDslBeans.OBJECT_MAPPER) ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.eventAdapter = eventAdapter;
        this.objectMapper = objectMapper;
    }

    /** 同步执行，返回最终状态。 */
    @PostMapping("/{graphId}/invoke")
    public Map<String, Object> invoke(@PathVariable String graphId,
                                      @RequestBody(required = false) ExecutionRequest req) {
        CompiledGraph graph = runtime.get(graphId);
        RunnableConfig config = RunnableConfig.builder().threadId(resolveThreadId(req)).build();
        Optional<OverAllState> result = graph.invoke(inputs(req), config);
        return result.map(OverAllState::data).orElse(Map.of());
    }

    /** 流式执行（SSE）。 */
    @PostMapping(value = "/{graphId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String graphId,
                             @RequestBody(required = false) ExecutionRequest req) {
        CompiledGraph graph = runtime.get(graphId);
        RunnableConfig config = RunnableConfig.builder().threadId(resolveThreadId(req)).build();
        return toSse(graph.stream(inputs(req), config));
    }

    /** HITL 恢复执行（SSE）：写回反馈 / 状态后从断点继续。
     *
     * <p>支持两种 resume 场景：</p>
     * <ul>
     *   <li>顶层 HITL：{@code subgraphNodeId} 为空，走 {@code graph.updateState} + {@code stream}。</li>
     *   <li>子图内 HITL（G4）：{@code subgraphNodeId} 非空，在 {@code RunnableConfig.metadata}
     *       写入 {@link ResumableSubGraphAction#resumeSubGraphId(String) → true}，
     *       触发 {@code SubCompiledGraphNodeAction} 的续跑分支。</li>
     * </ul>
     */
    @PostMapping(value = "/{graphId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable String graphId,
                             @RequestBody ResumeRequest req) throws Exception {
        if (req == null || req.threadId() == null || req.threadId().isBlank()) {
            throw new IllegalArgumentException("thread_id 不能为空");
        }
        CompiledGraph graph = runtime.get(graphId);
        // 子图内 HITL resume：在 metadata 标记 resumeSubGraphId，触发 SubCompiledGraphNodeAction 续跑
        RunnableConfig.Builder configBuilder = RunnableConfig.builder().threadId(req.threadId());
        if (req.subgraphNodeId() != null && !req.subgraphNodeId().isBlank()) {
            String resumeKey = ResumableSubGraphAction.resumeSubGraphId(req.subgraphNodeId());
            configBuilder.addMetadata(resumeKey, true);
        }
        RunnableConfig config = configBuilder.build();
        StateSnapshot snapshot = graph.getState(config);
        if (snapshot == null) {
            throw new IllegalStateException("未找到 thread_id=" + req.threadId() + " 的 checkpoint，请先调用 /stream");
        }
        RunnableConfig updated = graph.updateState(
                config, req.updates() != null ? req.updates() : Map.of(), null);
        return toSse(graph.stream(null, updated));
    }

    /** 查询顶层图的断点状态（HITL 暂停节点 + state 快照）。 */
    @GetMapping("/{graphId}/state/{threadId}")
    public Map<String, Object> getState(@PathVariable String graphId,
                                        @PathVariable String threadId) {
        CompiledGraph graph = runtime.get(graphId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        StateSnapshot snapshot = graph.getState(config);
        return formatSnapshot(snapshot);
    }

    /**
     * 查询子图的断点状态（G4 子图内 HITL）。
     *
     * <p>子图 checkpoint 存储在父子共享的 {@code CheckpointSaver} 中，threadId 命名空间为
     * {@code {parentThreadId}_subgraph_{nodeId}}（由 {@code SubCompiledGraphNodeAction} 内置）。
     * 本端点按此规则构造子 threadId 并查询。</p>
     *
     * @param graphId  顶层图 ID
     * @param threadId 父图 threadId
     * @param nodeId   子图节点 ID（父图中的 SUBGRAPH 节点 nodeId）
     * @return 子图断点状态（next 暂停节点 + state 快照）；无 checkpoint 时返回 {@code exists:false}
     */
    @GetMapping("/{graphId}/state/{threadId}/subgraph/{nodeId}")
    public Map<String, Object> getSubgraphState(@PathVariable String graphId,
                                                 @PathVariable String threadId,
                                                 @PathVariable String nodeId) {
        CompiledGraph graph = runtime.get(graphId);
        String subThreadId = threadId + "_subgraph_" + nodeId;
        RunnableConfig subConfig = RunnableConfig.builder().threadId(subThreadId).build();
        StateSnapshot snapshot = graph.getState(subConfig);
        return formatSnapshot(snapshot);
    }

    /** 将 StateSnapshot 格式化为前端可消费的 Map。 */
    private Map<String, Object> formatSnapshot(StateSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (snapshot == null) {
            result.put("exists", false);
            return result;
        }
        result.put("exists", true);
        result.put("next", snapshot.next());
        result.put("node", snapshot.node());
        result.put("state", snapshot.state() != null ? snapshot.state().data() : Map.of());
        return result;
    }

    private SseEmitter toSse(reactor.core.publisher.Flux<NodeOutput> flux) {
        SseEmitter emitter = new SseEmitter(0L);
        Disposable subscription = flux
                .filter(Objects::nonNull)
                .subscribe(
                        output -> sendEvent(emitter, output),
                        error -> {
                            // G4 子图内 HITL：SubGraphInterruptionException 是"暂停"而非"错误"
                            Optional<SubGraphInterruptionException> subEx = SubGraphInterruptionException.from(error);
                            if (subEx.isPresent()) {
                                try {
                                    Map<String, Object> event = new LinkedHashMap<>();
                                    event.put("type", "subgraph-interrupted");
                                    event.put("parentNodeId", subEx.get().parentNodeId());
                                    event.put("nodeId", subEx.get().nodeId());
                                    event.put("state", subEx.get().state());
                                    emitter.send(SseEmitter.event()
                                            .name("subgraph-interrupted")
                                            .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
                                    emitter.complete();
                                    log.info("子图内 HITL 暂停: parentNodeId={}, nodeId={}",
                                            subEx.get().parentNodeId(), subEx.get().nodeId());
                                } catch (Exception e) {
                                    emitter.completeWithError(e);
                                }
                            } else {
                                log.warn("图流式执行异常", error);
                                emitter.completeWithError(error);
                            }
                        },
                        emitter::complete);
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, NodeOutput output) {
        try {
            String json = objectMapper.writeValueAsString(eventAdapter.toPayload(output));
            emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private static Map<String, Object> inputs(ExecutionRequest req) {
        return req != null && req.inputs() != null ? req.inputs() : Map.of();
    }

    private static String resolveThreadId(ExecutionRequest req) {
        if (req != null && req.threadId() != null && !req.threadId().isBlank()) {
            return req.threadId();
        }
        return UUID.randomUUID().toString();
    }

    /** 执行请求体：图输入 + 可选 threadId。 */
    public record ExecutionRequest(Map<String, Object> inputs, String threadId) {}

    /** HITL 恢复请求体：threadId + 写回状态 + 可选子图节点 ID（G4 子图内 HITL resume）。
     *
     * <p>{@code subgraphNodeId} 非空时表示恢复的是子图内的 HITL 断点；
     * 为空时表示恢复顶层 HITL 断点。</p>
     */
    public record ResumeRequest(String threadId, Map<String, Object> updates, String subgraphNodeId) {
        /** 向后兼容：仅 threadId + updates（顶层 HITL resume） */
        public ResumeRequest(String threadId, Map<String, Object> updates) {
            this(threadId, updates, null);
        }
    }
}
