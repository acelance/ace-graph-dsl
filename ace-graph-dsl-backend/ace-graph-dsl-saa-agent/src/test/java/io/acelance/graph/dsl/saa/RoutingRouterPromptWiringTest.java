package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
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
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * ROUTING：saaSpec 路由器提示词接到 {@link LlmRoutingAgent}。
 */
class RoutingRouterPromptWiringTest {

    private static final Logger log = LoggerFactory.getLogger(RoutingRouterPromptWiringTest.class);

    @Test
    void factoryWiresSystemPromptInstructionAndFallback() throws Exception {
        GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
                stubAgent("sql-gen", "agent_result"),
                stubAgent("chat-helper", "agent_result")));
        ChatModel router = new MultiPatternSaaWorkflowFactoryIntegrationTest.StubRoutingChatModel("sql_generator");
        SaaWorkflowNodeFactoryImpl factory = new SaaWorkflowNodeFactoryImpl(
                List.of(new GenericAgentSubAgentResolver(registry)),
                (key, agentCode) -> router,
                router);

        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "ROUTING", "user_query", "route_out", "BIZ",
                "models:router", null, null, null, null,
                List.of(
                        new SaaSubAgentRef("sql_generator", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:sql-gen", "{user_query}", "sql_out"),
                        new SaaSubAgentRef("chat_helper", SaaSubAgentRefs.IMPL_GENERIC_AGENT,
                                "generic:chat-helper", "{user_query}", "chat_out")
                ),
                false, false, null, null,
                "路由规则：SQL 类问题选 sql_generator",
                "只输出 agent 名",
                "sql_generator",
                null, null);

        NodeAction action = factory.create("g-route", "route_node", spec, NodeRuntimeContext.empty(null));
        LlmRoutingAgent agent = extractFlowAgent(action);
        assertEquals("路由规则：SQL 类问题选 sql_generator", agent.getSystemPrompt());
        assertEquals("只输出 agent 名", agent.getInstruction());
        assertEquals("sql_generator", agent.getFallbackAgent());
        log.info("ROUTING 提示词接线断言通过");
    }

    private static LlmRoutingAgent extractFlowAgent(NodeAction action) throws Exception {
        Field f = FlowAgentNodeAction.class.getDeclaredField("flowAgent");
        f.setAccessible(true);
        Object flow = f.get(action);
        assertInstanceOf(LlmRoutingAgent.class, flow);
        return (LlmRoutingAgent) flow;
    }

    private static GraphBoundAgentNode stubAgent(String nodeId, String outputKey) {
        GenericAgentSpec agentSpec = new GenericAgentSpec(
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
                return Map.of(outputKey, "ok");
            }

            @Override
            public GenericAgentSpec agentSpec() {
                return agentSpec;
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
