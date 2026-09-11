package io.acelance.graph.dsl.skill;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillProgressiveDisclosureTest {

    @Test
    void forceSkills_normalizeListAndSkipNonList() {
        assertEquals(List.of("a", "b"), ForceSkills.normalize(List.of("a", " b "), "n1"));
        assertEquals(List.of("only"), ForceSkills.normalize("only", "n1"));
        assertEquals(List.of(), ForceSkills.normalize(Map.of("x", 1), "n1"));
    }

    @Test
    void l1Catalog_formatContainsKeysOnly() {
        String text = SkillL1Catalog.format(List.of(
                new SkillDescriptor("skill.refund", "Refund", "handle refunds", "when user asks refund")));
        assertTrue(text.contains("skill.refund"));
        assertTrue(text.contains("ace__skill__load_skill"));
        assertFalse(text.contains("FULL BODY"));
    }

    @Test
    void pathSafety_rejectsTraversal() {
        assertTrue(SkillPathSafety.isSafeRelativePath("scripts/run.py"));
        assertFalse(SkillPathSafety.isSafeRelativePath("../etc/passwd"));
        assertFalse(SkillPathSafety.isSafeRelativePath("/abs"));
        assertFalse(SkillPathSafety.inWhitelist("x", List.of("y")));
        assertTrue(SkillPathSafety.inWhitelist("x", List.of("x")));
    }

    @Test
    void inMemoryStore_l1DoesNotRequireBody() {
        InMemorySkillStore store = new InMemorySkillStore();
        store.put("skill.refund", "Refund", "short", "BODY-L2");
        ResourceBinding binding = new ResourceBinding(
                false, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), true, List.of("skill.refund", "missing"));
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "r", null, binding);
        List<SkillDescriptor> l1 = store.resolve(ctx, binding.skillKeys());
        assertEquals(1, l1.size());
        assertEquals("skill.refund", l1.get(0).key());
        assertEquals("BODY-L2", store.loadBody(ctx, "skill.refund").orElseThrow());
    }
}
