package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.Agent;
import io.acelance.graph.dsl.ai.memory.GraphNodeMemoryPersister;
import io.acelance.graph.dsl.definition.SaaWorkflowKeys;
import io.acelance.graph.dsl.llm.AceGraphAgentCodeHolder;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryWriteFlag;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 方式 A：将任意 SAA {@link Agent}（Sequential/Parallel/Routing/Loop）包成 ACE {@link NodeAction}。
 *
 * <p>不修改 SSE 协议（Q6）；子步骤写入 {@link SaaWorkflowKeys#SUB_STEPS_KEY} 供试运行 UI。</p>
 * <p>记忆：按 {@code memoryWrites} 在 invoke 前写 USER、成功后写 ASSISTANT MAIN（不经 ChatClient Advisor）。</p>
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
    /** 可组合写意图；空 = 不落盘 */
    private final Set<MemoryWriteFlag> memoryWrites;
    /** 业务侧落盘 SPI；可空（降级跳过） */
    private final GraphNodeMemoryPersister memoryPersister;

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
        this(graphId, nodeId, pattern, flowAgent, inputKeys, parentOutputKey, resultKeysToCopy, null,
                Set.of(), null);
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
        this(graphId, nodeId, pattern, flowAgent, inputKeys, parentOutputKey, resultKeysToCopy, subAgentNames,
                Set.of(), null);
    }

    /**
     * 完整构造：挂记忆写意图与 Persister。
     *
     * @param memoryWrites    可组合写意图（可空）
     * @param memoryPersister 落盘 SPI（可空；有写意图但无 Bean 时 WARN 跳过）
     */
    public FlowAgentNodeAction(String graphId,
                               String nodeId,
                               String pattern,
                               Agent flowAgent,
                               List<String> inputKeys,
                               String parentOutputKey,
                               String[] resultKeysToCopy,
                               String[] subAgentNames,
                               Set<MemoryWriteFlag> memoryWrites,
                               GraphNodeMemoryPersister memoryPersister) {
        this.graphId = Objects.requireNonNull(graphId, "graphId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.pattern = pattern == null ? "UNKNOWN" : pattern;
        this.flowAgent = Objects.requireNonNull(flowAgent, "flowAgent");
        this.inputKeys = inputKeys == null ? List.of() : List.copyOf(inputKeys);
        this.parentOutputKey = parentOutputKey;
        this.resultKeysToCopy = resultKeysToCopy == null ? new String[0] : resultKeysToCopy;
        this.subAgentNames = subAgentNames == null ? new String[0] : subAgentNames;
        this.memoryWrites = copyFlags(memoryWrites);
        this.memoryPersister = memoryPersister;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        long start = System.nanoTime();
        SaaSubStepCollector.begin();
        log.info("SAA 高阶节点开始执行, graphId={}, nodeId={}, pattern={}, inputKeys={}, memoryWrites={}",
                graphId, nodeId, pattern, inputKeys, memoryWrites);
        // ROUTING 延迟 ChatModel 跨 SAA 框架拿不到 OverAllState，用 Holder 透传入口 agentCode
        String agentCode = readAgentCode(state);
        AceGraphAgentCodeHolder.set(agentCode);
        try {
            // WRITE_USER：invoke 前落盘（与 Advisor before 对齐）
            maybePersistUser(state);

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

            // WRITE_ASSISTANT_MAIN_TEXT：invoke 成功后落盘；仅 THINKING 不 drain
            maybePersistAssistantMain(state, out);

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
            AceGraphAgentCodeHolder.clear();
            SaaSubStepCollector.clear();
        }
    }

    /** invoke 前：WRITE_USER → Persister。 */
    private void maybePersistUser(OverAllState state) {
        boolean writeUser = memoryWrites.contains(MemoryWriteFlag.WRITE_USER);
        if (!writeUser) {
            return;
        }
        if (memoryPersister == null) {
            log.warn("SAA memory persist 跳过 USER：未注册 GraphNodeMemoryPersister, graphId={}, nodeId={}, flags={}",
                    graphId, nodeId, memoryWrites);
            return;
        }
        String conversationId = readConversationId(state);
        if (!StringUtils.hasText(conversationId)) {
            log.warn("SAA memory persist 跳过 USER：无 conversationId, graphId={}, nodeId={}", graphId, nodeId);
            return;
        }
        LlmRequestContext ctx = buildPersistContext(state, conversationId);
        Map<String, Object> variables = snapshotInputVariables(state);
        String llmFallback = firstNonBlankText(variables);
        try {
            log.info("SAA memory persist: nodeId={}, flags={}, writeUser=true, writeAssistant=false, "
                            + "contentChars=n/a, thinkingChars=n/a, conversationId={}",
                    nodeId, memoryWrites, conversationId);
            memoryPersister.persistUser(new GraphNodeMemoryPersister.PersistUserRequest(
                    ctx, nodeId, variables, llmFallback));
        } catch (RuntimeException ex) {
            log.error("SAA memory persist USER 失败, graphId={}, nodeId={}, conversationId={}, err={}",
                    graphId, nodeId, conversationId, ex.toString(), ex);
        }
    }

    /** invoke 后：WRITE_ASSISTANT_MAIN_TEXT → Persister（仅 THINKING 不调用）。 */
    private void maybePersistAssistantMain(OverAllState state, Map<String, Object> out) {
        boolean writeMain = memoryWrites.contains(MemoryWriteFlag.WRITE_ASSISTANT_MAIN_TEXT);
        boolean thinkingOnly = memoryWrites.contains(MemoryWriteFlag.WRITE_ASSISTANT_THINKING) && !writeMain;
        if (thinkingOnly) {
            log.info("SAA memory persist: nodeId={}, flags={}, writeUser=false, writeAssistant=false "
                            + "(仅 THINKING，不 drain), conversationId={}",
                    nodeId, memoryWrites, readConversationId(state));
            return;
        }
        if (!writeMain) {
            return;
        }
        if (memoryPersister == null) {
            log.warn("SAA memory persist 跳过 ASSISTANT：未注册 GraphNodeMemoryPersister, graphId={}, nodeId={}, flags={}",
                    graphId, nodeId, memoryWrites);
            return;
        }
        String conversationId = readConversationId(state);
        if (!StringUtils.hasText(conversationId)) {
            log.warn("SAA memory persist 跳过 ASSISTANT：无 conversationId, graphId={}, nodeId={}", graphId, nodeId);
            return;
        }
        String text = resolveAssistantText(out);
        if (!StringUtils.hasText(text)) {
            log.warn("SAA memory persist 跳过 ASSISTANT：正文为空, graphId={}, nodeId={}, parentOutputKey={}",
                    graphId, nodeId, parentOutputKey);
            return;
        }
        LlmRequestContext ctx = buildPersistContext(state, conversationId);
        try {
            log.info("SAA memory persist: nodeId={}, flags={}, writeUser=false, writeAssistant=true, "
                            + "contentChars={}, thinkingChars=n/a(drain-by-persister), conversationId={}",
                    nodeId, memoryWrites, text.length(), conversationId);
            memoryPersister.persistAssistantMain(new GraphNodeMemoryPersister.PersistAssistantMainRequest(
                    ctx, nodeId, text));
        } catch (RuntimeException ex) {
            log.error("SAA memory persist ASSISTANT 失败, graphId={}, nodeId={}, conversationId={}, err={}",
                    graphId, nodeId, conversationId, ex.toString(), ex);
        }
    }

    private String resolveAssistantText(Map<String, Object> out) {
        if (StringUtils.hasText(parentOutputKey) && out != null && out.containsKey(parentOutputKey)) {
            Object v = out.get(parentOutputKey);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private LlmRequestContext buildPersistContext(OverAllState state, String conversationId) {
        String agentCode = readAgentCode(state);
        String runId = readStateString(state, ModelOverrideSpec.ACE_RUN_ID_KEY);
        return new LlmRequestContext(
                agentCode == null ? "" : agentCode,
                graphId,
                nodeId,
                runId == null ? "" : runId,
                conversationId,
                state,
                ResourceBinding.disabledAll());
    }

    private Map<String, Object> snapshotInputVariables(OverAllState state) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (state == null) {
            return vars;
        }
        for (String key : inputKeys) {
            Object raw = state.value(key).orElse(null);
            if (raw != null) {
                vars.put(key, raw);
            }
        }
        // 展示正文 SPI 常读 user_query_raw 等，不在 inputKeys 时也透传常见键
        copyIfPresent(state, vars, "user_query_raw");
        copyIfPresent(state, vars, "user_query");
        copyIfPresent(state, vars, "media_refs");
        copyIfPresent(state, vars, LlmRequestContext.ACE_FORCE_SKILLS_KEY);
        copyIfPresent(state, vars, LlmRequestContext.ACE_FORCE_SKILL_LABELS_KEY);
        return vars;
    }

    private static void copyIfPresent(OverAllState state, Map<String, Object> vars, String key) {
        if (vars.containsKey(key)) {
            return;
        }
        Object raw = state.value(key).orElse(null);
        if (raw != null) {
            vars.put(key, raw);
        }
    }

    private static String firstNonBlankText(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return null;
        }
        for (Object v : variables.values()) {
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private static Set<MemoryWriteFlag> copyFlags(Set<MemoryWriteFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            return EnumSet.noneOf(MemoryWriteFlag.class);
        }
        return EnumSet.copyOf(flags);
    }

    /** 从父图 state 读取执行入口 agentCode。 */
    private static String readAgentCode(OverAllState state) {
        return readStateString(state, LlmRequestContext.ACE_AGENT_CODE_KEY);
    }

    private static String readConversationId(OverAllState state) {
        return readStateString(state, LlmRequestContext.ACE_CONVERSATION_ID_KEY);
    }

    private static String readStateString(OverAllState state, String key) {
        if (state == null || key == null) {
            return null;
        }
        Object v = state.value(key).orElse(null);
        return v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null;
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
        // 透传 agentCode / runId / conversationId / forceSkills / modelOverrides / media_refs …
        SaaFlowSeedSupport.copyParentContext(state, seed);
        // ROUTING：RoutingNode 只读 messages；缺则用 input 补 UserMessage（否则路由器盲选）
        SaaFlowSeedSupport.ensureMessagesFromInput(seed);
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
