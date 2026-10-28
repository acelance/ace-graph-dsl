package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * applyDeepThinking → GenerateOptions SPI；与 bizParam attrs 正交。
 */
class AgentScopeDeepThinkingOptionsTest {

    @Test
    void applyDeepThinkingAndStateWritesGenerateOptionsViaSpi() throws Exception {
        AtomicReference<GenerateOptions> captured = new AtomicReference<>();
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                captured.set(options);
                return Flux.just(ChatResponse.builder()
                        .id("t")
                        .content(List.of(TextBlock.builder().text("ok").build()))
                        .finishReason("stop")
                        .build());
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };

        GenericAgentSpec spec = new GenericAgentSpec(
                "http://127.0.0.1/stub", "sk", false, "m",
                "prompt",
                "user_query", "out", null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of(),
                MemoryMode.NONE, null, true,
                false, null, null);

        AgentScopeGenerateOptionsCustomizer customizer = (deep, state) -> {
            assertTrue(deep);
            return GenerateOptions.builder()
                    .additionalBodyParam("enable_thinking", true)
                    .build();
        };

        AceAgentscopeReActBaseAgentAdapter adapter = new AceAgentscopeReActBaseAgentAdapter(
                "dt_ut", "unit", "out", "{user_query}",
                model, "sys", spec, AgentScopeToolAssembly.noop(),
                "g1", "n1", "agent1",
                GraphStreamBridge.NOOP,
                AgentScopeParentStreamContext.EMPTY,
                customizer);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "hi");
        data.put(LlmRequestContext.ACE_DEEP_THINKING_KEY, true);
        data.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-dt");
        OverAllState state = new OverAllState(data);

        Method m = AceAgentscopeReActBaseAgentAdapter.class.getDeclaredMethod("invokeReAct", OverAllState.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m.invoke(adapter, state);

        assertEquals("ok", out.get("out"));
        GenerateOptions opts = captured.get();
        assertNotNull(opts, "SPI 应写入 GenerateOptions 并传到 Model");
        assertNotNull(opts.getAdditionalBodyParams());
        assertEquals(Boolean.TRUE, opts.getAdditionalBodyParams().get("enable_thinking"));
    }
}
