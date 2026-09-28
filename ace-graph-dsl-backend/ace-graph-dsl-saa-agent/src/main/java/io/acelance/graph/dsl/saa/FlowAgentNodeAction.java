package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.Agent;
import io.acelance.graph.dsl.definition.SaaWorkflowKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 方式 A：将任意 SAA {@link Agent}（Sequential/Parallel/Routing/Loop）包成 ACE {@link NodeAction}。
 *
 * <p>不修改 SSE 协议（Q6）；子步骤写入 {@link SaaWorkflowKeys#SUB_STEPS_KEY} 供试运行 UI。</p>
 */
public final class FlowAgentNodeAction implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(FlowAgentNodeAction.class);

    private final String graphId;
    private final String nodeId;
    private final String pattern;
    private final Agent flowAgent;
    private final List<String> inputKeys;
    private final String parentOutputKey;
    private final String[] resultKeysToCopy;
    /** 与 resultKeys 对齐的子 Agent 名（可空） */
    private final String[] subAgentNames;

    /**
     * @param graphId          图 ID
     * @param nodeId           节点 ID
     * @param pattern          模式名（日志）
     * @param flowAgent        FlowAgent / Agent
     * @param inputKeys        父 state 输入键
     * @param parentOutputKey  父输出键
     * @param resultKeysToCopy 透传子键
     */
    public FlowAgentNodeAction(String graphId,
                               String nodeId,
                               String pattern,
                               Agent flowAgent,
                               List<String> inputKeys,
                               String parentOutputKey,
                               String... resultKeysToCopy) {
        this(graphId, nodeId, pattern, flowAgent, inputKeys, parentOutputKey, resultKeysToCopy, null);
    }

    /**
     * @param subAgentNames 与 {@code resultKeysToCopy} 对齐的子名（可空）
     */
    public FlowAgentNodeAction(String graphId,
                               String nodeId,
                               String pattern,
                               Agent flowAgent,
                               List<String> inputKeys,
                               String parentOutputKey,
                               String[] resultKeysToCopy,
                               String[] subAgentNames) {
        this.graphId = Objects.requireNonNull(graphId, "graphId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.pattern = pattern == null ? "UNKNOWN" : pattern;
        this.flowAgent = Objects.requireNonNull(flowAgent, "flowAgent");
        this.inputKeys = inputKeys == null ? List.of() : List.copyOf(inputKeys);
        this.parentOutputKey = parentOutputKey;
        this.resultKeysToCopy = resultKeysToCopy == null ? new String[0] : resultKeysToCopy;
        this.subAgentNames = subAgentNames == null ? new String[0] : subAgentNames;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        long start = System.nanoTime();
        SaaSubStepCollector.begin();
        log.info("SAA 高阶节点开始执行, graphId={}, nodeId={}, pattern={}, inputKeys={}",
                graphId, nodeId, pattern, inputKeys);
        try {
            Map<String, Object> seed = buildSeedInput(state);
            var resultOpt = flowAgent.invoke(seed);
            if (resultOpt.isEmpty()) {
                throw new IllegalStateException("SAA Agent.invoke 返回空, graphId=" + graphId
                        + ", nodeId=" + nodeId + ", pattern=" + pattern);
            }
            OverAllState flowState = resultOpt.get();

            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : resultKeysToCopy) {
                flowState.value(key).ifPresent(v -> out.put(key, unwrapMessageText(v)));
            }
            if (StringUtils.hasText(parentOutputKey)) {
                Object parentVal = resolveParentOutput(out, flowState);
                if (parentVal != null) {
                    // 禁止把 out 自身写入（否则 Jackson 序列化会 StackOverflow）
                    out.put(parentOutputKey, parentVal);
                } else {
                    log.warn("SAA 父 outputKey 无可用值, graphId={}, nodeId={}, parentOutputKey={}, keys={}",
                            graphId, nodeId, parentOutputKey, out.keySet());
                }
            }

            long costMs = (System.nanoTime() - start) / 1_000_000L;
            List<Map<String, Object>> steps = mergeSubSteps(out);
            if (!steps.isEmpty()) {
                out.put(SaaWorkflowKeys.SUB_STEPS_KEY, steps);
                out.put(SaaWorkflowKeys.SUB_STEPS_META_KEY, Map.of(
                        "pattern", pattern,
                        "nodeId", nodeId,
                        "costMs", costMs,
                        "subStepCount", steps.size()));
            }
            log.info("SAA 高阶节点执行成功, graphId={}, nodeId={}, pattern={}, costMs={}, keys={}, subSteps={}",
                    graphId, nodeId, pattern, costMs, out.keySet(), steps.size());
            return out;
        } catch (Exception ex) {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.error("SAA 高阶节点执行失败, graphId={}, nodeId={}, pattern={}, costMs={}, err={}",
                    graphId, nodeId, pattern, costMs, ex.toString(), ex);
            throw ex;
        } finally {
            SaaSubStepCollector.clear();
        }
    }

    /**
     * 合并 ThreadLocal 明细与 resultKeys 兜底条目（AgentScope 等无适配器埋点时仍有摘要）。
     */
    private List<Map<String, Object>> mergeSubSteps(Map<String, Object> out) {
        List<Map<String, Object>> collected = SaaSubStepCollector.endAndSnapshot();
        if (!collected.isEmpty()) {
            return collected;
        }
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (int i = 0; i < resultKeysToCopy.length; i++) {
            String key = resultKeysToCopy[i];
            String name = i < subAgentNames.length ? subAgentNames[i] : key;
            boolean ok = out.containsKey(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name == null ? key : name);
            row.put("outputKey", key);
            row.put("status", ok ? "OK" : "SKIPPED");
            row.put("costMs", 0L);
            if (ok) {
                row.put("preview", SaaSubStepCollector.preview(out.get(key), 120));
            }
            fallback.add(row);
        }
        return List.copyOf(fallback);
    }

    private Map<String, Object> buildSeedInput(OverAllState state) {
        if (inputKeys.isEmpty()) {
            throw new IllegalStateException("SAA 节点缺少 inputKeys, graphId=" + graphId
                    + ", nodeId=" + nodeId);
        }
        Map<String, Object> seed = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String key : inputKeys) {
            Object raw = state.value(key).orElse(null);
            if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
                missing.add(key);
            } else {
                seed.put(key, raw);
            }
        }
        if (seed.isEmpty()) {
            throw new IllegalStateException("SAA 节点缺少输入, graphId=" + graphId
                    + ", nodeId=" + nodeId + ", inputKeys=" + inputKeys + ", missing=" + missing);
        }
        if (!seed.containsKey("input")) {
            seed.put("input", seed.values().iterator().next());
        }
        return seed;
    }

    /**
     * 解析父 outputKey 取值：优先倒序命中的子键，其次 flowState 上的 merge 键，再退回任一已有子值。
     * 绝不返回 {@code out} 自身，避免自引用导致序列化栈溢出。
     */
    private Object resolveParentOutput(Map<String, Object> out, OverAllState flowState) {
        if (resultKeysToCopy.length > 0) {
            for (int i = resultKeysToCopy.length - 1; i >= 0; i--) {
                String key = resultKeysToCopy[i];
                if (out.containsKey(key)) {
                    return out.get(key);
                }
            }
        }
        Object merged = flowState.value(parentOutputKey).map(FlowAgentNodeAction::unwrapMessageText)
                .orElse(null);
        if (merged != null && merged != out) {
            return merged;
        }
        if (!out.isEmpty()) {
            return out.values().iterator().next();
        }
        return null;
    }

    private static Object unwrapMessageText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof org.springframework.ai.chat.messages.AssistantMessage msg) {
            return msg.getText();
        }
        return value;
    }
}
