package io.acelance.graph.dsl.validation;

import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.definition.SaaWorkflowPattern;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.definition.SaaWorkflowKeys;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SAA 高阶节点规格校验（M2：SEQUENTIAL / PARALLEL / ROUTING / LOOP）。
 *
 * <p>由 {@link io.acelance.graph.dsl.builder.GraphValidator} 在节点扫描阶段调用。</p>
 */
public final class SaaWorkflowValidator {

    private static final Logger log = LoggerFactory.getLogger(SaaWorkflowValidator.class);

    /** M2 已开放的 pattern 集合 */
    private static final Set<SaaWorkflowPattern> OPEN_PATTERNS = Set.of(
            SaaWorkflowPattern.SEQUENTIAL,
            SaaWorkflowPattern.PARALLEL,
            SaaWorkflowPattern.ROUTING,
            SaaWorkflowPattern.LOOP);

    private final GraphNodeRegistry nodeRegistry;
    private final List<SubAgentResolver> subAgentResolvers;
    private final boolean saaModuleEnabled;

    /**
     * @param nodeRegistry       节点注册中心（解析 generic:{id}）
     * @param subAgentResolvers  已注册的子 Agent 解析器（可空）
     * @param saaModuleEnabled   是否已注入 {@code SaaWorkflowNodeFactory}
     */
    public SaaWorkflowValidator(GraphNodeRegistry nodeRegistry,
                                List<SubAgentResolver> subAgentResolvers,
                                boolean saaModuleEnabled) {
        this.nodeRegistry = nodeRegistry;
        this.subAgentResolvers = subAgentResolvers != null ? List.copyOf(subAgentResolvers) : List.of();
        this.saaModuleEnabled = saaModuleEnabled;
    }

    /**
     * 校验单个 SAA_WORKFLOW 节点，错误追加到 {@code errors}。
     *
     * @param graphId 图 ID（日志）
     * @param ref     节点引用
     * @param errors  错误收集列表
     */
    public void validateNode(String graphId, NodeRef ref, List<String> errors) {
        String nodeId = ref.nodeId();
        if (!saaModuleEnabled) {
            String msg = "节点 '" + nodeId + "' 为 SAA_WORKFLOW，但未启用多智能体高阶节点模块"
                    + "（请引入 ace-graph-dsl-saa-agent 依赖）";
            log.error("SAA 校验失败, graphId={}, nodeId={}, reason={}", graphId, nodeId, msg);
            errors.add(msg);
            return;
        }
        SaaWorkflowSpec spec = ref.saaSpec();
        if (spec == null) {
            String msg = "SAA_WORKFLOW 节点缺少 saaSpec: " + nodeId;
            log.error("SAA 校验失败, graphId={}, nodeId={}, reason={}", graphId, nodeId, msg);
            errors.add(msg);
            return;
        }
        if (ref.agentSpec() != null) {
            errors.add("SAA_WORKFLOW 节点不得同时携带 agentSpec（与 saaSpec 互斥）: " + nodeId);
        }

        SaaWorkflowPattern pattern = spec.resolvedPattern();
        if (pattern == null) {
            errors.add("SAA_WORKFLOW 节点 pattern 非法: " + nodeId + ", pattern=" + spec.pattern());
            return;
        }
        if (!OPEN_PATTERNS.contains(pattern)) {
            errors.add("SAA_WORKFLOW 节点 pattern 尚未开放: " + nodeId
                    + ", pattern=" + pattern);
        }

        if (spec.outputKey() == null || spec.outputKey().isBlank()) {
            errors.add("SAA_WORKFLOW 节点 outputKey 不能为空: " + nodeId);
        }
        if (spec.inputKeyList().isEmpty()) {
            errors.add("SAA_WORKFLOW 节点 inputKeys 不能为空: " + nodeId);
        }
        if (spec.subAgents() == null || spec.subAgents().isEmpty()) {
            errors.add("SAA_WORKFLOW 节点 subAgents 不能为空: " + nodeId);
            return;
        }

        Set<String> names = new HashSet<>();
        Set<String> outputKeys = new HashSet<>();
        for (int i = 0; i < spec.subAgents().size(); i++) {
            SaaSubAgentRef sub = spec.subAgents().get(i);
            String prefix = "SAA_WORKFLOW '" + nodeId + "' subAgents[" + i + "]";
            if (sub == null) {
                errors.add(prefix + " 为 null");
                continue;
            }
            if (sub.name() == null || sub.name().isBlank()) {
                errors.add(prefix + " name 不能为空");
            } else if (!names.add(sub.name().trim())) {
                errors.add(prefix + " name 重复: " + sub.name());
            }
            if (sub.outputKey() == null || sub.outputKey().isBlank()) {
                errors.add(prefix + " outputKey 不能为空");
            } else if (!outputKeys.add(sub.outputKey().trim())) {
                errors.add(prefix + " outputKey 重名: " + sub.outputKey());
            }
            String impl = sub.effectiveImpl();
            if (!SaaSubAgentRefs.refMatchesImpl(impl, sub.ref())) {
                errors.add(prefix + " ref 与 impl 不一致: impl=" + impl + ", ref=" + sub.ref());
            }
            if (!hasResolver(impl)) {
                errors.add(prefix + " 无匹配的 SubAgentResolver: impl=" + impl);
            }
            if (SaaSubAgentRefs.IMPL_GENERIC_AGENT.equalsIgnoreCase(impl)) {
                validateGenericRef(graphId, nodeId, prefix, sub, errors);
            } else if (SaaSubAgentRefs.IMPL_AGENTSCOPE.equalsIgnoreCase(impl)) {
                validateAgentscopeRef(graphId, nodeId, prefix, sub, errors);
            }
        }

        if (pattern == SaaWorkflowPattern.PARALLEL) {
            int n = spec.subAgents().size();
            if (n < 2) {
                errors.add("PARALLEL 模式至少需要 2 个子 Agent: " + nodeId);
            }
            if (n > 10) {
                errors.add("PARALLEL 模式最多 10 个子 Agent: " + nodeId + ", actual=" + n);
            }
        }
        if (pattern == SaaWorkflowPattern.ROUTING && spec.subAgents().size() < 2) {
            errors.add("ROUTING 模式至少需要 2 个子 Agent: " + nodeId);
        }
        if (pattern == SaaWorkflowPattern.LOOP) {
            // 有 exitConditionKey 时要求 op/value；无 key 则走 CountLoopStrategy（仅 maxIterations）
            boolean hasExitKey = spec.exitConditionKey() != null && !spec.exitConditionKey().isBlank();
            if (hasExitKey) {
                if (spec.exitConditionOp() == null || spec.exitConditionOp().isBlank()) {
                    errors.add("LOOP 模式缺少 exitConditionOp: " + nodeId);
                }
                if (spec.exitConditionValue() == null || spec.exitConditionValue().isBlank()) {
                    errors.add("LOOP 模式缺少 exitConditionValue: " + nodeId);
                }
            }
            if (spec.effectiveMaxIterations() > SaaWorkflowSpec.HARD_MAX_ITERATIONS) {
                errors.add("LOOP maxIterations 超过硬上限 " + SaaWorkflowSpec.HARD_MAX_ITERATIONS
                        + ": " + nodeId);
            }
        }

        log.info("SAA 节点校验完成, graphId={}, nodeId={}, pattern={}, subAgents={}, errorCount增量待上层汇总",
                graphId, nodeId, pattern, spec.subAgents().size());
    }

