package io.acelance.graph.dsl.hitl;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.definition.HitlNodeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HitlReviewNodeActionTest {

    @Test
    void echoesPresentKeysAndSkipsMissing() {
        HitlReviewNodeAction action = new HitlReviewNodeAction(
                "g1", "hitl:r", new HitlNodeSpec(List.of("approved_text", "missing_key")));
        OverAllState state = new OverAllState(Map.of(
                "approved_text", "人工定稿",
                "ace.graph.dsl.runId", "run-1"));
        Map<String, Object> out = action.apply(state);
        assertEquals("人工定稿", out.get("approved_text"));
        assertFalse(out.containsKey("missing_key"));
    }
}
