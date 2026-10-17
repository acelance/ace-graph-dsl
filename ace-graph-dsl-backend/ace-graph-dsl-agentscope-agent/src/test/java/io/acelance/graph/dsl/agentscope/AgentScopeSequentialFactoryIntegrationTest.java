package io.acelance.graph.dsl.agentscope;

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
import io.acelance.graph.dsl.saa.GenericAgentSubAgentResolver;
import io.acelance.graph.dsl.saa.SaaWorkflowNodeFactoryImpl;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * M3：同一 SEQUENTIAL 样例切换 {@code impl=AGENTSCOPE}，主 outputKey 结构与 GENERIC 路径一致。
 */
class AgentScopeSequentialFactoryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSequentialFactoryIntegrationTest.class);

    private static final String GRAPH_ID = "m3-saa-agentscope-sequential";
    private static final String NODE_ID = "sql_quality";

    @Test
    void agentscopeImplChainsThroughSequentialFactory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentScopeModelFactory modelFactory = (graphId, nodeId, agentId, spec) ->
                new StubAgentScopeModel(agentId, calls);

        GraphNodeRegistry registry = new GraphNodeRegistry(List.of(
                stubRegistered("sql-gen", "SQL 生成器"),
                stubRegistered("sql-rater", "SQL 评分器")
        ));

        SaaWorkflowNodeFactoryImpl factory = new SaaWorkflowNodeFactoryImpl(List.of(
                new GenericAgentSubAgentResolver(registry),
                new AgentScopeSubAgentResolver(registry, modelFactory)
        ));

        SaaWorkflowSpec spec = new SaaWorkflowSpec(
                "SEQUENTIAL",
                "user_query",
                "sql_score",
                "BIZ",
                null, null, null, null, null,
                List.of(
                        new SaaSubAgentRef("sql_generator", SaaSubAgentRefs.IMPL_AGENTSCOPE,
                                "agentscope:sql-gen", "{user_query}", "sql"),
                        new SaaSubAgentRef("sql_rater", SaaSubAgentRefs.IMPL_AGENTSCOPE,
                                "agentscope:sql-rater", "给以下 SQL 打分：{sql}", "score")
                )
        );

        NodeAction action = factory.create(GRAPH_ID, NODE_ID, spec, NodeRuntimeContext.empty(null));
        log.info("M3 Factory 已创建 SEQUENTIAL(AGENTSCOPE) NodeAction");

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
        log.info("M3 集成结果: sql={}, score={}, sql_score={}, modelCalls={}",
                sql, score, parent, calls.get());

        assertNotNull(sql);
        assertTrue(String.valueOf(sql).contains("SELECT") || String.valueOf(sql).contains("sql-gen"),
                "sql 应来自 stub 模型");
        assertNotNull(score);
        assertEquals(String.valueOf(score).trim(), String.valueOf(parent).trim());
        assertTrue(calls.get() >= 2, "两个 AGENTSCOPE 子 Agent 应各至少调一次模型");
        log.info("M3 集成通过: AGENTSCOPE + Sequential + 方式 A 写回 OK");
    }

    private static GraphBoundAgentNode stubRegistered(String nodeId, String displayName) {
        GenericAgentSpec spec = new GenericAgentSpec(
                "http://127.0.0.1/stub", "sk-stub", false, "stub-model",
                "stub prompt for " + nodeId,
                "user_query", "agent_result", "BIZ", null,
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
                throw new UnsupportedOperationException("AGENTSCOPE 路径不应回落到 GenericAgent.execute");
            }

            @Override
            public GenericAgentSpec agentSpec() {
                return spec;
            }

            @Override
            public GraphNodeDescriptor descriptor() {
                return new GraphNodeDescriptor(
                        nodeId, displayName, GraphNodeDescriptor.CATEGORY_GENERIC_AGENT,
                        "stub", Set.of("user_query"), Set.of("agent_result"),
                        false, "1.0.0", Map.of());
            }

            @Override
            public NodeAction toAction(NodeRuntimeContext ctx) {
                return state -> Map.of();
            }
        };
    }

    /**
     * 确定性 stub：按 agentId 返回固定文本，验证 AgentScope 桥可跑通。
     */
    static final class StubAgentScopeModel implements Model {
        private final String agentId;
        private final AtomicInteger calls;

        StubAgentScopeModel(String agentId, AtomicInteger calls) {
            this.agentId = agentId;
            this.calls = calls;
        }

        @Override
        public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                         List<ToolSchema> tools,
                                         GenerateOptions options) {
            int n = calls.incrementAndGet();
            String text = "sql-rater".equals(agentId)
                    ? "0.9"
                    : "SELECT 1 /* agentscope:" + agentId + " */";
            log.info("StubAgentScopeModel.stream, agentId={}, call={}, replySnippet={}",
                    agentId, n, text.length() > 40 ? text.substring(0, 40) : text);
            ChatResponse resp = ChatResponse.builder()
                    .id("stub-" + n)
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .finishReason("stop")
                    .build();
            return Flux.just(resp);
        }

        @Override
        public String getModelName() {
            return "stub-" + agentId;
        }
    }
}
