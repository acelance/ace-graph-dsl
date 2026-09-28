package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpToolFilterTest {

    @Test
    void filtersByWhitelistSubset() {
        NamedToolCallback a = named("query", "crm");
        NamedToolCallback b = named("delete", "crm");
        ResourceBinding binding = new ResourceBinding(
                false, List.of(), false, null, false, List.of(),
                true, List.of("crm"), Map.of("crm", List.of("query")),
                false, List.of());
        List<NamedToolCallback> kept = McpToolFilter.apply(List.of(a, b), binding, "n1");
        assertEquals(1, kept.size());
        assertEquals("query", kept.get(0).originalName());
    }

    private static NamedToolCallback named(String original, String server) {
        ToolCallback cb = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(original).description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
        return new NamedToolCallback(
                ToolNames.toModelName(ToolSource.MCP, server, original),
                original, server, ToolSource.MCP, "d", cb);
    }
}
