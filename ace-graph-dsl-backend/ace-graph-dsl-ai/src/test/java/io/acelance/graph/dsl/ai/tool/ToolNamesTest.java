package io.acelance.graph.dsl.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolNamesTest {

    @Test
    void usesDoubleUnderscoreAndIsLegal() {
        String name = ToolNames.toModelName(ToolSource.MCP, "weather", "get_forecast");
        assertTrue(name.contains("__"));
        assertFalse(name.contains(":"));
        ToolNames.assertLegal(name);
    }

    @Test
    void assertLegalRejectsColon() {
        assertThrows(IllegalArgumentException.class, () -> ToolNames.assertLegal("mcp:x:y"));
    }

    @Test
    void compressesVeryLongNames() {
        String longOrig = "a".repeat(80);
        String name = ToolNames.toModelName(ToolSource.LOCAL, "ns", longOrig);
        assertTrue(name.length() <= ToolNames.MAX_LEN);
        ToolNames.assertLegal(name);
    }
}

class ToolDeduperTest {

    @Test
    void marksConflictedOriginalNamesInDescription() {
        NamedToolCallback a = named("mcp__s1__query", "query", "s1", ToolSource.MCP, "desc");
        NamedToolCallback b = named("mcp__s2__query", "query", "s2", ToolSource.MCP, "desc");
        List<ToolCallback> cbs = ToolDeduper.toModelCallbacks(List.of(a, b));
        assertEquals(2, cbs.size());
        String d0 = cbs.get(0).getToolDefinition().description();
        String d1 = cbs.get(1).getToolDefinition().description();
        assertTrue(d0.contains("[来源:") || d1.contains("[来源:"));
    }

    @Test
    void localFirstDropsMcpWithSameOriginalName() {
        NamedToolCallback local = named("local__crm__query", "query", "crm", ToolSource.LOCAL, "L");
        NamedToolCallback mcp = named("mcp__erp__query", "query", "erp", ToolSource.MCP, "M");
        List<ToolCallback> cbs = ToolDeduper.toModelCallbacks(
                List.of(local, mcp), ToolConflictPolicy.LOCAL_FIRST);
        assertEquals(1, cbs.size());
        assertEquals("local__crm__query", cbs.get(0).getToolDefinition().name());
    }

    @Test
    void mcpFirstDropsLocalWithSameOriginalName() {
        NamedToolCallback local = named("local__crm__query", "query", "crm", ToolSource.LOCAL, "L");
        NamedToolCallback mcp = named("mcp__erp__query", "query", "erp", ToolSource.MCP, "M");
        List<ToolCallback> cbs = ToolDeduper.toModelCallbacks(
                List.of(local, mcp), ToolConflictPolicy.MCP_FIRST);
        assertEquals(1, cbs.size());
        assertEquals("mcp__erp__query", cbs.get(0).getToolDefinition().name());
    }

    @Test
    void failThrowsWhenLocalAndMcpShareOriginalName() {
        NamedToolCallback local = named("local__crm__query", "query", "crm", ToolSource.LOCAL, "L");
        NamedToolCallback mcp = named("mcp__erp__query", "query", "erp", ToolSource.MCP, "M");
        assertThrows(IllegalStateException.class, () ->
                ToolDeduper.toModelCallbacks(List.of(local, mcp), ToolConflictPolicy.FAIL));
    }

    @Test
    void builtinAlwaysKeptUnderLocalFirst() {
        NamedToolCallback builtin = named("ace__skill__load_skill", "load_skill", null,
                ToolSource.BUILTIN, "B");
        NamedToolCallback local = named("local__x__load_skill", "load_skill", "x",
                ToolSource.LOCAL, "L");
        NamedToolCallback mcp = named("mcp__y__load_skill", "load_skill", "y",
                ToolSource.MCP, "M");
        List<ToolCallback> cbs = ToolDeduper.toModelCallbacks(
                List.of(builtin, local, mcp), ToolConflictPolicy.LOCAL_FIRST);
        assertEquals(2, cbs.size());
        Set<String> names = cbs.stream().map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
        assertTrue(names.contains("ace__skill__load_skill"));
        assertTrue(names.contains("local__x__load_skill"));
    }

    private static NamedToolCallback named(String unique, String orig, String server,
                                           ToolSource src, String desc) {
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(orig).description(desc).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
        return new NamedToolCallback(unique, orig, server, src, desc, delegate);
    }
}
