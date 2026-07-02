package io.acelance.graph.dsl.web;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

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

    /** HITL 恢复执行（SSE）：写回反馈 / 状态后从断点继续。 */
    @PostMapping(value = "/{graphId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable String graphId,
                             @RequestBody ResumeRequest req) throws Exception {
        if (req == null || req.threadId() == null || req.threadId().isBlank()) {
            throw new IllegalArgumentException("thread_id 不能为空");
        }
        CompiledGraph graph = runtime.get(graphId);
        RunnableConfig config = RunnableConfig.builder().threadId(req.threadId()).build();
        StateSnapshot snapshot = graph.getState(config);
        if (snapshot == null) {
            throw new IllegalStateException("未找到 thread_id=" + req.threadId() + " 的 checkpoint，请先调用 /stream");
        }
        RunnableConfig updated = graph.updateState(
                config, req.updates() != null ? req.updates() : Map.of(), null);
        return toSse(graph.stream(null, updated));
    }

    private SseEmitter toSse(reactor.core.publisher.Flux<NodeOutput> flux) {
        SseEmitter emitter = new SseEmitter(0L);
        Disposable subscription = flux
                .filter(Objects::nonNull)
                .subscribe(
                        output -> sendEvent(emitter, output),
                        error -> {
                            log.warn("图流式执行异常", error);
                            emitter.completeWithError(error);
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

    /** HITL 恢复请求体：threadId + 写回状态。 */
    public record ResumeRequest(String threadId, Map<String, Object> updates) {}
}
