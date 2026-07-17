package io.acelance.graph.dsl.builder;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphEdge;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeParamReachabilityValidatorTest {

    private final GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
            node("intake", GraphNodeDescriptor.CATEGORY_NORMAL,
                    Set.of("query", "product_id"), Set.of("normalized_query")),
            node("draft", GraphNodeDescriptor.CATEGORY_NORMAL,
                    Set.of(), Set.of("reply_draft")),
            node("human", GraphNodeDescriptor.CATEGORY_HITL,
                    Set.of("feed_back"), Set.of("human_next_node")),
            node("translate", GraphNodeDescriptor.CATEGORY_NORMAL,
                    Set.of("reply_draft"), Set.of("translate_en")),
            node("rag", GraphNodeDescriptor.CATEGORY_NORMAL,
                    Set.of("query"), Set.of("knowledge_summary"))
    ));
    private final EdgeParamReachabilityValidator validator = new EdgeParamReachabilityValidator(registry);

    @Test
    void validChainPasses() {
        GraphDefinition def = new GraphDefinition(
                "g1", "test", "1.0.0", "",
                Map.of("reply_draft", "REPLACE", "translate_en", "REPLACE"),
                List.of(
                        new NodeRef("draft", Map.of(), null, null),
                        new NodeRef("translate", Map.of(), null, null)),
                List.of(
                        new GraphEdge(GraphDefinition.START, "draft", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("draft", "translate", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("translate", GraphDefinition.END, GraphEdge.TYPE_NORMAL, null, null, null, null)),
                null, null);

        List<String> errors = validator.validate(def);

        assertTrue(errors.isEmpty(), () -> "应通过但有错误: " + errors);
    }

    @Test
    void missingUpstreamOutputFails() {
        GraphDefinition def = new GraphDefinition(
                "g1", "test", "1.0.0", "",
                Map.of("translate_en", "REPLACE"),
                List.of(new NodeRef("translate", Map.of(), null, null)),
                List.of(
                        new GraphEdge(GraphDefinition.START, "rag", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("rag", "translate", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("translate", GraphDefinition.END, GraphEdge.TYPE_NORMAL, null, null, null, null)),
                null, null);

        List<String> errors = validator.validate(def);

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("reply_draft")),
                () -> "错误: " + errors);
    }

    @Test
    void startToBusinessNodeSkipsValidation() {
        GraphDefinition def = new GraphDefinition(
                "g1", "test", "1.0.0", "",
                Map.of("normalized_query", "REPLACE"),
                List.of(new NodeRef("intake", Map.of(), null, null)),
                List.of(
                        new GraphEdge(GraphDefinition.START, "intake", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("intake", GraphDefinition.END, GraphEdge.TYPE_NORMAL, null, null, null, null)),
                null, null);

        List<String> errors = validator.validate(def);

        assertTrue(errors.isEmpty(), () -> "START 出边应跳过校验: " + errors);
    }

    @Test
    void normalToHitlSkipsValidation() {
        GraphDefinition def = new GraphDefinition(
                "g1", "test", "1.0.0", "",
                Map.of("reply_draft", "REPLACE", "feed_back", "REPLACE"),
                List.of(
                        new NodeRef("draft", Map.of(), null, null),
                        new NodeRef("human", Map.of(), null, null)),
                List.of(
                        new GraphEdge(GraphDefinition.START, "draft", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("draft", "human", GraphEdge.TYPE_NORMAL, null, null, null, null),
                        new GraphEdge("human", GraphDefinition.END, GraphEdge.TYPE_NORMAL, null, null, null, null)),
                null, null);

        List<String> errors = validator.validate(def);

        assertTrue(errors.isEmpty(), () -> "普通节点到 HITL 应跳过校验: " + errors);
    }

    private static RegisteredGraphNode node(String id, String category, Set<String> inputs, Set<String> outputs) {
        return new RegisteredGraphNode() {
            @Override
            public GraphNodeDescriptor descriptor() {
                return new GraphNodeDescriptor(id, id, category, "",
                        inputs, outputs, false, "1.0.0", Map.of());
            }

            @Override
            public NodeAction toAction(NodeRuntimeContext ctx) {
                return state -> Map.of();
            }
        };
    }

    private static RegisteredGraphNode node(String id, Set<String> inputs, Set<String> outputs) {
        return node(id, GraphNodeDescriptor.CATEGORY_NORMAL, inputs, outputs);
    }
}
