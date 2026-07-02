package io.acelance.graph.dsl.script;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptEdgeActionFactoryTest {

    private final ScriptEdgeActionFactory factory =
            new ScriptEdgeActionFactory(new ScriptEngineRegistry(List.of(new AviatorScriptEngine(1000))));

    @Test
    void routesByExpression() throws Exception {
        EdgeAction action = factory.create("aviator", "state.score > 60 ? 'pass' : 'fail'");

        assertEquals("pass", action.apply(new OverAllState(Map.of("score", 80))));
        assertEquals("fail", action.apply(new OverAllState(Map.of("score", 30))));
    }

    @Test
    void supportsKnownEngineOnly() {
        assertTrue(factory.supports("aviator"));
        assertFalse(factory.supports("groovy"));
    }

    @Test
    void validateRejectsInvalidExpression() {
        assertThrows(RuntimeException.class, () -> factory.validate("aviator", "!!!bad!!!"));
    }

    @Test
    void validateRejectsUnknownEngine() {
        assertThrows(IllegalArgumentException.class, () -> factory.validate("groovy", "state.x"));
    }
}
