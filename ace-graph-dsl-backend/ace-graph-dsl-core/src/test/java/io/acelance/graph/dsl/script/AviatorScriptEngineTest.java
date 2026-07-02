package io.acelance.graph.dsl.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AviatorScriptEngineTest {

    private final AviatorScriptEngine engine = new AviatorScriptEngine(1000);

    @Test
    void executeNormalizeScript() {
        String script = "seq.map('normalized_query', state.prefix + state.query)";
        engine.validate(script);
        Object compiled = engine.compile(script);

        ScriptExecutionContext ctx = new ScriptExecutionContext(
                Map.of("prefix", "q:", "query", "hello"),
                Map.of());

        Object result = engine.execute(compiled, ctx);
        Map<String, Object> output = ScriptOutputNormalizer.normalize(result, Set.of("normalized_query"));

        assertEquals("q:hello", output.get("normalized_query"));
    }

    @Test
    void executeWithConfig() {
        String script = "seq.map('result', long(state.value) * long(config.multiplier))";
        Object compiled = engine.compile(script);
        ScriptExecutionContext ctx = new ScriptExecutionContext(
                Map.of("value", 10),
                Map.of("multiplier", 3));

        Object result = engine.execute(compiled, ctx);
        Map<String, Object> output = ScriptOutputNormalizer.normalize(result, Set.of("result"));

        assertEquals(30L, output.get("result"));
    }

    @Test
    void invalidScriptThrows() {
        assertThrows(IllegalArgumentException.class, () -> engine.validate("!!!invalid!!!"));
    }

    @Test
    void scriptRegisteredGraphNodeIntegration() {
        var registry = new ScriptEngineRegistry(java.util.List.of(engine));
        var factory = new ScriptNodeFactory(registry);
        var def = new io.acelance.graph.dsl.definition.DynamicNodeDefinition(
                "script:test_normalize",
                "测试标准化",
                "NORMAL",
                "trim query",
                Set.of("query"),
                Set.of("normalized_query"),
                false,
                "1.0.0",
                "aviator",
                "seq.map('normalized_query', state.query)",
                "hash",
                Map.of(),
                io.acelance.graph.dsl.registry.NodeOrigin.SCRIPT,
                Set.of(),
                "test",
                java.time.Instant.now(),
                java.time.Instant.now(),
                true
        );

        var node = factory.create(def);
        var action = node.toAction(io.acelance.graph.dsl.registry.NodeRuntimeContext.empty(null));

        assertTrue(node.descriptor().origin() == io.acelance.graph.dsl.registry.NodeOrigin.SCRIPT);
        assertEquals("script:test_normalize", node.descriptor().nodeId());
    }
}
