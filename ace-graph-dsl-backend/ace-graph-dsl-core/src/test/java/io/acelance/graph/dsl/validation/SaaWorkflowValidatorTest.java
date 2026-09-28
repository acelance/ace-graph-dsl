package io.acelance.graph.dsl.validation;

import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.agent.SubAgentBinding;
import io.acelance.graph.dsl.agent.SubAgentResolveRequest;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaaWorkflowValidatorTest {

    @Test
    void sequentialSpecPassesWhenModuleEnabled() {
        GraphNodeRegistry registry = registryWith("sql-gen", "sql-rater");
        SaaWorkflowValidator validator = new SaaWorkflowValidator(
                registry, List.of(new AcceptGenericResolver()), true);

        NodeRef ref = saaNode(new SaaWorkflowSpec(
                "SEQUENTIAL", "user_query", "sql_score", "BIZ",
                null, null, null, null, null,
                List.of(
                        new SaaSubAgentRef("g", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:sql-gen", "{user_query}", "sql"),
                        new SaaSubAgentRef("r", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:sql-rater", "{sql}", "score")
                )));

        List<String> errors = new ArrayList<>();
        validator.validateNode("g1", ref, errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }

    @Test
    void moduleDisabledFails() {
        SaaWorkflowValidator validator = new SaaWorkflowValidator(
                new GraphNodeRegistry(List.of()), List.of(), false);
        NodeRef ref = saaNode(new SaaWorkflowSpec(
                "SEQUENTIAL", "user_query", "out", null,
                null, null, null, null, null,
                List.of(new SaaSubAgentRef("a", null, "generic:sql-gen", null, "x"))));
        List<String> errors = new ArrayList<>();
        validator.validateNode("g1", ref, errors);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("未启用"));
    }

    @Test
    void routingNeedsAtLeastTwoSubAgents() {
        GraphNodeRegistry registry = registryWith("sql-gen");
        SaaWorkflowValidator validator = new SaaWorkflowValidator(
                registry, List.of(new AcceptGenericResolver()), true);
        NodeRef ref = saaNode(new SaaWorkflowSpec(
                "ROUTING", "user_query", "out", null,
                "nacos_agent_node", null, null, null, null,
                List.of(new SaaSubAgentRef("a", null, "generic:sql-gen", null, "x"))));
        List<String> errors = new ArrayList<>();
        validator.validateNode("g1", ref, errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("至少需要 2")));
    }

    @Test
    void parallelNeedsAtLeastTwoSubAgents() {
        GraphNodeRegistry registry = registryWith("sql-gen");
        SaaWorkflowValidator validator = new SaaWorkflowValidator(
                registry, List.of(new AcceptGenericResolver()), true);
        NodeRef ref = saaNode(new SaaWorkflowSpec(
                "PARALLEL", "user_query", "out", null,
                null, null, null, null, null,
                List.of(new SaaSubAgentRef("a", null, "generic:sql-gen", null, "x"))));
        List<String> errors = new ArrayList<>();
        validator.validateNode("g1", ref, errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("PARALLEL") && e.contains("至少需要 2")));
    }

    @Test
    void loopWithExitConditionPasses() {
        GraphNodeRegistry registry = registryWith("sql-gen");
        SaaWorkflowValidator validator = new SaaWorkflowValidator(
                registry, List.of(new AcceptGenericResolver()), true);
        NodeRef ref = saaNode(new SaaWorkflowSpec(
                "LOOP", "user_query", "out", null,
                null, 3, "score", "GT", "0.8",
                List.of(new SaaSubAgentRef("a", null, "generic:sql-gen", null, "score"))));
        List<String> errors = new ArrayList<>();
        validator.validateNode("g1", ref, errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }

    @Test
    void loopCountOnlyWithoutExitKeyPasses() {
        GraphNodeRegistry registry = registryWith("sql-gen");
        SaaWorkflowValidator validator = new SaaWorkflowValidator(
                registry, List.of(new AcceptGenericResolver()), true);
        NodeRef ref = saaNode(new SaaWorkflowSpec(
                "LOOP", "user_query", "out", null,
                null, 2, null, null, null,
                List.of(new SaaSubAgentRef("a", null, "generic:sql-gen", null, "score"))));
        List<String> errors = new ArrayList<>();
        validator.validateNode("g1", ref, errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }

    private static NodeRef saaNode(SaaWorkflowSpec spec) {
        return new NodeRef("sql_quality", "SAA_WORKFLOW", Map.of(), null, null,
                null, null, null, null, spec);
    }

    private static GraphNodeRegistry registryWith(String... ids) {
        List<RegisteredGraphNode> nodes = new ArrayList<>();
        for (String id : ids) {
            nodes.add(stub(id));
        }
        return new GraphNodeRegistry(nodes);
    }

    private static GraphBoundAgentNode stub(String id) {
        GenericAgentSpec spec = new GenericAgentSpec(
                null, null, false, "m", null,
                "user_query", "agent_result", null, null,
                false, List.of(), false, null,
                false, List.of(), false, List.of(), Map.of(),
                false, List.of(), MemoryMode.NONE, false,
                false, null, null);
        return new GraphBoundAgentNode() {
            @Override
            public GraphBoundAgentNode withGraphId(String graphId) {
                return this;
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> variables) {
                return Map.of();
            }

            @Override
            public GenericAgentSpec agentSpec() {
                return spec;
            }

            @Override
            public GraphNodeDescriptor descriptor() {
                return new GraphNodeDescriptor(
                        id, id, GraphNodeDescriptor.CATEGORY_GENERIC_AGENT,
                        "", Set.of(), Set.of("agent_result"), false, "1", Map.of());
            }

            @Override
            public com.alibaba.cloud.ai.graph.action.NodeAction toAction(NodeRuntimeContext ctx) {
                return state -> Map.of();
            }
        };
    }

    private static final class AcceptGenericResolver implements SubAgentResolver {
        @Override
        public boolean supports(String impl) {
            return SaaSubAgentRefs.IMPL_GENERIC_AGENT.equalsIgnoreCase(
                    impl == null || impl.isBlank() ? SaaSubAgentRefs.IMPL_GENERIC_AGENT : impl);
        }

        @Override
        public SubAgentBinding resolve(SubAgentResolveRequest request) {
            return new SubAgentBinding(request.ref().name(), request.ref().effectiveImpl(),
                    request.ref().ref(), request.ref().outputKey(), new Object());
        }
    }
}
