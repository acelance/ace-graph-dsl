package io.acelance.graph.dsl.langfuse;

import io.acelance.graph.dsl.observability.TraceLLMEvent;
import io.acelance.graph.dsl.observability.TraceRecorder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Langfuse LLM 调用级记录器：把 {@link TraceLLMEvent} 转化为 Langfuse {@code generation.create} 事件，
 * 挂到对应节点 span 之下，从而在同一链路中体现「节点内的 LLM 日志细节」——
 * 尤其是模型在<b>请求级动态指定</b>时，这里记录的是<b>运行时真实模型</b>。
 *
 * <p>实现 {@link TraceRecorder} SPI，由 {@code GenericAgentNode} 在每次 LLM 调用边界通过
 * {@code applicationContext.getBeansOfType(TraceRecorder)} 找到并调用。</p>
 *
 * <p><b>硬性约束</b>：{@link #recordLLM} 绝不抛错（节点执行 finally 路径），异常一律内部消化。</p>
 */
@Component
public class LangfuseTraceRecorder implements TraceRecorder {

    private final LangfuseClient client;
    private final LangfuseTraceContext ctx;

    public LangfuseTraceRecorder(LangfuseClient client, LangfuseTraceContext ctx) {
        this.client = client;
        this.ctx = ctx;
    }

    @Override
    public void recordLLM(TraceLLMEvent event) {
        try {
            String traceId = ctx.getTrace(event.runId());
            if (traceId == null) {
                // 无 trace 上下文（如节点试跑、未开启 Langfuse）：不产出孤儿事件
                return;
            }
            String spanId = ctx.getSpan(event.runId(), event.nodeId());
            String generationId = UUID.randomUUID().toString();
            String startTime = event.startedAtEpochMs() != null
                    ? Instant.ofEpochMilli(event.startedAtEpochMs()).toString()
                    : Instant.now().toString();
            String endTime = (event.startedAtEpochMs() != null && event.durationMs() != null)
                    ? Instant.ofEpochMilli(event.startedAtEpochMs() + event.durationMs()).toString()
                    : Instant.now().toString();

            List<String> input = new ArrayList<>();
            if (event.promptTemplate() != null) {
                input.add(event.promptTemplate());
            }

            Map<String, Object> generation = new java.util.LinkedHashMap<>();
            generation.put("type", "generation.create");
            generation.put("id", generationId);
            generation.put("traceId", traceId);
            if (spanId != null) {
                generation.put("parentObservationId", spanId);
            }
            generation.put("name", "llm:" + event.nodeId());
            generation.put("model", event.modelId());
            generation.put("modelParameters", java.util.Map.of(
                    "modelBaseUrl", event.modelBaseUrl() != null ? event.modelBaseUrl() : ""));
            generation.put("input", input);
            generation.put("output", event.response() != null ? event.response() : "");
            generation.put("startTime", startTime);
            generation.put("endTime", endTime);
            if (event.error() != null) {
                generation.put("level", "ERROR");
                generation.put("statusMessage", event.error().toString());
            }
            client.ingest(generation);
        } catch (RuntimeException ignored) {
            // 观测异常不得影响图执行
        }
    }
}
