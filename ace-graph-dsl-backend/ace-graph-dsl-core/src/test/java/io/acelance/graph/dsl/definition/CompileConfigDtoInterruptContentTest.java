package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code compile.interruptContent} 序列化保真与内容比对（HITL 图级中断提示文案）。
 */
class CompileConfigDtoInterruptContentTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void jsonRoundTripPreservesInterruptContent() throws Exception {
        GraphDefinition def = new GraphDefinition(
                "hitl-demo", "HITL", "1.0.0", "",
                Map.of(),
                List.of(new NodeRef("final_reply", Map.of(), null, null)),
                List.of(),
                new CompileConfigDto(List.of("final_reply"), "memory", "草稿已生成，请审核后继续。"),
                null);

        String json = objectMapper.writeValueAsString(def);
        GraphDefinition restored = objectMapper.readValue(json, GraphDefinition.class);

        assertEquals("草稿已生成，请审核后继续。", restored.compile().interruptContent());
        assertEquals(List.of("final_reply"), restored.compile().interruptBefore());
        assertEquals("memory", restored.compile().saver());
    }

    @Test
    void missingInterruptContentDeserializesAsNull() throws Exception {
        String json = """
                {
                  "graphId": "g1",
                  "displayName": "G1",
                  "version": "1.0.0",
                  "description": "",
                  "keyStrategies": {},
                  "nodes": [],
                  "edges": [],
                  "compile": { "interruptBefore": [], "saver": "memory" }
                }
                """;
        GraphDefinition restored = objectMapper.readValue(json, GraphDefinition.class);
        assertNull(restored.compile().interruptContent());
        assertEquals("memory", restored.compile().saver());
    }

    @Test
    void contentComparatorDetectsInterruptContentChange() {
        GraphDefinition a = new GraphDefinition(
                "g1", "A", "1.0.0", "",
                Map.of(), List.of(), List.of(),
                new CompileConfigDto(List.of(), "memory", null), null);
        GraphDefinition b = new GraphDefinition(
                "g1", "A", "1.0.0", "",
                Map.of(), List.of(), List.of(),
                new CompileConfigDto(List.of(), "memory", "请确认后继续"), null);

        assertTrue(GraphDefinitionContentComparator.sameContent(a, a));
        assertFalse(GraphDefinitionContentComparator.sameContent(a, b),
                "仅改 interruptContent 应视为内容变更");
    }

    @Test
    void defaultConfigHasNullInterruptContent() {
        assertNull(CompileConfigDto.defaultConfig().interruptContent());
    }
}
