package io.acelance.graph.dsl.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StreamingToolCallMergingManagerTest {

    @Test
    void mergeStreamingFragmentsIntoSingleToolCall() {
        String id = "chatcmpl-tool-8bf065eb4de9019c";
        List<AssistantMessage.ToolCall> raw = List.of(
                new AssistantMessage.ToolCall(id, "function", "read_skill", ""),
                new AssistantMessage.ToolCall(id, "function", null, "{\"skill\": "),
                new AssistantMessage.ToolCall(id, "function", null, "\""),
                new AssistantMessage.ToolCall(id, "function", null, "weather"),
                new AssistantMessage.ToolCall(id, "function", null, ""),
                new AssistantMessage.ToolCall(id, "function", null, "\""),
                new AssistantMessage.ToolCall(id, "function", null, "}"),
                new AssistantMessage.ToolCall(id, "function", null, "")
        );

        List<AssistantMessage.ToolCall> merged = StreamingToolCallMergingManager.mergeToolCallsById(raw);

        assertEquals(1, merged.size());
        assertEquals(id, merged.get(0).id());
        assertEquals("read_skill", merged.get(0).name());
        assertEquals("{\"skill\": \"weather\"}", merged.get(0).arguments());
    }

    @Test
    void keepDistinctIdsSeparate() {
        List<AssistantMessage.ToolCall> raw = List.of(
                new AssistantMessage.ToolCall("a", "function", "read_skill", "{\"skill\":\"weather\"}"),
                new AssistantMessage.ToolCall("b", "function", "execute_command", "{\"command\":\"echo 1\"}")
        );
        List<AssistantMessage.ToolCall> merged = StreamingToolCallMergingManager.mergeToolCallsById(raw);
        assertEquals(2, merged.size());
        assertEquals("read_skill", merged.get(0).name());
        assertEquals("execute_command", merged.get(1).name());
    }

    @Test
    void dropNamelessOrphansWithoutId() {
        List<AssistantMessage.ToolCall> raw = List.of(
                new AssistantMessage.ToolCall(null, "function", null, "garbage")
        );
        List<AssistantMessage.ToolCall> merged = StreamingToolCallMergingManager.mergeToolCallsById(raw);
        assertTrue(merged.isEmpty());
    }

    @Test
    void unknownToolNamesDetectsHallucinatedName() {
        ChatResponse response = responseWithToolCall("write_file", "{}");
        Set<String> known = Set.of("read_skill", "execute_command");
        assertEquals(Set.of("write_file"),
                StreamingToolCallMergingManager.unknownToolNames(response, known));
        assertEquals(List.of("write_file"),
                StreamingToolCallMergingManager.requestedToolNames(response));
    }

    @Test
    void executeUnknownToolDoesNotThrow_returnsErrorPayload() {
        ToolCallback known = stubCallback("execute_command", "ok");
        ToolCallingChatOptions opts = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(known))
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("write report")), opts);
        ChatResponse response = responseWithToolCall("write_file", "{\"path\":\"a.txt\"}");

        StreamingToolCallMergingManager manager = new StreamingToolCallMergingManager(
                DefaultToolCallingManager.builder().build());

        ToolExecutionResult result = assertDoesNotThrow(
                () -> manager.executeToolCalls(prompt, response));

        boolean found = result.conversationHistory().stream().anyMatch(m -> {
            if (!(m instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm)) {
                return false;
            }
            return trm.getResponses() != null && trm.getResponses().stream()
                    .anyMatch(r -> r.responseData() != null && r.responseData().contains("unknown_tool"));
        });
        assertTrue(found, () -> "expected unknown_tool payload in history, got: "
                + result.conversationHistory());
    }

    @Test
    void unknownCallbackHintListsAvailableTools() {
        var cb = new StreamingToolCallMergingManager.UnknownNameToolCallback(
                "write_file", "execute_command, read_skill");
        String payload = cb.call("{}");
        assertTrue(payload.contains("unknown_tool"));
        assertTrue(payload.contains("write_file"));
        assertTrue(payload.contains("execute_command"));
        assertTrue(payload.contains("read_skill"));
    }

    private static ChatResponse responseWithToolCall(String name, String args) {
        AssistantMessage am = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(am)));
    }

    private static ToolCallback stubCallback(String name, String result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("stub")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String functionInput) {
                return result;
            }
        };
    }
}
