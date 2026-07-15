package io.acelance.graph.dsl.script.groovy;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import io.acelance.graph.dsl.script.ScriptEdgeActionFactory;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptEdgeActionFactoryGroovyTest {

    private final ScriptEdgeActionFactory factory =
            new ScriptEdgeActionFactory(new ScriptEngineRegistry(List.of(
                    new GroovySandboxScriptEngine(1000, 16, List.of("java.util.*", "java.math.*", "java.time.*")))));

    @Test
    void routesByGroovyExpression() throws Exception {
        assertTrue(factory.supports("groovy"));
        EdgeAction action = factory.create("groovy", "state.score > 60 ? 'pass' : 'fail'");

        assertEquals("pass", action.apply(new OverAllState(Map.of("score", 80))));
        assertEquals("fail", action.apply(new OverAllState(Map.of("score", 30))));
    }
}
