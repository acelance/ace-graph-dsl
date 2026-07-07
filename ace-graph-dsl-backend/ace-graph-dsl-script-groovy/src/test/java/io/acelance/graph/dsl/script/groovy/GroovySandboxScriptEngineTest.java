package io.acelance.graph.dsl.script.groovy;

import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.ScriptExecutionContext;
import io.acelance.graph.dsl.script.ScriptOutputNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovySandboxScriptEngineTest {

    private final GroovySandboxScriptEngine engine =
            new GroovySandboxScriptEngine(1000, 16, List.of("java.util.*", "java.math.*", "java.time.*"));

    @Test
    void invalidScriptThrows() {
        assertThrows(IllegalArgumentException.class, () -> engine.validate("def x = ("));
    }

    @Test
    void returnsMap() {
        String script = """
                def q = (state.query as String)?.trim()?.toLowerCase()
                return [normalized_query: q]
                """;
        engine.validate(script);
        Object compiled = engine.compile(script);

        ScriptExecutionContext ctx = new ScriptExecutionContext(
                Map.of("query", "  Hello  "),
                Map.of());

        Object result = engine.execute(compiled, ctx);
        Map<String, Object> output = ScriptOutputNormalizer.normalize(result, Set.of("normalized_query"));

        assertEquals("hello", output.get("normalized_query"));
    }

    @Test
    void collectionOperations() {
        String script = """
                def grouped = (state.items as List)
                    ?.groupBy { it.category }
                    ?.collectEntries { k, v -> [k, v.size()] } ?: [:]
                return [group_counts: grouped]
                """;
        Object compiled = engine.compile(script);
        ScriptExecutionContext ctx = new ScriptExecutionContext(
                Map.of("items", List.of(
                        Map.of("category", "a"), Map.of("category", "a"), Map.of("category", "b"))),
                Map.of());

        Object result = engine.execute(compiled, ctx);
        Map<String, Object> output = ScriptOutputNormalizer.normalize(result, Set.of("group_counts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) output.get("group_counts");
        assertEquals(2, counts.get("a"));
        assertEquals(1, counts.get("b"));
    }

    @Test
    void timeoutInterruptsInfiniteLoop() {
        GroovySandboxScriptEngine shortEngine =
                new GroovySandboxScriptEngine(200, 16, List.of("java.util.*"));
        Object compiled = shortEngine.compile("while (true) { 1 + 1 }");
        ScriptExecutionContext ctx = new ScriptExecutionContext(Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> shortEngine.execute(compiled, ctx));
    }

    @Test
    void runtimeExecutionBlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("Runtime.getRuntime().exec('calc')"));
    }

    @Test
    void systemExitBlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("System.exit(0)"));
    }

    @Test
    void reflectionBlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("Class.forName('java.lang.Runtime')"));
    }

    @Test
    void registeredInRegistry() {
        var registry = new ScriptEngineRegistry(List.of(engine));
        assertTrue(registry.supports("groovy"));
        assertEquals("groovy", engine.descriptor().id());
        assertTrue(engine.descriptor().multiLine());
    }
}
