package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 方式 A：将 {@link SequentialAgent} 包成 ACE {@link NodeAction}。
 *
 * <p>进入时按 inputKeys 从 {@link OverAllState} 取值组装 invoke 入参；
 * 退出时把约定键写回父 outputKey（及可选的透传子中间键）。</p>
 *
 * <p>不修改 SSE 协议（Q6）；关键路径打日志便于排障。</p>
 */
public final class SequentialFlowAgentNodeAction implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SequentialFlowAgentNodeAction.class);

    private final String graphId;
    private final String nodeId;
    private final SequentialAgent sequentialAgent;
    private final List<String> inputKeys;
    private final String parentOutputKey;
    /** 需要从 FlowAgent 结果透传到父 state 的键（含子 outputKey） */
    private final String[] resultKeysToCopy;

    /**
     * 兼容 M0 Spike：单 inputKey。
     */
    public SequentialFlowAgentNodeAction(String graphId,
                                         String nodeId,
                                         SequentialAgent sequentialAgent,
                                         String inputKey,
                                         String parentOutputKey,
                                         String... resultKeysToCopy) {
        this(graphId, nodeId, sequentialAgent,
                inputKey == null ? List.of() : List.of(inputKey),
                parentOutputKey, resultKeysToCopy);
    }

    /**
     * @param graphId           图 ID（日志）
     * @param nodeId            节点 ID（日志）
     * @param sequentialAgent   已组装的 SequentialAgent
     * @param inputKeys         从父 state 读取的输入键（至少一个）
     * @param parentOutputKey   写回父 state 的主输出键；可为 null 表示不写聚合键
     * @param resultKeysToCopy  从子结果 OverAllState 复制到返回 Map 的键
     */
    public SequentialFlowAgentNodeAction(String graphId,
                                         String nodeId,
                                         SequentialAgent sequentialAgent,
                                         List<String> inputKeys,
                                         String parentOutputKey,
                                         String... resultKeysToCopy) {
        this.graphId = Objects.requireNonNull(graphId, "graphId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sequentialAgent = Objects.requireNonNull(sequentialAgent, "sequentialAgent");
        this.inputKeys = inputKeys == null ? List.of() : List.copyOf(inputKeys);
        this.parentOutputKey = parentOutputKey;
        this.resultKeysToCopy = resultKeysToCopy == null ? new String[0] : resultKeysToCopy;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        long start = System.nanoTime();
        log.info("SAA Sequential 节点开始执行, graphId={}, nodeId={}, inputKeys={}, pattern=SEQUENTIAL",
                graphId, nodeId, inputKeys);
        try {
            Map<String, Object> seed = buildSeedInput(state);

            var resultOpt = sequentialAgent.invoke(seed);
            if (resultOpt.isEmpty()) {
                throw new IllegalStateException("SAA SequentialAgent.invoke 返回空, graphId=" + graphId
                        + ", nodeId=" + nodeId);
            }
            OverAllState flowState = resultOpt.get();

            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : resultKeysToCopy) {
                flowState.value(key).ifPresent(v -> out.put(key, unwrapMessageText(v)));
            }
            if (StringUtils.hasText(parentOutputKey)) {
                Object parentVal = resultKeysToCopy.length > 0
                        ? out.getOrDefault(resultKeysToCopy[resultKeysToCopy.length - 1], out)
                        : out;
                out.put(parentOutputKey, parentVal);
            }

            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.info("SAA Sequential 节点执行成功, graphId={}, nodeId={}, costMs={}, keys={}",
                    graphId, nodeId, costMs, out.keySet());
            return out;
        } catch (Exception ex) {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.error("SAA Sequential 节点执行失败, graphId={}, nodeId={}, costMs={}, err={}",
                    graphId, nodeId, costMs, ex.toString(), ex);
            throw ex;
        }
    }

    private Map<String, Object> buildSeedInput(OverAllState state) {
        if (inputKeys.isEmpty()) {
            throw new IllegalStateException("SAA Sequential 缺少 inputKeys, graphId=" + graphId
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
            throw new IllegalStateException("SAA Sequential 缺少输入, graphId=" + graphId
                    + ", nodeId=" + nodeId + ", inputKeys=" + inputKeys + ", missing=" + missing);
        }
        // Framework 习惯：额外提供 input，同时保留业务 inputKeys（如 user_query）供 instruction 占位
        if (!seed.containsKey("input")) {
            seed.put("input", seed.values().iterator().next());
        }
        return seed;
    }

    /** AssistantMessage 等类型提取可见文本，便于写回 OverAllState */
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
