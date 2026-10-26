package io.acelance.graph.dsl.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-MCP：ToolCallback → Toolkit / AgentTool 桥接单测。
 */
class AceToolCallbackAgentscopeBridgeTest {

    private static final Logger log = LoggerFactory.getLogger(AceToolCallbackAgentscopeBridgeTest.class);

    @Test
    void bridgeRegistersAndInvokesToolCallback() {
        AtomicReference<String> seenInput = new AtomicReference<>();
        ToolCallback cb = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("echo_tool")
                        .description("echo")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                seenInput.set(toolInput);
                return "OK:" + toolInput;
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };

        Toolkit toolkit = AceToolCallbackAgentscopeBridge.toToolkit(List.of(cb));
        assertTrue(toolkit.getToolNames().contains("echo_tool"));
        AgentTool tool = toolkit.getTool("echo_tool");
        assertNotNull(tool);
        assertEquals("echo_tool", tool.getName());
        assertNotNull(tool.getParameters());
        assertEquals("object", tool.getParameters().get("type"));

        ToolUseBlock use = ToolUseBlock.builder()
                .id("call-1")
                .name("echo_tool")
                .input(Map.of("q", "hello"))
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(use)
                .input(Map.of("q", "hello"))
                .build();
        ToolResultBlock result = tool.callAsync(param).block();
        assertNotNull(result);
        log.info("桥接工具结果: id={}, name={}, output={}", result.getId(), result.getName(), result.getOutput());
        assertEquals("call-1", result.getId());
        assertTrue(seenInput.get() != null && seenInput.get().contains("hello"));
        assertTrue(result.getOutput() != null && !result.getOutput().isEmpty());
    }
}
