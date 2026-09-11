package io.acelance.graph.dsl.ai.skill;

import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.skill.InMemorySkillStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolsTest {

    @Test
    void loadSkill_rejectsOutOfWhitelist() {
        InMemorySkillStore store = new InMemorySkillStore();
        store.put("skill.refund", "Refund", "d", "BODY");
        ResourceBinding binding = new ResourceBinding(
                false, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), true, List.of("skill.refund"));
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "r", null, binding);
        Set<String> activated = new LinkedHashSet<>();
        List<NamedToolCallback> tools = SkillTools.builtin(
                ctx, binding.skillKeys(), store, store, activated);
        NamedToolCallback load = tools.stream()
                .filter(t -> SkillTools.LOAD_SKILL.equals(t.uniqueName()))
                .findFirst()
                .orElseThrow();
        String denied = load.delegate().call("{\"code\":\"skill.invoice\"}");
        assertTrue(denied.contains("ERROR"));
        String ok = load.delegate().call("{\"code\":\"skill.refund\"}");
        assertTrue(ok.contains("BODY"));
        String dup = load.delegate().call("{\"code\":\"skill.refund\"}");
        assertTrue(dup.contains("already activated"));
    }

    @Test
    void readResource_rejectsPathTraversal() {
        InMemorySkillStore store = new InMemorySkillStore();
        store.put("skill.refund", "Refund", "d", "BODY");
        store.putResource("skill.refund", "scripts/a.py", "print(1)");
        ResourceBinding binding = new ResourceBinding(
                false, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), true, List.of("skill.refund"));
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "r", null, binding);
        NamedToolCallback read = SkillTools.builtin(
                        ctx, binding.skillKeys(), store, store, new LinkedHashSet<>())
                .stream()
                .filter(t -> SkillTools.READ_RESOURCE.equals(t.uniqueName()))
                .findFirst()
                .orElseThrow();
        assertTrue(read.delegate().call("{\"code\":\"skill.refund\",\"path\":\"../x\"}").contains("ERROR"));
        assertTrue(read.delegate().call("{\"code\":\"skill.refund\",\"path\":\"scripts/a.py\"}").contains("print"));
    }
}
