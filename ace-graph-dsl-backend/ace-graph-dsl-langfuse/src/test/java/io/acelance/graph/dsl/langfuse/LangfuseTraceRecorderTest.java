package io.acelance.graph.dsl.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.observability.TraceLLMEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LangfuseTraceRecorder} 验证：把节点内 LLM 调用细节转成 Langfuse {@code generation.create} 事件，
 * 并挂到对应节点 span 之下（{@code parentObservationId = spanId}），traceId 与 trace 上下文对齐。
 * 无 trace 上下文（试跑 / 未开启 Langfuse）时不产出孤儿事件。
 */
class LangfuseTraceRecorderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private LangfuseClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private LangfuseClient newClient(LangfuseHttpSender sender) {
        LangfuseProperties p = new LangfuseProperties();
        p.setFlushIntervalMs(1_000_000);
        p.setMaxBatchSize(100);
        return new LangfuseClient(p, sender);
    }

    private JsonNode lastBatch(LangfuseHttpSender sender) throws Exception {
        return mapper.readTree(((CapturingSender) sender).payloads.get(0)).get("batch");
    }

    static class CapturingSender implements LangfuseHttpSender {
        final java.util.List<String> payloads = new java.util.ArrayList<>();
        @Override
        public void send(String json) {
            payloads.add(json);
        }
    }

    @Test
    void recordLLMProducesGenerationAttachedToSpan() throws Exception {
        CapturingSender sender = new CapturingSender();
        client = newClient(sender);
        LangfuseTraceContext ctx = new LangfuseTraceContext();
        LangfuseTraceRecorder recorder = new LangfuseTraceRecorder(client, ctx);

        // 模拟 listener 已为本 run/node 建好 trace 与 span
        String traceId = ctx.getOrCreateTrace("run-1");
        ctx.putSpan("run-1", "nodeA", "span-1");

        TraceLLMEvent event = new TraceLLMEvent(
                "g-test", "nodeA", "run-1",
                "gpt-4o-dynamic", "https://edge",
                "你好 {{x}}", "回复内容", 120L, 1_700_000_000_000L, null);
        recorder.recordLLM(event);

        assertEquals(1, client.pendingCount());
        client.flush();

        JsonNode batch = lastBatch(sender);
        assertEquals(1, batch.size());
        JsonNode gen = batch.get(0);
        assertEquals("generation.create", gen.get("type").asText());
        assertEquals("gpt-4o-dynamic", gen.get("model").asText(), "记录的是请求级动态模型");
        assertEquals(traceId, gen.get("traceId").asText());
        assertEquals("span-1", gen.get("parentObservationId").asText(), "挂到节点 span 下");
        assertEquals("llm:nodeA", gen.get("name").asText());
        assertEquals("回复内容", gen.get("output").asText());
        assertTrue(gen.get("input").isArray());
        assertEquals("你好 {{x}}", gen.get("input").get(0).asText());
        assertFalse(gen.has("level"), "成功调用不带 level");
    }

    @Test
    void recordLLMWithErrorMarksLevelError() throws Exception {
        CapturingSender sender = new CapturingSender();
        client = newClient(sender);
        LangfuseTraceContext ctx = new LangfuseTraceContext();
        LangfuseTraceRecorder recorder = new LangfuseTraceRecorder(client, ctx);

        ctx.getOrCreateTrace("run-2");
        ctx.putSpan("run-2", "nodeB", "span-b");

        TraceLLMEvent event = new TraceLLMEvent(
                "g-test", "nodeB", "run-2",
                "gpt-4o-dynamic", null,
                "prompt", null, 5L, 1_700_000_000_000L, new RuntimeException("boom"));
        recorder.recordLLM(event);

        client.flush();
        JsonNode gen = lastBatch(sender).get(0);
        assertEquals("ERROR", gen.get("level").asText());
        assertTrue(gen.get("statusMessage").asText().contains("boom"));
    }

    @Test
    void recordLLMWithoutTraceContextIsNoop() {
        CapturingSender sender = new CapturingSender();
        client = newClient(sender);
        LangfuseTraceContext ctx = new LangfuseTraceContext(); // 未建 trace
        LangfuseTraceRecorder recorder = new LangfuseTraceRecorder(client, ctx);

        TraceLLMEvent event = new TraceLLMEvent(
                "g-test", "nodeA", "run-x",
                "gpt-4o", null, "p", "r", 1L, 1L, null);
        recorder.recordLLM(event);

        assertEquals(0, client.pendingCount(), "无 trace 上下文不产出孤儿事件");
        assertTrue(sender.payloads.isEmpty());
        assertNull(ctx.getTrace("run-x"));
    }
}
