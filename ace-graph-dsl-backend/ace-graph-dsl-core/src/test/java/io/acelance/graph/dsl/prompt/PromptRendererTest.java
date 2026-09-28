package io.acelance.graph.dsl.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    @Test
    void rendersStatePrefixedAndBareKeys() {
        String out = renderer.render(
                "订单 {{ state.order_no }} / {{user_name}}",
                Map.of("order_no", "A1", "user_name", "张三"),
                "n1");
        assertEquals("订单 A1 / 张三", out);
    }

    @Test
    void singlePassDoesNotRescanReplacement() {
        String out = renderer.render(
                "X={{a}}",
                Map.of("a", "{{b}}", "b", "LEAK"),
                "n1");
        assertEquals("X={{b}}", out);
    }

    @Test
    void quoteReplacementHandlesDollarAndBackslash() {
        String out = renderer.render("v={{x}}", Map.of("x", "$100 \\path"), "n1");
        assertEquals("v=$100 \\path", out);
    }

    @Test
    void missingVariableDefaultsToEmpty() {
        String out = renderer.render("hi {{missing}}", Map.of(), "n1");
        assertEquals("hi ", out);
    }

    @Test
    void strictModeFailsOnMissing() {
        PromptRenderer strict = new PromptRenderer(null,
                new PromptRenderProperties(true, 100, 1000));
        assertThrows(IllegalStateException.class,
                () -> strict.render("{{gone}}", Map.of(), "n1"));
    }

    @Test
    void truncatesSingleValue() {
        PromptRenderer r = new PromptRenderer(null,
                new PromptRenderProperties(false, 5, 1000));
        String out = r.render("{{t}}", Map.of("t", "abcdefgh"), "n1");
        assertEquals("abcde", out);
    }

    @Test
    void mapBecomesJson() {
        String out = renderer.render("{{m}}", Map.of("m", Map.of("k", 1)), "n1");
        assertTrue(out.contains("\"k\""));
        assertTrue(out.contains("1"));
    }

    @Test
    void findPlaceholders() {
        Set<String> names = renderer.findPlaceholders("{{a}} and {{ state.b }}");
        assertEquals(Set.of("a", "b"), names);
    }
}
