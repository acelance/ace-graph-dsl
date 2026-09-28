package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SaaWorkflowSpec / NodeRef.saaSpec JSON 往返。
 */
class SaaWorkflowSpecJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void nodeRefRoundTripPreservesSaaSpec() throws Exception {
        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "SEQUENTIAL",
                "user_query",
                "sql_score",
                "BIZ",
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new SaaSubAgentRef("sql_generator", "GENERIC_AGENT",
                                "generic:sql-gen", "{user_query}", "sql"),
                        new SaaSubAgentRef("sql_rater", null,
                                "generic:sql-rater", "SQL:{sql}", "score")
                ));
        NodeRef node = new NodeRef("sql_quality", "SAA_WORKFLOW", Map.of("label", "质量"),
                1.0, 2.0, null, null, null, null, spec);

        String json = mapper.writeValueAsString(node);
        NodeRef back = mapper.readValue(json, NodeRef.class);

        assertTrue(back.hasSaaSpec());
        assertEquals("SAA_WORKFLOW", back.category());
        assertNotNull(back.saaSpec());
        assertEquals(SaaWorkflowPattern.SEQUENTIAL, back.saaSpec().resolvedPattern());
        assertEquals(2, back.saaSpec().subAgents().size());
        assertEquals(SaaSubAgentRefs.IMPL_GENERIC_AGENT, back.saaSpec().subAgents().get(1).effectiveImpl());
        assertEquals("score", back.saaSpec().subAgents().get(1).outputKey());
    }
}