    private void validateGenericRef(String graphId, String nodeId, String prefix,
                                    SaaSubAgentRef sub, List<String> errors) {
        String registeredId = SaaSubAgentRefs.parseGenericNodeId(sub.ref());
        if (registeredId == null) {
            return;
        }
        validateRegisteredAgentForSub(graphId, nodeId, prefix, sub.ref(), registeredId, errors);
    }

    /**
     * AGENTSCOPE：id 仍指向 ACE 注册 GenericAgent（仅执行引擎切换）。
     */
    private void validateAgentscopeRef(String graphId, String nodeId, String prefix,
                                       SaaSubAgentRef sub, List<String> errors) {
        String registeredId = SaaSubAgentRefs.parseAgentscopeId(sub.ref());
        if (registeredId == null) {
            return;
        }
        validateRegisteredAgentForSub(graphId, nodeId, prefix, sub.ref(), registeredId, errors);
    }

    private void validateRegisteredAgentForSub(String graphId, String nodeId, String prefix,
                                               String ref, String registeredId, List<String> errors) {
        if (!nodeRegistry.contains(registeredId)) {
            errors.add(prefix + " 引用未在注册中心找到: " + ref);
            return;
        }
        RegisteredGraphNode registered = nodeRegistry.get(registeredId);
        if (!(registered instanceof GraphBoundAgentNode agentNode)) {
            errors.add(prefix + " 引用不是 GenericAgent 节点: " + ref);
            return;
        }
        GenericAgentSpec agentSpec = agentNode.agentSpec();
        if (agentSpec != null && agentSpec.effectiveMemoryMode() == MemoryMode.READ_WRITE) {
            // Q5：子 Agent 禁止 READ_WRITE，避免与父图双源落盘
            errors.add(prefix + " 子 Agent memoryMode=READ_WRITE 禁止（Q5）: " + ref
                    + ", graphId=" + graphId + ", parentNodeId=" + nodeId);
        }
    }

    private boolean hasResolver(String impl) {
        if (impl == null) {
            return false;
        }
        String key = impl.trim().toUpperCase(Locale.ROOT);
        for (SubAgentResolver resolver : subAgentResolvers) {
            if (resolver.supports(key) || resolver.supports(impl)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集图中所有 SAA 节点需要的 KeyStrategy 键（父 outputKey + 子 outputKey）。
     *
     * @param ref 节点
     * @return 键集合
     */
    public static List<String> collectNeededKeys(NodeRef ref) {
        List<String> keys = new ArrayList<>();
        if (ref == null || ref.saaSpec() == null) {
            return keys;
        }
        SaaWorkflowSpec spec = ref.saaSpec();
        if (spec.outputKey() != null && !spec.outputKey().isBlank()) {
            keys.add(spec.outputKey().trim());
        }
        keys.addAll(spec.subAgentOutputKeys());
        // M4：子步骤轨迹保留键（构图自动补 KeyStrategy）
        keys.add(SaaWorkflowKeys.SUB_STEPS_KEY);
        keys.add(SaaWorkflowKeys.SUB_STEPS_META_KEY);
        return keys;
    }

    /** 是否 SAA_WORKFLOW 节点 */
    public static boolean isSaaWorkflowNode(NodeRef ref) {
        return ref != null && (ref.hasSaaSpec()
                || GraphNodeDescriptor.CATEGORY_SAA_WORKFLOW.equals(ref.category()));
    }
}
