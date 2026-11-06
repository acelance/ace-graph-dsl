package io.acelance.graph.dsl.hitl;

import io.acelance.graph.dsl.definition.CompileConfigDto;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.HitlNodeSpec;
import io.acelance.graph.dsl.definition.NodeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlGraphSupportTest {

    @Test
    void mergeInterruptBeforeUnionsHitlIds() {
        NodeRef hitl = new NodeRef("hitl:review", "HITL", Map.of(), null, null,
                null, null, null, null, null, new HitlNodeSpec(List.of("approved_text")));
        CompileConfigDto compile = new CompileConfigDto(List.of("agent:final"), "memory", "tip");
        CompileConfigDto merged = HitlGraphSupport.mergeInterruptBefore(compile, List.of(hitl));
        assertTrue(merged.interruptBefore().contains("agent:final"));
        assertTrue(merged.interruptBefore().contains("hitl:review"));
        assertEquals("tip", merged.interruptContent());
    }

    @Test
    void ensureInjectKeyStrategiesAddsReplace() {
        NodeRef hitl = new NodeRef("hitl:a", "HITL", Map.of(), null, null,
                null, null, null, null, null, new HitlNodeSpec(List.of("approved_text")));
        Map<String, String> keys = HitlGraphSupport.ensureInjectKeyStrategies(
                Map.of("draft", "REPLACE"), List.of(hitl));
        assertEquals("REPLACE", keys.get("draft"));
        assertEquals("REPLACE", keys.get("approved_text"));
    }

    @Test
    void validateRequiresInjectKeysAndInterruptBefore() {
        NodeRef hitl = new NodeRef("hitl:a", "HITL", Map.of(), null, null,
                null, null, null, null, null, new HitlNodeSpec(List.of()));
        GraphDefinition def = new GraphDefinition(
                "g", "g", "1.0.0", "",
                Map.of(), List.of(hitl), List.of(),
                CompileConfigDto.defaultConfig(), true);
        List<String> errors = HitlGraphSupport.validateHitlNodes(def);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("injectKeys")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("interruptBefore")));
    }

    @Test
    void normalizeFixesInterruptAndKeyStrategies() {
        NodeRef hitl = new NodeRef("hitl:a", "HITL", Map.of(), null, null,
                null, null, null, null, null, new HitlNodeSpec(List.of("approved_text")));
        GraphDefinition def = new GraphDefinition(
                "g", "g", "1.0.0", "",
                Map.of(), List.of(hitl), List.of(),
                CompileConfigDto.defaultConfig(), true);
        GraphDefinition normalized = HitlGraphSupport.normalize(def);
        assertTrue(normalized.compile().interruptBefore().contains("hitl:a"));
        assertEquals("REPLACE", normalized.keyStrategies().get("approved_text"));
        assertTrue(HitlGraphSupport.validateHitlNodes(normalized).isEmpty());
    }
}
