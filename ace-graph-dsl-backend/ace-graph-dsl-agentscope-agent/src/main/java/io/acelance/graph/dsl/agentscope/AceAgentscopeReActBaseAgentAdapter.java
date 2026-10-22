package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 路径 B：将 AS 2.x {@link ReActAgent} 包装为 SAA {@link BaseAgent}，供 FlowAgent.subAgents 挂载。
 *
 * <p>MVP：instruction 渲染 → {@code ReActAgent.call}（同步 block）→ 写 {@code outputKey}。
 * 内层暂非流式（缺口 G-STREAM-INNER）；外层业务 SSE 不受影响。</p>
 */
public final class AceAgentscopeReActBaseAgentAdapter extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(AceAgentscopeReActBaseAgentAdapter.class);

    private final ReActAgent reActAgent;
    private final String instructionTemplate;
    private final String bindingOutputKey;
    private final String parentGraphId;
    private final String parentNodeId;

    /**
     * @param name                子 Agent 名（Flow 内节点 id）
     * @param description         描述
     * @param bindingOutputKey    写回 Flow state 的键
     * @param instructionTemplate instruction 模板（支持 {@code {stateKey}}）
     * @param reActAgent          已构建的 AS 2.x ReActAgent
     * @param parentGraphId       父图 ID（日志）
     * @param parentNodeId        父高阶节点 ID（日志）
     */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              ReActAgent reActAgent,
                                              String parentGraphId,
                                              String parentNodeId) {
        super(name, description, true, false, bindingOutputKey, new ReplaceStrategy());
        this.reActAgent = Objects.requireNonNull(reActAgent, "reActAgent");
        this.instructionTemplate = instructionTemplate;
        this.bindingOutputKey = Objects.requireNonNull(bindingOutputKey, "bindingOutputKey");
        this.parentGraphId = parentGraphId;
        this.parentNodeId = parentNodeId;
    }

    @Override
    public Node asNode(boolean includeContents, boolean returnReasoningContents) {
        NodeActionWithConfig action = (state, config) -> invokeReAct(state);
        return new Node(name(), compileConfig -> AsyncNodeActionWithConfig.node_async(action));
    }

    @Override
    protected StateGraph initGraph() throws GraphStateException {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put(bindingOutputKey, new ReplaceStrategy());
        StateGraph graph = new StateGraph(() -> strategies);
        graph.addNode(name(), node_async(this::invokeReAct));
        graph.addEdge(START, name());
        graph.addEdge(name(), END);
        return graph;
    }

    /**
     * 渲染 instruction、调用 ReActAgent、写回 outputKey。
     */
    private Map<String, Object> invokeReAct(OverAllState state) {
        long start = System.nanoTime();
        log.info("AGENTSCOPE 子 Agent 开始, bridge={}, graphId={}, parentNodeId={}, subName={}, outputKey={}",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(), bindingOutputKey);
        try {
            String rendered = AgentscopeInstructionTemplate.render(instructionTemplate, state);
            if (!StringUtils.hasText(rendered)) {
                rendered = readFallbackInput(state);
            }
            log.info("AGENTSCOPE 子 Agent 输入就绪, graphId={}, parentNodeId={}, subName={}, inputLen={}",
                    parentGraphId, parentNodeId, name(), rendered != null ? rendered.length() : 0);

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(rendered != null ? rendered : "")
                    .build();
            // MVP：内层同步 block（G-STREAM-INNER 后续补齐流式）
            Msg reply = reActAgent.call(List.of(userMsg)).block();
            String text = reply != null ? reply.getTextContent() : null;
            if (text == null) {
                text = "";
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put(bindingOutputKey, text);
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.info("AGENTSCOPE 子 Agent 成功, bridge={}, graphId={}, parentNodeId={}, subName={}, outputKey={}, "
                            + "costMs={}, replyLen={}",
                    AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(),
                    bindingOutputKey, costMs, text.length());
            return out;
        } catch (Exception ex) {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.error("AGENTSCOPE 子 Agent 失败, bridge={}, graphId={}, parentNodeId={}, subName={}, outputKey={}, "
                            + "costMs={}, err={}",
                    AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(),
                    bindingOutputKey, costMs, ex.toString(), ex);
            throw ex instanceof RuntimeException re ? re : new IllegalStateException(
                    "AGENTSCOPE 子 Agent 执行失败: " + name() + ", graphId=" + parentGraphId, ex);
        }
    }

    /**
     * instruction 为空时兜底读常见输入键。
     */
    private static String readFallbackInput(OverAllState state) {
        if (state == null) {
            return "";
        }
        for (String key : List.of("user_query", "input", "query")) {
            Object v = state.value(key).orElse(null);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return "";
    }
}
