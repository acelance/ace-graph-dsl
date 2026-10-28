package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.streaming.SaaStreamAttrs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SaaWorkflowSpec / NodeRef.saaSpec JSON ??????????
 */
class SaaWorkflowSpecJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void nodeRefRoundTripPreservesSaaSpec() throws Exception {
        SaaWorkflowSpec spec = SaaWorkflowSpec.of(
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
        NodeRef node = new NodeRef("sql_quality", "SAA_WORKFLOW", Map.of("label", "??"),
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
        assertFalse(back.saaSpec().applyDeepThinking());
        assertFalse(back.saaSpec().enableBizParams());
    }

    @Test
    void oldJsonWithoutChannelFieldsDeserializesCompatibly() throws Exception {
        String legacy = """
                {"pattern":"SEQUENTIAL","inputKeys":"user_query","outputKey":"agent_result",
                "streamResponseKind":"BIZ","subAgents":[]}
                """;
        SaaWorkflowSpec spec = mapper.readValue(legacy, SaaWorkflowSpec.class);
        assertEquals(SaaWorkflowPattern.SEQUENTIAL, spec.resolvedPattern());
        assertFalse(spec.applyDeepThinking());
        assertFalse(spec.enableBizParams());
        assertTrue(SaaStreamAttrs.fromSpec(spec).isEmpty());
    }

    @Test
    void routingRouterPromptFieldsRoundTrip() throws Exception {
        String json = """
                {"pattern":"ROUTING","inputKeys":"user_query","outputKey":"agent_result",
                "streamResponseKind":"BIZ","modelConfigKey":"models:router",
                "routerSystemPrompt":"? loan_interest ? discount_auto",
                "routerInstruction":"??? agent ?",
                "routerFallbackAgent":"loan_interest",
                "subAgents":[
                  {"name":"loan_interest","impl":"GENERIC_AGENT","ref":"generic:a","instruction":"{user_query}","outputKey":"a"},
                  {"name":"discount_auto","impl":"GENERIC_AGENT","ref":"generic:b","instruction":"{user_query}","outputKey":"b"}
                ]}
                """;
        SaaWorkflowSpec spec = mapper.readValue(json, SaaWorkflowSpec.class);
        assertEquals(SaaWorkflowPattern.ROUTING, spec.resolvedPattern());
        assertEquals("? loan_interest ? discount_auto", spec.routerSystemPrompt());
        assertEquals("??? agent ?", spec.routerInstruction());
        assertEquals("loan_interest", spec.routerFallbackAgent());

        String back = mapper.writeValueAsString(spec);
        SaaWorkflowSpec again = mapper.readValue(back, SaaWorkflowSpec.class);
        assertEquals(spec.routerSystemPrompt(), again.routerSystemPrompt());
        assertEquals(spec.routerFallbackAgent(), again.routerFallbackAgent());
    }
}
