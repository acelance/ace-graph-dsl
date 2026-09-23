package io.acelance.graph.dsl.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
