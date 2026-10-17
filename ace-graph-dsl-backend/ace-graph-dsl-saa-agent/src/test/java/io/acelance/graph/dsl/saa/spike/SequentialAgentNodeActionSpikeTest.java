package io.acelance.graph.dsl.saa.spike;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.acelance.graph.dsl.saa.SequentialFlowAgentNodeAction;
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

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 Spike：验证「方式 A」——{@link SequentialAgent} 经 {@link SequentialFlowAgentNodeAction}
 * 挂进 ACE {@link StateGraph}，写回 {@link OverAllState}；第二子 Agent 能读第一子 outputKey。
 *
 * <p>子 Agent 使用官方 {@link ReactAgent}（Q2 优先官方接口）；不引入 AgentScope；不改 SSE。</p>
 */
class SequentialAgentNodeActionSpikeTest {

    private static final Logger log = LoggerFactory.getLogger(SequentialAgentNodeActionSpikeTest.class);

    private static final String GRAPH_ID = "spike-saa-sequential";
    private static final String NODE_ID = "sql_quality";

    @Test
    void sequentialAgentViaNodeActionWritesStateAndChainsOutputKeys() throws Exception {
        ChatModel chatModel = new StubSequentialChatModel();

        // Q2：官方 ReactAgent 作为 subAgent（非反射）
        ReactAgent generator = ReactAgent.builder()
                .name("sql_generator")
                .model(chatModel)
                .description("stub SQL generator")
                .instruction("根据需求生成 SQL：{input}")
                .outputKey("sql")
                .build();

        ReactAgent rater = ReactAgent.builder()
                .name("sql_rater")
                .model(chatModel)
                .description("stub SQL rater")
                .instruction("给以下 SQL 打分：{sql}")
                .outputKey("score")
                .build();

        SequentialAgent sequential = SequentialAgent.builder()
                .name("sql_quality_flow")
                .description("M0 spike sequential")
                .subAgents(List.of(generator, rater))
                .build();

        log.info("M0 Spike 已组装 SequentialAgent, subAgents=[sql_generator, sql_rater], mount=NodeAction(A)");

        // 方式 A：NodeAction 适配器挂进 ACE StateGraph
        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put("user_query", new ReplaceStrategy());
        strategies.put("sql", new ReplaceStrategy());
        strategies.put("score", new ReplaceStrategy());
        strategies.put("sql_score", new ReplaceStrategy());

        StateGraph stateGraph = new StateGraph(() -> strategies);
        stateGraph.addNode(NODE_ID, node_async(new SequentialFlowAgentNodeAction(
                GRAPH_ID,
                NODE_ID,
                sequential,
                "user_query",
                "sql_score",
                "sql", "score")));
        stateGraph.addEdge(START, NODE_ID);
        stateGraph.addEdge(NODE_ID, END);

        CompiledGraph compiled = stateGraph.compile();
        OverAllState result = compiled.invoke(Map.of("user_query", "按门店汇总本月销售额"),
                        RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));

        Object sql = result.data().get("sql");
        Object score = result.data().get("score");
        Object parent = result.data().get("sql_score");

        log.info("M0 Spike 结果: sql={}, score={}, sql_score={}", sql, score, parent);

        assertNotNull(sql, "第一子 Agent 应写入 sql");
        assertNotNull(score, "第二子 Agent 应写入 score（依赖 {sql}）");
        assertNotNull(parent, "父 outputKey sql_score 应有值");
        assertTrue(String.valueOf(sql).contains("SELECT"), "生成 SQL 应含 SELECT, actual=" + sql);
        assertEquals("0.9", String.valueOf(score).trim(), "评分应来自第二步 stub");
        assertEquals("0.9", String.valueOf(parent).trim(), "父输出取最后子键 score");

        log.info("M0 Spike 通过: 方式A + 官方 ReactAgent subAgent + state 链式透传 OK");
    }

    /**
     * 按 prompt 文本区分两步 stub：含「打分」则返回分数，否则返回假 SQL。
     * 用于验证第二步 instruction 中的 {sql} 已被 Framework 渲染进 prompt。
     */
    static final class StubSequentialChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            String text = prompt.getContents();
            log.info("StubChatModel.call, promptSnippet={}",
                    text == null ? "null" : text.substring(0, Math.min(120, text.length())));
            String reply;
            if (text != null && text.contains("打分")) {
                // 第二步：应能在 prompt 中看到第一步 SQL
                assertTrue(text.contains("SELECT"), "第二步 prompt 应包含第一步 SQL, prompt=" + text);
                reply = "0.9";
            } else {
                reply = "SELECT store_id, SUM(amount) FROM sales GROUP BY store_id";
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
