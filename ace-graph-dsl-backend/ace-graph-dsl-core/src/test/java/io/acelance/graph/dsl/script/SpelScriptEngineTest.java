package io.acelance.graph.dsl.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpelScriptEngineTest {

    private final SpelScriptEngine engine = new SpelScriptEngine(1000);

    @Test
    void descriptorDeclaresSingleLine() {
        ScriptEngineDescriptor d = engine.descriptor();
        assertEquals("spel", d.id());
        assertEquals("SpEL 表达式", d.label());
        assertFalse(d.multiLine());
        assertEquals(3, d.maxScriptLines());
    }

    @Test
    void invalidSyntaxThrows() {
        assertThrows(IllegalArgumentException.class, () -> engine.validate("!!!bad!!!"));
    }

    @Test
    void executeReturnsScalar() {
        Object compiled = engine.compile("#state['query']?.trim()");
        ScriptExecutionContext ctx = new ScriptExecutionContext(Map.of("query", "  hi  "), Map.of());
        Object result = engine.execute(compiled, ctx);
        assertEquals("hi", result);
    }

    @Test
    void executeReturnsMap() {
        Object compiled = engine.compile("{'normalized': #state['query']?.trim()?.toLowerCase()}");
        ScriptExecutionContext ctx = new ScriptExecutionContext(Map.of("query", "  Hi  "), Map.of());
        Object result = engine.execute(compiled, ctx);
        Map<String, Object> out = ScriptOutputNormalizer.normalize(result, Set.of("normalized"));
        assertEquals("hi", out.get("normalized"));
    }

    @Test
    void typeReferenceRejected() {
        // T(java.lang.Runtime) 应通过 TypeLocator 被拒绝
        assertThrows(IllegalArgumentException.class, () ->
                engine.execute(engine.compile("T(java.lang.Runtime)"), new ScriptExecutionContext(Map.of(), Map.of())));
    }

    @Test
    void beanReferenceRejected() {
        // 未注册 BeanFactoryResolver，@bean 引用应被拒绝
        assertThrows(IllegalArgumentException.class, () ->
                engine.execute(engine.compile("@systemProperties"), new ScriptExecutionContext(Map.of(), Map.of())));
    }

    @Test
    void timeoutInterruptsLongProjection() {
        // SpEL 无 while；用超短超时 + 大集合投影触发 AbstractTimeoutScriptEngine 超时
        SpelScriptEngine shortEngine = new SpelScriptEngine(50);
        List<Integer> nums = IntStream.range(0, 800_000).boxed().collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        Object compiled = shortEngine.compile("#state['nums'].?[true]");
        ScriptExecutionContext ctx = new ScriptExecutionContext(Map.of("nums", nums), Map.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> shortEngine.execute(compiled, ctx));
        assertTrue(ex.getMessage().contains("超时"), () -> "消息: " + ex.getMessage());
    }
}
