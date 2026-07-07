package io.acelance.graph.dsl.script;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptEngineRegistryTest {

    @Test
    void registersEnginesById() {
        var registry = new ScriptEngineRegistry(List.of(new AviatorScriptEngine(500), new SpelScriptEngine(500)));
        assertTrue(registry.supports("aviator"));
        assertTrue(registry.supports("spel"));
        assertFalse(registry.supports("groovy"));
        assertEquals("aviator", registry.require("aviator").engineId());
    }

    @Test
    void listDescriptorsReturnsSortedMetas() {
        var registry = new ScriptEngineRegistry(List.of(new AviatorScriptEngine(500), new SpelScriptEngine(500)));
        List<ScriptEngineDescriptor> descs = registry.listDescriptors();
        assertEquals(2, descs.size());
        // 按 id 字典序：aviator 在 spel 之前
        assertEquals("aviator", descs.get(0).id());
        assertEquals("spel", descs.get(1).id());
    }

    @Test
    void requireThrowsForUnknown() {
        var registry = new ScriptEngineRegistry(List.of());
        assertThrows(IllegalArgumentException.class, () -> registry.require("nope"));
    }
}
