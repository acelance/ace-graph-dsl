package io.acelance.graph.dsl.builder;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphEdge;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.acelance.graph.dsl.script.AviatorScriptEngine;
import io.acelance.graph.dsl.script.ScriptEdgeActionFactory;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator(
            new GraphNodeRegistry(List.of(node("a"), node("b"), node("c"))),
            new EdgeDispatcherRegistry(List.of()),
            new ScriptEdgeActionFactory(new ScriptEngineRegistry(List.of(new AviatorScriptEngine(1000)))));

    @Test
    void scriptConditionalEdgePasses() {
        GraphDefinition def = graphWith(scriptEdge(
                "a", "state.x > 0 ? 'hi' : 'lo'", Map.of("hi", "b", "lo", "c")));

        ValidationResult result = validator.validate(def);

        assertTrue(result.ok(), () -> "应通过但有错误: " + result.errors());
    }

    @Test
    void scriptConditionalEdgeWithInvalidExpressionFails() {
        GraphDefinition def = graphWith(scriptEdge(
                "a", "!!!bad!!!", Map.of("hi", "b", "lo", "c")));

        ValidationResult result = validator.validate(def);

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("脚本表达式非法")),
                () -> "错误: " + result.errors());
    }

    @Test
    void scriptConditionalEdgeWithMissingTargetFails() {
        GraphDefinition def = graphWith(scriptEdge(
                "a", "state.x > 0 ? 'hi' : 'lo'", Map.of("hi", "b", "lo", "missing")));

        ValidationResult result = validator.validate(def);

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("mapping 目标不存在")),
                () -> "错误: " + result.errors());
    }

    @Test
    void conditionalEdgeWithUnregisteredDispatcherFails() {
        GraphEdge edge = new GraphEdge("a", null, GraphEdge.TYPE_CONDITIONAL,
                "missingDispatcher", Map.of("hi", "b", "lo", "c"), null, null);
        GraphDefinition def = graphWith(edge);

        ValidationResult result = validator.validate(def);

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("dispatcher 未注册")),
                () -> "错误: " + result.errors());
    }

    private static GraphDefinition graphWith(GraphEdge conditional) {
        return new GraphDefinition(
                "g1", "测试图", "1.0.0", "",
                Map.of(),
                List.of(new NodeRef("a", Map.of()), new NodeRef("b", Map.of()), new NodeRef("c", Map.of())),
                List.of(
                        new GraphEdge(GraphDefinition.START, "a", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        conditional,
                        new GraphEdge("b", GraphDefinition.END, GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("c", GraphDefinition.END, GraphEdge.TYPE_NORMAL, null, null, null, null)),
                null, null);
    }

    private static GraphEdge scriptEdge(String from, String condition, Map<String, String> mapping) {
        return new GraphEdge(from, null, GraphEdge.TYPE_CONDITIONAL, null, mapping, condition, null);
    }

    private static RegisteredGraphNode node(String id) {
        return new RegisteredGraphNode() {
            @Override
            public GraphNodeDescriptor descriptor() {
                return new GraphNodeDescriptor(id, id, GraphNodeDescriptor.CATEGORY_NORMAL, "",
                        Set.of(), Set.of(), false, "1.0.0", Map.of());
            }

            @Override
            public NodeAction toAction(NodeRuntimeContext ctx) {
                return state -> Map.of();
            }
        };
    }
}
