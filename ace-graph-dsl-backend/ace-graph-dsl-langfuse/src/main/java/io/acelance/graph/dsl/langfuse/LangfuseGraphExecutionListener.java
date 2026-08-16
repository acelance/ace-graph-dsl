package io.acelance.graph.dsl.langfuse;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import io.acelance.graph.dsl.observability.GraphExecutionListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Langfuse 节点级生命周期监听：每个 run 一 trace，每个节点一 span。
 *
 * <p>挂在 {@code GraphExecutionListener} SPI 上，由 {@code DynamicGraphBuilder} 自动收集桥接。
 * 与 {@link LangfuseTraceRecorder} 共用 {@link LangfuseTraceContext} 把节点内的 LLM generation 挂到对应 span 下。</p>
 *
 * <p>事件模型（Langfuse ingestion）：</p>
 * <ul>
 *   <li>{@code trace.create}：run 开始时（onStart）</li>
 *   <li>{@code span.create}：节点开始前（before）</li>
 *   <li>{@code span.update}：节点结束 / 异常（after / onError），带 endTime 与状态</li>
 *   <li>{@code trace.update}：run 结束时（onComplete），标记结束</li>
 * </ul>
 *
 * <p>实现必须保证各回调不抛错（桥接层已吞异常，但此处仍自防御）。</p>
 */
@Component
public class LangfuseGraphExecutionListener implements GraphExecutionListener {

    private final LangfuseClient client;
    private final LangfuseTraceContext ctx;
    private final LangfuseProperties props;
    // 缓存每个 run 的 traceId，onStart 创建、onComplete 清理
    private final ConcurrentHashMap<String, String> traceByRun = new ConcurrentHashMap<>();

    public LangfuseGraphExecutionListener(LangfuseClient client, LangfuseTraceContext ctx, LangfuseProperties props) {
        this.client = client;
        this.ctx = ctx;
        this.props = props;
    }

    @Override
    public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
        safe(() -> {
            String runId = runId(config, state);
            String traceId = ctx.getOrCreateTrace(runId);
            traceByRun.put(runId, traceId);
            client.ingest(Map.of(
                    "type", "trace.create",
                    "id", traceId,
                    "name", props.getTraceName(),
                    "timestamp", Instant.now().toString(),
                    "metadata", Map.of("runId", runId, "startedNode", nodeId)
            ));
        });
    }

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        safe(() -> {
            String runId = runId(config, state);
            String traceId = ctx.getOrCreateTrace(runId);
            String spanId = UUID.randomUUID().toString();
            ctx.putSpan(runId, nodeId, spanId);
            client.ingest(Map.of(
                    "type", "span.create",
                    "id", spanId,
                    "traceId", traceId,
                    "name", nodeId,
                    "startTime", Instant.now().toString(),
                    "metadata", Map.of("runId", runId)
            ));
        });
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        safe(() -> {
            String runId = runId(config, state);
            String traceId = ctx.getTrace(runId);
            String spanId = ctx.getSpan(runId, nodeId);
            if (spanId != null && traceId != null) {
                client.ingest(Map.of(
                        "type", "span.update",
                        "id", spanId,
                        "traceId", traceId,
                        "endTime", Instant.now().toString()
                ));
            }
            ctx.removeSpan(runId, nodeId);
        });
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable error, RunnableConfig config) {
        safe(() -> {
            String runId = runId(config, state);
            String traceId = ctx.getTrace(runId);
            String spanId = ctx.getSpan(runId, nodeId);
            if (spanId != null && traceId != null) {
                client.ingest(Map.of(
                        "type", "span.update",
                        "id", spanId,
                        "traceId", traceId,
                        "endTime", Instant.now().toString(),
                        "level", "ERROR",
                        "statusMessage", error != null ? error.toString() : "unknown"
                ));
            }
            ctx.removeSpan(runId, nodeId);
        });
    }

    @Override
    public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
        safe(() -> {
            String runId = runId(config, state);
            String traceId = traceByRun.remove(runId);
            if (traceId != null) {
                client.ingest(Map.of(
                        "type", "trace.update",
                        "id", traceId,
                        "endTime", Instant.now().toString()
                ));
            }
            ctx.removeTrace(runId);
        });
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 观测异常不得影响图执行
        }
    }

    private static String runId(RunnableConfig config, Map<String, Object> state) {
        // 优先从 state 中的保留键取 runId：保证与 GenericAgentNode 内 TraceRecorder 对齐，
        // 且并行扇出分支（其 RunnableConfig 未携带 threadId）也能归并到同一 trace。
        if (state != null) {
            Object v = state.get(io.acelance.graph.dsl.runtime.ModelOverrideSpec.ACE_RUN_ID_KEY);
            if (v instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        if (config != null && config.threadId().isPresent()) {
            return config.threadId().get();
        }
        return "-";
    }
}
