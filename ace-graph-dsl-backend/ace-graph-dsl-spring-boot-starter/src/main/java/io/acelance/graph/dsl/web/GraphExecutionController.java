package io.acelance.graph.dsl.web;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.exception.SubGraphInterruptionException;
import com.alibaba.cloud.ai.graph.internal.node.ResumableSubGraphAction;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.autoconfigure.AceGraphDslBeans;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.execution.AdapterDelegatingStreamingChunkFormatter;
import io.acelance.graph.dsl.execution.DefaultGraphExecutionEventAdapter;
import io.acelance.graph.dsl.execution.DefaultStreamingChunkFormatter;
import io.acelance.graph.dsl.execution.GraphExecutionEventAdapter;
import io.acelance.graph.dsl.execution.StreamingChunkFormatter;
import io.acelance.graph.dsl.execution.StreamingContext;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import reactor.core.publisher.Flux;

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
 * <h2>流式输出与定制</h2>
 * <p>{@code /stream} 与 {@code /resume} 把「图执行 flux」与「LLM 逐 token 桥接 flux」合并后，
 * 经 {@link StreamingChunkFormatter} 转换每个片段再下发。业务未定制时输出原生 SSE 格式；
 * 业务只需提供一个 {@code @Bean StreamingChunkFormatter} 即可按自身前后端协议输出
 * （例如 JSON 协议 chunk、携带 {@code thinking}/{@code isEnd} 等业务字段）。</p>
 */
