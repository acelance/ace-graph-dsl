package io.acelance.graph.dsl.skill;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForceSkillsEffectiveKeysTest {

    @Test
    void effectiveSkillKeys_unionsForceSkillsOntoNodeWhitelist() {
        Map<String, Object> data = new HashMap<>();
        data.put(LlmRequestContext.ACE_FORCE_SKILLS_KEY,
                List.of("attendance-ot-calculator", "excel-summary-report"));
        OverAllState state = new OverAllState(data);

        List<String> effective = ForceSkills.effectiveSkillKeys(
                state, "biz", List.of("weather", "attendance-ot-calculator"));

        assertEquals(
                List.of("weather", "attendance-ot-calculator", "excel-summary-report"),
                effective);
    }

    @Test
    void effectiveSkillKeys_forceOnly_whenNodeWhitelistEmpty() {
        Map<String, Object> data = new HashMap<>();
        data.put(LlmRequestContext.ACE_FORCE_SKILLS_KEY, List.of("excel-summary-report"));
        OverAllState state = new OverAllState(data);

        List<String> effective = ForceSkills.effectiveSkillKeys(state, "biz", List.of());

        assertEquals(List.of("excel-summary-report"), effective);
    }
}
