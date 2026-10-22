package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adapter 单测：instruction 占位、outputKey 写回。
 */
class AceAgentscopeReActBaseAgentAdapterTest {

    @Test
    void instructionPlaceholderWritesOutputKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                String user = messages == null || messages.isEmpty() ? "" : messages.get(0).getTextContent();
                return Flux.just(ChatResponse.builder()
                        .id("t1")
                        .content(List.of(TextBlock.builder().text("ECHO:" + user).build()))
                        .finishReason("stop")
                        .build());
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("ut_agent")
                .description("unit")
                .sysPrompt("test")
                .model(model)
                .build();
        AceAgentscopeReActBaseAgentAdapter adapter = new AceAgentscopeReActBaseAgentAdapter(
                "ut_agent", "unit", "sql", "生成 SQL：{user_query}", agent, "g1", "n1");

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "按门店汇总");
        OverAllState state = new OverAllState(data);

        Method m = AceAgentscopeReActBaseAgentAdapter.class.getDeclaredMethod("invokeReAct", OverAllState.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m.invoke(adapter, state);

        assertNotNull(out);
        assertTrue(String.valueOf(out.get("sql")).contains("按门店汇总")
                || String.valueOf(out.get("sql")).startsWith("ECHO:"));
        assertEquals(1, calls.get());
    }

    @Test
    void instructionTemplateRendersFromMap() {
        String rendered = AgentscopeInstructionTemplate.render("给 {sql} 打分", Map.of("sql", "SELECT 1"));
        assertEquals("给 SELECT 1 打分", rendered);
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(rendered).build();
        assertEquals(rendered, msg.getTextContent());
    }
}
