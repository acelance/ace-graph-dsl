package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.ai.tool.ToolSource;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-MCP：Assembly 读取 Spec mcpKeys 并经 SPI 解析。
 */
class AgentScopeToolAssemblyTest {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeToolAssemblyTest.class);

    @Test
    void assemblesMcpToolsFromSpec() {
        ToolCallback cb = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("mcp_demo")
                        .description("demo")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "mcp-ok";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
        NamedToolCallback named = new NamedToolCallback(
                "mcp_demo", "mcp_demo", "srv1", ToolSource.MCP, "demo", cb);

        AgentScopeToolAssembly assembly = new AgentScopeToolAssembly(
                (ctx, keys) -> {
                    log.info("假 McpToolResolver: agentCode={}, runId={}, keys={}",
                            ctx.agentCode(), ctx.runId(), keys);
                    return List.of(named);
                },
                null, null, null, null);

        GenericAgentSpec spec = new GenericAgentSpec(
                "http://127.0.0.1/stub", "sk", false, "m",
                "prompt",
                "user_query", "out", null, null,
                true, List.of(), false, null, false, List.of(),
                true, List.of("srv1"), Map.of(), false, List.of(),
                MemoryMode.NONE, null, false,
                false, null, null);

        Map<String, Object> data = new HashMap<>();
        data.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-ut-1");
        data.put(LlmRequestContext.ACE_AGENT_CODE_KEY, "ut-agent");
        OverAllState state = new OverAllState(data);

        AgentScopeToolAssemblyResult result = assembly.assemble(
                "g1", "saa1", "agent:x", spec, state);
        log.info("装配结果: {}", result);
        assertEquals(1, result.mcpToolCount());
        assertEquals(1, result.totalToolCount());
        assertTrue(result.hasTools());
        assertEquals("mcp_demo", result.callbacks().get(0).getToolDefinition().name());
    }
}
