package io.acelance.graph.dsl.definition;

import io.acelance.graph.dsl.runtime.ModelOverride;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericAgentSpecOverrideTest {

    @Test
    void withOverride_replacesNonNullAndUnmasksApiKey() {
        GenericAgentSpec base = new GenericAgentSpec(
                "https://base", "sk-abc", false, "qwen-plus", "p",
                null, "out", null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of(),
                io.acelance.graph.dsl.llm.MemoryMode.NONE, null, false,
                    false, null, null);

        ModelOverride ov = new ModelOverride("gpt-4o", "https://other", "sk-xyz");
        GenericAgentSpec r = base.withOverride(ov);

        assertEquals("gpt-4o", r.modelId());
        assertEquals("https://other", r.modelBaseUrl());
        assertEquals("sk-xyz", r.modelApiKey());
        assertFalse(r.apiKeyMasked(), "overridden apiKey should be plaintext");
        assertEquals("p", r.prompt());
        assertEquals("out", r.effectiveOutputKey());
    }

    @Test
    void withOverride_nullReturnsSelf() {
        GenericAgentSpec base = new GenericAgentSpec(
                null, null, false, "qwen-plus", "p",
                null, "out", null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of(),
                io.acelance.graph.dsl.llm.MemoryMode.NONE, null, false,
                    false, null, null);
        assertSame(base, base.withOverride(null));
    }

    @Test
    void looksLikeMaskedApiKey_detectsHttpPlaceholder() {
        org.junit.jupiter.api.Assertions.assertTrue(GenericAgentSpec.looksLikeMaskedApiKey("****1de1"));
        org.junit.jupiter.api.Assertions.assertTrue(GenericAgentSpec.looksLikeMaskedApiKey("****"));
        org.junit.jupiter.api.Assertions.assertFalse(GenericAgentSpec.looksLikeMaskedApiKey("sk-test-full-key-1de1"));
        org.junit.jupiter.api.Assertions.assertFalse(GenericAgentSpec.looksLikeMaskedApiKey(null));
        org.junit.jupiter.api.Assertions.assertFalse(GenericAgentSpec.looksLikeMaskedApiKey(""));
    }
}
