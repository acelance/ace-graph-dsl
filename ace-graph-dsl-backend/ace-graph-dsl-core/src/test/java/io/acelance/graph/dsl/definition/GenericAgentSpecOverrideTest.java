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
                false, List.of(), Map.of(), false, List.of());

        ModelOverride ov = new ModelOverride("gpt-4o", "https://other", "sk-xyz");
        GenericAgentSpec r = base.withOverride(ov);

        assertEquals("gpt-4o", r.modelId());
        assertEquals("https://other", r.modelBaseUrl());
        assertEquals("sk-xyz", r.modelApiKey());
        assertFalse(r.apiKeyMasked(), "覆盖的 apiKey 视为明文");
        assertEquals("p", r.prompt());
        assertEquals("out", r.effectiveOutputKey());
    }

    @Test
    void withOverride_nullReturnsSelf() {
        GenericAgentSpec base = new GenericAgentSpec(
                null, null, false, "qwen-plus", "p",
                null, "out", null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of());
        assertSame(base, base.withOverride(null));
    }
}
