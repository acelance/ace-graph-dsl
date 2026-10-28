package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2：Adapter → GraphStreamBridge emit（父 nodeId/kind/attrs）与降级。
 */
class AceAgentscopeStreamBridgeTest {

    @Test
    void streamingEmitsParentNodeIdKindAndAttrsThenWritesOutputKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model model = stubModel(calls, "Hello", " world");
        ReActAgent agent = ReActAgent.builder()
                .name("stream_ut")
                .description("unit")
                .sysPrompt("test")
                .model(model)
                .build();

        RecordingBridge bridge = new RecordingBridge();
        SaaWorkflowSpec parent = new SaaWorkflowSpec(
                "SEQUENTIAL", "user_query", "out", "BIZ",
                null, null, null, null, null, List.of(),
                true, true, "lesso.sse-frame",
                "{\"thinking\":true,\"nodeDisplay\":\"父思考\"}",
                null, null, null);
        AgentScopeParentStreamContext parentStream = AgentScopeParentStreamContext.fromParent(parent);

        AceAgentscopeReActBaseAgentAdapter adapter = new AceAgentscopeReActBaseAgentAdapter(
                "stream_ut", "unit", "sql", "{user_query}", agent,
                "g1", "saa_parent", bridge, parentStream);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "hi");
        data.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-m2");
        OverAllState state = new OverAllState(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) invokeReAct(adapter, state);

        assertTrue(String.valueOf(out.get("sql")).contains("Hello"));
        assertFalse(bridge.chunks.isEmpty(), "应向 bridge emit TokenChunk");
        TokenChunk first = bridge.chunks.get(0);
        assertEquals("saa_parent", first.nodeId());
        assertEquals("BIZ", first.responseKind());
        assertEquals(Boolean.TRUE, first.attrs().get(TokenChunk.ATTR_ENABLE_BIZ_PARAMS));
        assertEquals("lesso.sse-frame", first.attrs().get(TokenChunk.ATTR_BIZ_PARAM_INTERPRETER_ID));
        assertTrue(String.valueOf(first.attrs().get(TokenChunk.ATTR_BIZ_PARAM_RAW)).contains("thinking"));

        boolean hasStreaming = bridge.chunks.stream()
                .anyMatch(c -> c.outputType() == OutputType.AGENT_MODEL_STREAMING);
        boolean hasFinished = bridge.chunks.stream()
                .anyMatch(c -> c.outputType() == OutputType.AGENT_MODEL_FINISHED && c.last());
        assertTrue(hasStreaming, "应有 STREAMING chunk");
        assertTrue(hasFinished, "应有 FINISHED chunk");
        assertTrue(calls.get() >= 1);
    }

    @Test
    void noRunIdDegradesWithoutEmit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model model = stubModel(calls, "ECHO:ok");
        ReActAgent agent = ReActAgent.builder()
                .name("degrade_ut")
                .description("unit")
                .sysPrompt("test")
                .model(model)
                .build();

        RecordingBridge bridge = new RecordingBridge();
        AceAgentscopeReActBaseAgentAdapter adapter = new AceAgentscopeReActBaseAgentAdapter(
                "degrade_ut", "unit", "sql", "{user_query}", agent,
                "g1", "saa_parent", bridge, AgentScopeParentStreamContext.EMPTY);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "ok");
        // 无 runId → 降级
        OverAllState state = new OverAllState(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) invokeReAct(adapter, state);

        assertTrue(String.valueOf(out.get("sql")).contains("ok")
                || String.valueOf(out.get("sql")).startsWith("ECHO:"));
        assertTrue(bridge.chunks.isEmpty(), "无 runId 时不应 emit");
        assertEquals(1, calls.get());
    }

    @Test
    void streamFailureFallsBackToCallAndWritesOutputKey() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int n = modelCalls.incrementAndGet();
                if (n == 1) {
                    return Flux.error(new IllegalStateException("stream boom"));
                }
                return Flux.just(ChatResponse.builder()
                        .id("fb")
                        .content(List.of(TextBlock.builder().text("FALLBACK_OK").build()))
                        .finishReason("stop")
                        .build());
            }

            @Override
            public String getModelName() {
                return "stub-fail-then-ok";
            }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("fail_ut")
                .description("unit")
                .sysPrompt("test")
                .model(model)
                .build();

        RecordingBridge bridge = new RecordingBridge();
        AceAgentscopeReActBaseAgentAdapter adapter = new AceAgentscopeReActBaseAgentAdapter(
                "fail_ut", "unit", "sql", "{user_query}", agent,
                "g1", "saa_parent", bridge, AgentScopeParentStreamContext.EMPTY);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "x");
        data.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-fail");
        OverAllState state = new OverAllState(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) invokeReAct(adapter, state);

        assertEquals("FALLBACK_OK", out.get("sql"));
        assertTrue(modelCalls.get() >= 2, "stream 失败后应再走 call（再次调模型）");
        // 首轮 stream 即失败、未推过 delta → 不强制 FINISHED；outputKey 已由 call 写回
        assertTrue(bridge.chunks.stream().noneMatch(c ->
                "FALLBACK_OK".equals(c.token())), "降级不应把 call 全文再推成 STREAMING");
    }

    @Test
    void noopBridgeDegradesWithoutEmit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model model = stubModel(calls, "plain");
        ReActAgent agent = ReActAgent.builder()
                .name("noop_ut")
                .description("unit")
                .sysPrompt("test")
                .model(model)
                .build();

        AceAgentscopeReActBaseAgentAdapter adapter = new AceAgentscopeReActBaseAgentAdapter(
                "noop_ut", "unit", "sql", "{user_query}", agent, "g1", "n1");

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "x");
        data.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-ignored");
        OverAllState state = new OverAllState(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) invokeReAct(adapter, state);

        assertTrue(String.valueOf(out.get("sql")).contains("plain")
                || String.valueOf(out.get("sql")).contains("x"));
        assertEquals(1, calls.get());
    }

    private static Object invokeReAct(AceAgentscopeReActBaseAgentAdapter adapter, OverAllState state)
            throws Exception {
        Method m = AceAgentscopeReActBaseAgentAdapter.class.getDeclaredMethod("invokeReAct", OverAllState.class);
        m.setAccessible(true);
        return m.invoke(adapter, state);
    }

    private static Model stubModel(AtomicInteger calls, String... pieces) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                List<ChatResponse> frames = new ArrayList<>();
                for (int i = 0; i < pieces.length; i++) {
                    frames.add(ChatResponse.builder()
                            .id("t" + i)
                            .content(List.of(TextBlock.builder().text(pieces[i]).build()))
                            .finishReason(i == pieces.length - 1 ? "stop" : null)
                            .build());
                }
                return Flux.fromIterable(frames);
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };
    }

    /** 记录 emit 的 bridge。 */
    private static final class RecordingBridge implements GraphStreamBridge {
        private final List<TokenChunk> chunks = new ArrayList<>();

        @Override
        public void emit(String runId, TokenChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void complete(String runId) {
        }

        @Override
        public reactor.core.publisher.Flux<TokenChunk> register(String runId) {
            return reactor.core.publisher.Flux.empty();
        }
    }
}
