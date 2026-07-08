package io.acelance.graph.dsl.script.qlexpress;

import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.ScriptExecutionContext;
import io.acelance.graph.dsl.script.ScriptNodeFactory;
import io.acelance.graph.dsl.script.ScriptOutputNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QlExpressScriptEngineTest {

    private final QlExpressScriptEngine engine = new QlExpressScriptEngine(1000);

    @Test
    void invalidScriptThrows() {
        assertThrows(IllegalArgumentException.class, () -> engine.validate("!!!invalid!!!"));
    }

    @Test
    void importStatementRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("import java.util.Date; return 1;"));
    }

    @Test
    void multiLineIfElseReturnsMap() {
        String script = """
                amount = state.amount;
                discount = state.discount > 0 ? state.discount : 1.0;
                price = amount * discount;
                if (state.member == 'vip') {
                  price = price * 0.9;
                }
                return map('final_price', price);
                """;
        engine.validate(script);
        Object compiled = engine.compile(script);

        ScriptExecutionContext ctx = new ScriptExecutionContext(
                Map.of("amount", 100, "discount", 0.8, "member", "vip"),
                Map.of());

        Object result = engine.execute(compiled, ctx);
        Map<String, Object> output = ScriptOutputNormalizer.normalize(result, Set.of("final_price"));

        assertEquals(72.0, output.get("final_price"));
    }

    @Test
    void timeoutInterruptsInfiniteLoop() {
        QlExpressScriptEngine shortEngine = new QlExpressScriptEngine(200);
        // QLExpress 不支持 while 死循环语法，改用大上界 for 循环触发执行超时
        Object compiled = shortEngine.compile("for (i = 0; i < 1000000000; i = i + 1) { i * i }");
        ScriptExecutionContext ctx = new ScriptExecutionContext(Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> shortEngine.execute(compiled, ctx));
    }

    @Test
    void registeredInRegistry() {
        var registry = new ScriptEngineRegistry(List.of(engine));
        assertTrue(registry.supports("qlexpress"));
        assertEquals("qlexpress", engine.descriptor().id());
        assertTrue(engine.descriptor().multiLine());
    }

    @Test
    void scriptNodeFactoryIntegration() {
        var registry = new ScriptEngineRegistry(List.of(engine));
        var factory = new ScriptNodeFactory(registry);
        var def = new io.acelance.graph.dsl.definition.DynamicNodeDefinition(
                "script:test_qlexpress",
                "测试 QLExpress",
                "NORMAL",
                "discount price",
                Set.of("amount", "discount", "member"),
                Set.of("final_price"),
                false,
                "1.0.0",
                "qlexpress",
                "return map('final_price', state.amount * state.discount)",
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
        assertEquals("script:test_qlexpress", node.descriptor().nodeId());
    }
}
