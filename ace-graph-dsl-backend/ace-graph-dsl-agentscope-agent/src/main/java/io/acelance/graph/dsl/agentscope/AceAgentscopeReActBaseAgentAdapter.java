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
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
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
 * 路径 B / G-MCP：将 AS 2.x {@link ReActAgent} 包装为 SAA {@link BaseAgent}。
 *
 * <p>执行时按 Spec 装配 ACE 工具 → Toolkit，再 {@code ReActAgent.call}（同步 block）写 {@code outputKey}。
 * 内层暂非流式（G-STREAM-INNER）；子记忆 NONE（Q5）。</p>
 */
public final class AceAgentscopeReActBaseAgentAdapter extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(AceAgentscopeReActBaseAgentAdapter.class);

    private final String instructionTemplate;
    private final String bindingOutputKey;
    private final String parentGraphId;
    private final String parentNodeId;
    private final String agentId;
    private final String baseSysPrompt;
    private final Model model;
    private final GenericAgentSpec agentSpec;
    private final AgentScopeToolAssembly toolAssembly;
    /** 无工具单测路径：预构建 Agent（不经 Assembly） */
    private final ReActAgent fixedReActAgent;

    /**
     * 生产路径（G-MCP）：每次 invoke 按 state 装配工具并构建 {@link ReActAgent}。
     */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              Model model,
                                              String baseSysPrompt,
                                              GenericAgentSpec agentSpec,
                                              AgentScopeToolAssembly toolAssembly,
                                              String parentGraphId,
                                              String parentNodeId,
                                              String agentId) {
        super(name, description, true, false, bindingOutputKey, new ReplaceStrategy());
        this.model = Objects.requireNonNull(model, "model");
        this.baseSysPrompt = StringUtils.hasText(baseSysPrompt) ? baseSysPrompt.trim() : "You are a helpful assistant.";
        this.agentSpec = Objects.requireNonNull(agentSpec, "agentSpec");
        this.toolAssembly = toolAssembly != null ? toolAssembly : AgentScopeToolAssembly.noop();
        this.fixedReActAgent = null;
        this.instructionTemplate = instructionTemplate;
        this.bindingOutputKey = Objects.requireNonNull(bindingOutputKey, "bindingOutputKey");
        this.parentGraphId = parentGraphId;
        this.parentNodeId = parentNodeId;
        this.agentId = agentId;
    }

    /**
     * 无工具单测 / 兼容路径：使用预构建 {@link ReActAgent}（不装配 Toolkit）。
     */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              ReActAgent reActAgent,
                                              String parentGraphId,
                                              String parentNodeId) {
        super(name, description, true, false, bindingOutputKey, new ReplaceStrategy());
        this.fixedReActAgent = Objects.requireNonNull(reActAgent, "reActAgent");
        this.model = null;
        this.baseSysPrompt = null;
        this.agentSpec = null;
        this.toolAssembly = null;
        this.instructionTemplate = instructionTemplate;
        this.bindingOutputKey = Objects.requireNonNull(bindingOutputKey, "bindingOutputKey");
        this.parentGraphId = parentGraphId;
        this.parentNodeId = parentNodeId;
        this.agentId = null;
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
     * 渲染 instruction、（可选）装配工具、调用 ReActAgent、写回 outputKey。
     */
    private Map<String, Object> invokeReAct(OverAllState state) {
        long start = System.nanoTime();
        log.info("AGENTSCOPE 子 Agent 开始, bridge={}, graphId={}, parentNodeId={}, subName={}, agentId={}, outputKey={}",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(), agentId,
                bindingOutputKey);
        try {
            String rendered = AgentscopeInstructionTemplate.render(instructionTemplate, state);
            if (!StringUtils.hasText(rendered)) {
                rendered = readFallbackInput(state);
            }
            log.info("AGENTSCOPE 子 Agent 输入就绪, graphId={}, parentNodeId={}, subName={}, inputLen={}",
                    parentGraphId, parentNodeId, name(), rendered != null ? rendered.length() : 0);

            ReActAgent agent = resolveReActAgent(state);
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(rendered != null ? rendered : "")
                    .build();
            // 内层同步 block（G-STREAM-INNER 后续补齐流式）
            Msg reply = agent.call(List.of(userMsg)).block();
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
     * 固定 Agent，或按本次 state 装配 Toolkit 后构建。
     */
    private ReActAgent resolveReActAgent(OverAllState state) {
        if (fixedReActAgent != null) {
            return fixedReActAgent;
        }
        AgentScopeToolAssemblyResult assembled = toolAssembly.assemble(
                parentGraphId, parentNodeId, agentId, agentSpec, state);
        String sysPrompt = baseSysPrompt;
        if (StringUtils.hasText(assembled.sysPromptSuffix())) {
            sysPrompt = baseSysPrompt + assembled.sysPromptSuffix();
        }
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name())
                .description(description())
                .sysPrompt(sysPrompt)
                .model(model);
        if (assembled.hasTools()) {
            Toolkit toolkit = AceToolCallbackAgentscopeBridge.toToolkit(assembled);
            builder.toolkit(toolkit);
            log.info("AGENTSCOPE ReActAgent 已挂 Toolkit, graphId={}, subName={}, tools={}",
                    parentGraphId, name(), assembled.totalToolCount());
        } else {
            log.info("AGENTSCOPE ReActAgent 无工具（与 1.1.21 无工具路径一致）, graphId={}, subName={}",
                    parentGraphId, name());
        }
        return builder.build();
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
