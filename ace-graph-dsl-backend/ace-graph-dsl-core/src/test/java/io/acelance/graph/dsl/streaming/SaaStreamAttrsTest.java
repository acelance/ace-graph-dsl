package io.acelance.graph.dsl.streaming;

import io.acelance.graph.dsl.bizparam.DefaultStringBizParamInterpreter;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SaaStreamAttrs} 与旧 Spec JSON 缺省兼容。
 */
class SaaStreamAttrsTest {

    @Test
    void disabledBizParamsYieldsEmptyAttrs() {
        SaaWorkflowSpec spec = SaaWorkflowSpec.of(
                "SEQUENTIAL", "user_query", "agent_result", "BIZ",
                null, null, null, null, null,
                List.of(new SaaSubAgentRef("a", "GENERIC_AGENT", "generic:a", "{user_query}", "step1")));
        assertFalse(spec.enableBizParams());
        assertTrue(SaaStreamAttrs.fromSpec(spec).isEmpty());
        assertTrue(SaaStreamAttrs.fromSpec(null).isEmpty());
    }

    @Test
    void enabledBizParamsPopulateTokenChunkKeys() {
        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "SEQUENTIAL", "user_query", "agent_result", "BIZ",
                null, null, null, null, null,
                List.of(new SaaSubAgentRef("a", "GENERIC_AGENT", "generic:a", "{user_query}", "step1")),
                true,
                true,
                "lesso.sse-frame",
                "{\"thinking\":true,\"nodeDisplay\":\"SAA父\"}",
                null, null, null, null, null);
        Map<String, Object> attrs = SaaStreamAttrs.fromSpec(spec);
        assertEquals(Boolean.TRUE, attrs.get(TokenChunk.ATTR_ENABLE_BIZ_PARAMS));
        assertEquals("lesso.sse-frame", attrs.get(TokenChunk.ATTR_BIZ_PARAM_INTERPRETER_ID));
        assertTrue(String.valueOf(attrs.get(TokenChunk.ATTR_BIZ_PARAM_RAW)).contains("thinking"));
        assertTrue(spec.applyDeepThinking());
    }

    @Test
    void enableBizParamsDefaultsInterpreterId() {
        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "SEQUENTIAL", "q", "out", null,
                null, null, null, null, null, List.of(),
                false, true, null, "x", null, null, null, null, null);
        assertEquals(DefaultStringBizParamInterpreter.ID, spec.bizParamInterpreterId());
    }
}