@RestController
@RequestMapping("/execution")
@ConditionalOnProperty(prefix = "ace.graph.dsl.web.execution", name = "enabled", havingValue = "true")
public class GraphExecutionController {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutionController.class);

    private final GraphRuntime runtime;
    private final GraphExecutionEventAdapter eventAdapter;
    private final ObjectMapper objectMapper;
    private final GraphStreamBridge streamBridge;
    private final ObjectProvider<StreamingChunkFormatter> formatterProvider;

    public GraphExecutionController(GraphRuntime runtime,
                                    GraphExecutionEventAdapter eventAdapter,
                                    @Qualifier(AceGraphDslBeans.OBJECT_MAPPER) ObjectMapper objectMapper,
                                    GraphStreamBridge streamBridge,
                                    ObjectProvider<StreamingChunkFormatter> formatterProvider) {
        this.runtime = runtime;
        this.eventAdapter = eventAdapter;
        this.objectMapper = objectMapper;
        this.streamBridge = streamBridge;
        this.formatterProvider = formatterProvider;
    }

    /** 同步执行，返回最终状态。 */
    @PostMapping("/{graphId}/invoke")
    public Map<String, Object> invoke(@PathVariable String graphId,
                                      @RequestBody(required = false) ExecutionRequest req) {
        String threadId = resolveThreadId(req);
        CompiledGraph graph = runtime.get(graphId);
        Optional<OverAllState> result = graph.invoke(inputs(req, threadId), buildConfig(threadId));
        return result.map(s -> stripReserved(s.data())).orElse(Map.of());
    }

    /** 流式执行（SSE）。 */
    @PostMapping(value = "/{graphId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String graphId,
                             @RequestBody(required = false) ExecutionRequest req) {
        String threadId = resolveThreadId(req);
        CompiledGraph graph = runtime.get(graphId);
        return toSse(graph.stream(inputs(req, threadId), buildConfig(threadId)),
                graphId, threadId, resolveFormatter());
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
        return toSse(graph.stream(null, updated), graphId, req.threadId(), resolveFormatter());
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
        result.put("state", snapshot.state() != null ? stripReserved(snapshot.state().data()) : Map.of());
        return result;
    }

    /**
     * 合并「图执行 flux」与「桥接 token flux」并经格式化器下发 SSE。
     *
     * <p>控制器先为本次 {@code threadId} 注册桥接通道并订阅，再触发图执行（节点执行期会通过
     * 桥接器把逐 token 片段推入该通道）；流结束 / 异常时 {@link GraphStreamBridge#complete(String)}
     * 关闭通道，避免 runId 泄漏。</p>
     */
    private SseEmitter toSse(Flux<NodeOutput> graphFlux,
                             String graphId,
                             String threadId,
                             StreamingChunkFormatter formatter) {
        SseEmitter emitter = new SseEmitter(0L);
        Flux<TokenChunk> bridgeFlux = streamBridge.register(threadId);
        Flux<Object> merged = graphFlux.cast(Object.class).mergeWith(bridgeFlux.cast(Object.class));
        Disposable subscription = merged.subscribe(
                element -> {
                    StreamingContext ctx = (element instanceof TokenChunk tc)
                            ? StreamingContext.ofToken(tc, graphId)
                            : StreamingContext.ofNode((NodeOutput) element, graphId);
                    sendFormatted(emitter, formatter, ctx);
                },
                error -> {
                    streamBridge.complete(threadId);
                    handleStreamError(emitter, error);
                },
                () -> {
                    streamBridge.complete(threadId);
                    emitter.complete();
                });
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
        return emitter;
    }

    private void sendFormatted(SseEmitter emitter, StreamingChunkFormatter formatter, StreamingContext ctx) {
        try {
            Object payload = formatter.format(ctx);
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /** 图流式异常兜底：子图内 HITL 的 {@link SubGraphInterruptionException} 是"暂停"而非"错误"。 */
    private void handleStreamError(SseEmitter emitter, Throwable error) {
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
    }

    /**
     * 解析流式格式化器（优先级）：
     * <ol>
     *   <li>自定义 {@code StreamingChunkFormatter} Bean → 业务定制格式；</li>
     *   <li>否则若自定义了旧版 {@code GraphExecutionEventAdapter} → 委派给它（向后兼容）；</li>
     *   <li>否则使用原生默认格式 {@link DefaultStreamingChunkFormatter}。</li>
     * </ol>
     */
    private StreamingChunkFormatter resolveFormatter() {
        StreamingChunkFormatter custom = formatterProvider.getIfAvailable();
        if (custom != null) {
            return custom;
        }
        if (!(eventAdapter instanceof DefaultGraphExecutionEventAdapter)) {
            return new AdapterDelegatingStreamingChunkFormatter(eventAdapter);
        }
        return new DefaultStreamingChunkFormatter();
    }

    /** 合并用户 inputs 与运行态保留键（runId + 模型覆盖），注入初始 state。 */
    private static Map<String, Object> inputs(ExecutionRequest req, String threadId) {
        Map<String, Object> base = new LinkedHashMap<>();
        if (req != null && req.inputs() != null) {
            base.putAll(req.inputs());
        }
        // 保留键：runId 与 Langfuse trace 对齐；模型覆盖供节点执行层消费
        base.put(ModelOverrideSpec.ACE_RUN_ID_KEY, threadId);
        if (req != null && req.modelOverrides() != null) {
            base.put(ModelOverrideSpec.ACE_MODEL_OVERRIDES_KEY, req.modelOverrides());
        }
        return base;
    }

    /** 构造执行配置（仅 threadId）。模型覆盖走 state 保留键，故无需写入 metadata。 */
    private static RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /** 剔除运行态保留键，避免泄漏到最终结果 / 状态快照。 */
    private static Map<String, Object> stripReserved(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.keySet().removeIf(k -> k != null && k.startsWith(ModelOverrideSpec.ACE_RESERVED_PREFIX));
        return out;
    }

    private static String resolveThreadId(ExecutionRequest req) {
        if (req != null && req.threadId() != null && !req.threadId().isBlank()) {
            return req.threadId();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 执行请求体：图输入 + 可选 threadId + 可选请求级模型覆盖。
     *
     * <p>{@code modelOverrides} 用于在本次请求内动态指定某节点 / 全部 GENERIC_AGENT 节点使用的模型，
     * 不修改图定义；优先级为 node 级 &gt; global 级。JSON 反序列化兼容顺序无关（均标注 @JsonProperty）。</p>
     */
    public record ExecutionRequest(
            @JsonProperty("inputs") Map<String, Object> inputs,
            @JsonProperty("threadId") String threadId,
            @JsonProperty("modelOverrides") ModelOverrideSpec modelOverrides
    ) {}

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
