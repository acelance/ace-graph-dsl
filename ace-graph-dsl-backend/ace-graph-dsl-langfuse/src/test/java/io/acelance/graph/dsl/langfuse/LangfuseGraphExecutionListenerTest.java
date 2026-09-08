package io.acelance.graph.dsl.langfuse;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LangfuseGraphExecutionListener} 节点级生命周期验证：每个 run 一 trace、每个节点一 span，
 * 生命周期回调产出 {@code trace.create / span.create / span.update / trace.update} 序列，
 * 异常路径产出带 {@code level=ERROR} 的 span.update。runId 优先从 state 保留键对齐。
 */
class LangfuseGraphExecutionListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private LangfuseClient client;

    static class CapturingSender implements LangfuseHttpSender {
        final List<String> payloads = new ArrayList<>();
        @Override
        public void send(String json) {
            payloads.add(json);
        }
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private LangfuseProperties props() {
        LangfuseProperties p = new LangfuseProperties();
        p.setFlushIntervalMs(1_000_000);
        p.setMaxBatchSize(100);
        p.setTraceName("test-trace");
        return p;
    }

    private JsonNode collectedBatch() throws Exception {
        return mapper.readTree(((CapturingSender) clientSender).payloads.get(0)).get("batch");
    }

    private CapturingSender clientSender;

    private LangfuseGraphExecutionListener newListener() {
        clientSender = new CapturingSender();
        client = new LangfuseClient(props(), clientSender);
        return new LangfuseGraphExecutionListener(client, new LangfuseTraceContext(), props());
    }

    private Map<String, Object> stateWithRunId(String runId) {
        return Map.of(ModelOverrideSpec.ACE_RUN_ID_KEY, runId);
    }

    @Test
    void lifecycleProducesTraceAndSpanSequence() throws Exception {
        LangfuseGraphExecutionListener listener = newListener();
        Map<String, Object> state = stateWithRunId("run-1");

        listener.onStart("__start__", state, RunnableConfig.builder().threadId("run-1").build());
        listener.before("nodeA", state, RunnableConfig.builder().threadId("run-1").build(), 1L);
        listener.after("nodeA", state, RunnableConfig.builder().threadId("run-1").build(), 2L);
        listener.onComplete("__start__", state, RunnableConfig.builder().threadId("run-1").build());

        client.flush();
        JsonNode batch = collectedBatch();
        assertEquals(4, batch.size(), "trace.create + span.create + span.update + trace.update");
        assertEquals("trace.create", batch.get(0).get("type").asText());
        assertEquals("span.create", batch.get(1).get("type").asText());
        assertEquals("span.update", batch.get(2).get("type").asText());
        assertEquals("trace.update", batch.get(3).get("type").asText());
        // span 必须挂在 trace 下
        assertEquals(batch.get(0).get("id").asText(), batch.get(1).get("traceId").asText());
        // trace 元数据带 runId，与 node 内 TraceRecorder 对齐
        assertEquals("run-1", batch.get(0).get("metadata").get("runId").asText());
    }

    @Test
    void onErrorProducesSpanUpdateWithLevelError() throws Exception {
        LangfuseGraphExecutionListener listener = newListener();
        Map<String, Object> state = stateWithRunId("run-2");

        listener.onStart("__start__", state, RunnableConfig.builder().threadId("run-2").build());
        listener.before("nodeB", state, RunnableConfig.builder().threadId("run-2").build(), 1L);
        listener.onError("nodeB", state, new RuntimeException("kaboom"),
                RunnableConfig.builder().threadId("run-2").build());

        client.flush();
        JsonNode batch = collectedBatch();
        assertEquals(3, batch.size());
        assertEquals("span.update", batch.get(2).get("type").asText());
        assertEquals("ERROR", batch.get(2).get("level").asText());
        assertTrue(batch.get(2).get("statusMessage").asText().contains("kaboom"));
    }

    @Test
    void runIdResolvedFromStateReservedKeyWhenNoThreadId() throws Exception {
        LangfuseGraphExecutionListener listener = newListener();
        // RunnableConfig 不带 threadId，应回退到 state 保留键
        Map<String, Object> state = stateWithRunId("run-from-state");

        listener.onStart("__start__", state, null);
        client.flush();

        JsonNode batch = collectedBatch();
        assertEquals("run-from-state", batch.get(0).get("metadata").get("runId").asText());
    }
}
