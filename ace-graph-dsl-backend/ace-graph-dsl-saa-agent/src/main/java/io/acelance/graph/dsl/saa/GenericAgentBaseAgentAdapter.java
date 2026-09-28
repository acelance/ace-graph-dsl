package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.acelance.graph.dsl.agent.GenericAgentNode;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
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
 * Q2：将 ACE {@link GraphBoundAgentNode} 包装为 SAA 官方 {@link BaseAgent}，
 * 供 {@code SequentialAgent.subAgents(...)} 挂载。
 *
 * <p>内部仍走 GenericAgent 执行路径（toAction / execute），不复制 Prompt/MCP 逻辑。</p>
 */
public final class GenericAgentBaseAgentAdapter extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentBaseAgentAdapter.class);

    private final GraphBoundAgentNode delegate;
    private final String instructionTemplate;
    private final String bindingOutputKey;
    private final String parentGraphId;
    private final String parentNodeId;

    /**
     * @param name                子 Agent 名（Flow 内节点 id）
     * @param description         描述
     * @param bindingOutputKey    绑定层 outputKey（写回 Flow state）
     * @param instructionTemplate instruction 模板
     * @param delegate            已 withGraphId 的 GenericAgent 节点
     * @param parentGraphId       父图 ID（日志）
     * @param parentNodeId        父高阶节点 ID（日志）
     */
    public GenericAgentBaseAgentAdapter(String name,
                                        String description,
                                        String bindingOutputKey,
                                        String instructionTemplate,
                                        GraphBoundAgentNode delegate,
                                        String parentGraphId,
                                        String parentNodeId) {
        super(name, description, true, false, bindingOutputKey, new ReplaceStrategy());
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.instructionTemplate = instructionTemplate;
        this.bindingOutputKey = Objects.requireNonNull(bindingOutputKey, "bindingOutputKey");
        this.parentGraphId = parentGraphId;
        this.parentNodeId = parentNodeId;
    }

    @Override
    public Node asNode(boolean includeContents, boolean returnReasoningContents) {
        NodeActionWithConfig action = (state, config) -> applyDelegate(state);
        return new Node(name(), compileConfig -> AsyncNodeActionWithConfig.node_async(action));
    }

    @Override
    protected StateGraph initGraph() throws GraphStateException {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put(bindingOutputKey, new ReplaceStrategy());
        StateGraph graph = new StateGraph(() -> strategies);
        graph.addNode(name(), node_async(state -> applyDelegate(state)));
        graph.addEdge(START, name());
        graph.addEdge(name(), END);
        return graph;
    }

    private Map<String, Object> applyDelegate(OverAllState state) throws Exception {
        long start = System.nanoTime();
        log.info("SAA 子 Agent 开始, graphId={}, parentNodeId={}, subName={}, outputKey={}",
                parentGraphId, parentNodeId, name(), bindingOutputKey);
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            if (state != null && state.data() != null) {
                variables.putAll(state.data());
            }
            String rendered = InstructionTemplate.render(instructionTemplate, state);
            if (StringUtils.hasText(rendered)) {
                injectInstruction(variables, rendered);
            }

            Map<String, Object> rawResult;
            if (delegate instanceof GenericAgentNode gan) {
                rawResult = gan.execute(
                        variables,
                        readString(state, ModelOverrideSpec.ACE_RUN_ID_KEY),
                        readOverrides(state),
                        readString(state, LlmRequestContext.ACE_AGENT_CODE_KEY),
                        state);
            } else {
                rawResult = delegate.execute(variables);
            }

            Object value = extractOutput(rawResult);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(bindingOutputKey, value);

            long costMs = (System.nanoTime() - start) / 1_000_000L;
            SaaSubStepCollector.record(name(), bindingOutputKey, "OK", costMs,
                    SaaSubStepCollector.preview(value, 120), null);
            log.info("SAA 子 Agent 成功, graphId={}, parentNodeId={}, subName={}, costMs={}, outputKey={}",
                    parentGraphId, parentNodeId, name(), costMs, bindingOutputKey);
            return out;
        } catch (Exception ex) {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            SaaSubStepCollector.record(name(), bindingOutputKey, "FAILED", costMs, null, ex.toString());
            log.error("SAA 子 Agent 失败, graphId={}, parentNodeId={}, subName={}, costMs={}, err={}",
                    parentGraphId, parentNodeId, name(), costMs, ex.toString(), ex);
            throw ex;
        }
    }

    private void injectInstruction(Map<String, Object> variables, String rendered) {
        GenericAgentSpec spec = delegate.agentSpec();
        List<String> inputKeys = spec != null ? spec.inputKeyList() : List.of();
        if (inputKeys.isEmpty()) {
            variables.put("user_query", rendered);
            variables.put("input", rendered);
        } else {
            for (String key : inputKeys) {
                variables.put(key, rendered);
            }
        }
    }

    private Object extractOutput(Map<String, Object> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            return null;
        }
        GenericAgentSpec spec = delegate.agentSpec();
        String agentKey = spec != null ? spec.effectiveOutputKey() : GenericAgentSpec.DEFAULT_OUTPUT_KEY;
        Object value = rawResult.get(agentKey);
        if (value == null && rawResult.size() == 1) {
            value = rawResult.values().iterator().next();
        }
        if (value instanceof org.springframework.ai.chat.messages.AssistantMessage msg) {
            return msg.getText();
        }
        return value;
    }

    private static String readString(OverAllState state, String key) {
        if (state == null) {
            return null;
        }
        try {
            Object v = state.value(key).orElse(null);
            return v instanceof String s && !s.isBlank() ? s : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ModelOverrideSpec readOverrides(OverAllState state) {
        if (state == null) {
            return null;
        }
        try {
            Object v = state.value(ModelOverrideSpec.ACE_MODEL_OVERRIDES_KEY).orElse(null);
            return v instanceof ModelOverrideSpec spec ? spec : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
