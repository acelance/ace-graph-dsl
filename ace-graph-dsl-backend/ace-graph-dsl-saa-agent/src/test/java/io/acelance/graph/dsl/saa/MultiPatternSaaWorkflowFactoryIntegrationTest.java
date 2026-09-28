package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 集成：Factory 组装 PARALLEL / ROUTING / LOOP，经方式 A 写回 OverAllState。
 */
class MultiPatternSaaWorkflowFactoryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MultiPatternSaaWorkflowFactoryIntegrationTest.class);

    @Test
    void parallelMergesTwoSubAgentOutputs() throws Exception {
        GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
                stubAgent("alpha", "agent_result", vars -> "A:" + vars.get("user_query")),
                stubAgent("beta", "agent_result", vars -> "B:" + vars.get("user_query"))
        ));
        SaaWorkflowNodeFactoryImpl factory = new SaaWorkflowNodeFactoryImpl(
                List.of(new GenericAgentSubAgentResolver(registry)));

        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "PARALLEL", "user_query", "merged", "BIZ",
                null, null, null, null, null,
                List.of(
                        new SaaSubAgentRef("alpha", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:alpha", "{user_query}", "out_a"),
                        new SaaSubAgentRef("beta", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:beta", "{user_query}", "out_b")
                ));

        OverAllState result = invoke("m2-parallel", "par_node", factory, spec,
                Map.of("user_query", "hello"),
                "user_query", "out_a", "out_b", "merged");

        Object a = result.data().get("out_a");
        Object b = result.data().get("out_b");
        Object merged = result.data().get("merged");
        log.info("PARALLEL 结果: out_a={}, out_b={}, merged={}", a, b, merged);
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(String.valueOf(a).contains("hello"));
        assertTrue(String.valueOf(b).contains("hello"));
        assertNotNull(merged);
        log.info("PARALLEL 集成通过");
    }

    @Test
    void routingSelectsNamedSubAgentViaStubChatModel() throws Exception {
        GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
                stubAgent("sql-gen", "agent_result", vars -> "SQL_OK"),
                stubAgent("chat-helper", "agent_result", vars -> "CHAT_OK")
        ));
        // 路由器固定选 sql_generator
        ChatModel router = new StubRoutingChatModel("sql_generator");
        SaaWorkflowNodeFactoryImpl factory = new SaaWorkflowNodeFactoryImpl(
                List.of(new GenericAgentSubAgentResolver(registry)),
                key -> router,
                router);

        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "ROUTING", "user_query", "route_out", "BIZ",
                "models:router", null, null, null, null,
                List.of(
                        new SaaSubAgentRef("sql_generator", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:sql-gen", "{user_query}", "sql_out"),
                        new SaaSubAgentRef("chat_helper", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:chat-helper", "{user_query}", "chat_out")
                ));

        OverAllState result = invoke("m2-routing", "route_node", factory, spec,
                Map.of("user_query", "生成一条 SQL"),
                "user_query", "sql_out", "chat_out", "route_out");

        Object sqlOut = result.data().get("sql_out");
        Object parent = result.data().get("route_out");
        log.info("ROUTING 结果: sql_out={}, route_out={}", sqlOut, parent);
        assertEquals("SQL_OK", String.valueOf(sqlOut).trim());
        assertNotNull(parent);
        log.info("ROUTING 集成通过");
    }

    @Test
    void loopRunsUntilExitConditionMet() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
                stubAgent("scorer", "agent_result", vars -> {
                    int n = calls.incrementAndGet();
                    // 第 1 次 0.3，第 2 次 0.9 → GT 0.8 退出
                    return n == 1 ? "0.3" : "0.9";
                })
        ));
        SaaWorkflowNodeFactoryImpl factory = new SaaWorkflowNodeFactoryImpl(
                List.of(new GenericAgentSubAgentResolver(registry)));

        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "LOOP", "user_query", "final_score", "BIZ",
                null, 5, "score", "GT", "0.8",
                List.of(
                        new SaaSubAgentRef("scorer", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:scorer", "{user_query}", "score")
                ));

        OverAllState result = invoke("m2-loop", "loop_node", factory, spec,
                Map.of("user_query", "打分"),
                "user_query", "score", "final_score");

        Object score = result.data().get("score");
        Object parent = result.data().get("final_score");
        log.info("LOOP 结果: score={}, final_score={}, calls={}", score, parent, calls.get());
        assertTrue(calls.get() >= 2, "应至少迭代 2 次");
        assertEquals("0.9", String.valueOf(score).trim());
        assertEquals("0.9", String.valueOf(parent).trim());
        log.info("LOOP 集成通过");
    }

    private static OverAllState invoke(String graphId,
                                       String nodeId,
                                       SaaWorkflowNodeFactoryImpl factory,
                                       SaaWorkflowSpec spec,
                                       Map<String, Object> input,
                                       String... strategyKeys) throws Exception {
        NodeAction action = factory.create(graphId, nodeId, spec, NodeRuntimeContext.empty(null));
        log.info("M2 Factory 已创建 NodeAction, graphId={}, nodeId={}, pattern={}",
                graphId, nodeId, spec.pattern());

        Map<String, KeyStrategy> strategies = new HashMap<>();
        for (String key : strategyKeys) {
            strategies.put(key, new ReplaceStrategy());
        }
        StateGraph stateGraph = new StateGraph(() -> strategies);
        stateGraph.addNode(nodeId, node_async(action));
        stateGraph.addEdge(START, nodeId);
        stateGraph.addEdge(nodeId, END);

        CompiledGraph compiled = stateGraph.compile();
        return compiled.invoke(input, RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));
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
                false, List.of(), MemoryMode.NONE, false,
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

    /**
     * ROUTING 桩模型：返回指定 agent 名的 JSON（对齐 RoutingNode BeanOutputConverter）。
     */
    static final class StubRoutingChatModel implements ChatModel {
        private final String agentName;

        StubRoutingChatModel(String agentName) {
            this.agentName = agentName;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String json = "{\"agents\":[{\"agent\":\"" + agentName
                    + "\",\"query\":\"routed\"}]}";
            log.info("StubRoutingChatModel.call → {}", json);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
