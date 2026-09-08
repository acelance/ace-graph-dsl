package io.acelance.graph.dsl.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelOverrideSpecTest {

    @Test
    void effectiveFor_prefersNodeOverGlobal() {
        ModelOverride global = new ModelOverride("gpt-4o", null, null);
        ModelOverride node = new ModelOverride("qwen-max", null, null);
        ModelOverrideSpec spec = new ModelOverrideSpec(global, Map.of("branchA", node));

        assertEquals("qwen-max", spec.effectiveFor("branchA").modelId());
        assertEquals("gpt-4o", spec.effectiveFor("otherNode").modelId());
        // 未知节点回退到 global 覆盖（优先级：node 级 > global 级）
        assertEquals("gpt-4o", spec.effectiveFor("missing").modelId());
    }

    @Test
    void effectiveFor_noOverrideReturnsNull() {
        // 既无 node 覆盖也无 global 覆盖时返回 null
        assertNull(new ModelOverrideSpec(null, null).effectiveFor("x"));
    }

    @Test
    void hasAny() {
        assertTrue(new ModelOverrideSpec(new ModelOverride("x", null, null), Map.of()).hasAny());
        assertTrue(new ModelOverrideSpec(null, Map.of("a", new ModelOverride("y", null, null))).hasAny());
        assertFalse(new ModelOverrideSpec(null, Map.of()).hasAny());
    }
}
