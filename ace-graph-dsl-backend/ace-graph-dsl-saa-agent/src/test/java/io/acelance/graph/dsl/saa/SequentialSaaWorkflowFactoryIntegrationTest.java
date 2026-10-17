package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 集成：SaaWorkflowSpec → FactoryImpl → Sequential NodeAction → OverAllState 链式写回。
 *
 * <p>子 Agent 经 GenericAgentSubAgentResolver 包装为官方 BaseAgent；内部委托 stub GraphBoundAgentNode。</p>
 */
class SequentialSaaWorkflowFactoryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SequentialSaaWorkflowFactoryIntegrationTest.class);

    private static final String GRAPH_ID = "m1-saa-sequential";
    private static final String NODE_ID = "sql_quality";

    @Test
    void factoryBuildsSequentialNodeActionAndChainsSubAgentKeys() throws Exception {
        GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
                stubAgent("sql-gen", "agent_result", vars -> {
                    Object q = vars.getOrDefault("user_query", vars.get("input"));
                    return "SELECT 1 /* " + q + " */";
                }),
                stubAgent("sql-rater", "agent_result", vars -> {
                    Object sql = vars.get("sql");
                    assertNotNull(sql, "第二步应能从 state 读到 sql");
                    assertTrue(String.valueOf(sql).contains("SELECT"), "sql 应含 SELECT");
                    return "0.9";
                })
        ));

        SubAgentResolver resolver = new GenericAgentSubAgentResolver(registry);
        SaaWorkflowNodeFactoryImpl factory = new SaaWorkflowNodeFactoryImpl(List.of(resolver));

        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "SEQUENTIAL",
                "user_query",
                "sql_score",
                "BIZ",
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new SaaSubAgentRef("sql_generator", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:sql-gen", "{user_query}", "sql"),
                        new SaaSubAgentRef("sql_rater", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:sql-rater", "给以下 SQL 打分：{sql}", "score")
                )
        );

        NodeAction action = factory.create(GRAPH_ID, NODE_ID, spec, NodeRuntimeContext.empty(null));
        log.info("M1 Factory 已创建 Sequential NodeAction, graphId={}, nodeId={}", GRAPH_ID, NODE_ID);

        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put("user_query", new ReplaceStrategy());
        strategies.put("sql", new ReplaceStrategy());
        strategies.put("score", new ReplaceStrategy());
        strategies.put("sql_score", new ReplaceStrategy());

        StateGraph stateGraph = new StateGraph(() -> strategies);
        stateGraph.addNode(NODE_ID, node_async(action));
        stateGraph.addEdge(START, NODE_ID);
        stateGraph.addEdge(NODE_ID, END);

        CompiledGraph compiled = stateGraph.compile();
        OverAllState result = compiled.invoke(Map.of("user_query", "按门店汇总本月销售额"),
                        RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));

        Object sql = result.data().get("sql");
        Object score = result.data().get("score");
        Object parent = result.data().get("sql_score");

        log.info("M1 集成结果: sql={}, score={}, sql_score={}", sql, score, parent);

        assertNotNull(sql);
        assertTrue(String.valueOf(sql).contains("SELECT"));
        assertEquals("0.9", String.valueOf(score).trim());
        assertEquals("0.9", String.valueOf(parent).trim());
        Object subSteps = result.data().get(io.acelance.graph.dsl.definition.SaaWorkflowKeys.SUB_STEPS_KEY);
        assertNotNull(subSteps, "M4 应写出子步骤轨迹");
        assertTrue(subSteps instanceof java.util.List && !((java.util.List<?>) subSteps).isEmpty());
        log.info("M1 集成通过: Spec/Factory/BaseAgent包装/方式A 写回 OK, subSteps={}", subSteps);
    }

    @FunctionalInterface
    private interface StubLogic {
        String apply(Map<String, Object> variables);
    }

    private static GraphBoundAgentNode stubAgent(String nodeId, String outputKey, StubLogic logic) {
        GenericAgentSpec spec = new GenericAgentSpec(
                null, null, false, "stub", null,
                "user_query", outputKey, "BIZ", null,
                false, List.of(), false, null,
                false, List.of(), false, List.of(), Map.of(),
                false, List.of(), MemoryMode.NONE, null, false,
                false, null, null);

        return new GraphBoundAgentNode() {
            @Override
            public GraphBoundAgentNode withGraphId(String graphId) {
                return this;
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> variables) {
                log.info("stub GenericAgent execute, nodeId={}, varsKeys={}", nodeId,
                        variables == null ? null : variables.keySet());
                return Map.of(outputKey, logic.apply(variables == null ? Map.of() : variables));
            }

            @Override
            public GenericAgentSpec agentSpec() {
                return spec;
            }

            @Override
            public GraphNodeDescriptor descriptor() {
                return new GraphNodeDescriptor(
                        nodeId, nodeId, GraphNodeDescriptor.CATEGORY_GENERIC_AGENT,
                        "stub", Set.of("user_query"), Set.of(outputKey),
                        false, "1.0.0", Map.of());
            }

            @Override
            public NodeAction toAction(NodeRuntimeContext ctx) {
                return state -> {
                    Map<String, Object> vars = new HashMap<>();
                    state.data().forEach(vars::put);
                    return execute(vars);
                };
            }
        };
    }
}
