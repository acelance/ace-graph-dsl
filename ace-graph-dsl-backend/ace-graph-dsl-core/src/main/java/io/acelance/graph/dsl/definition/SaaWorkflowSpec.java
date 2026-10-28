package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.acelance.graph.dsl.bizparam.DefaultStringBizParamInterpreter;
import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SAA 高阶工作流节点规格（挂在 {@link NodeRef#saaSpec()}）。
 *
 * <p>M2 开放 {@link SaaWorkflowPattern} 四种模式；LOOP/ROUTING 使用对应编排字段。</p>
 * <p>G-STREAM-INNER：{@code applyDeepThinking} / bizParam 与 {@link GenericAgentSpec} 同级语义；
 * ROUTING 另可配 {@code routerSystemPrompt} / {@code routerInstruction} / {@code routerFallbackAgent}
 * （接到 {@code LlmRoutingAgent}；缺省走 Framework 默认路由提示）。</p>
 *
 * @param pattern                模式
 * @param inputKeys              父节点从 OverAllState 读取的键（逗号/空白分隔）
 * @param outputKey              父节点写回主输出键
 * @param streamResponseKind     父节点流式种类（BIZ/OUTPUT 等）
 * @param modelConfigKey         ROUTING 路由器模型 key（按宿主 SPI 解析）
 * @param maxIterations          LOOP 迭代上限
 * @param exitConditionKey       LOOP 退出条件：state 键
 * @param exitConditionOp        LOOP 退出条件运算符（如 GT）
 * @param exitConditionValue     LOOP 退出条件比较值
 * @param subAgents              子 Agent 绑定列表（顺序对 SEQUENTIAL 有意义）
 * @param applyDeepThinking      是否允许应用请求级深度思考
 * @param enableBizParams        是否启用业务附加参数
 * @param bizParamInterpreterId  附加参数解释器 id
 * @param bizParamRaw            附加参数原文（框架只存，由解释器解析）
 * @param routerSystemPrompt     ROUTING 路由器系统提示（路由规则）；空=Framework 默认
 * @param routerInstruction      ROUTING 路由器 instruction（可选）
 * @param routerFallbackAgent    ROUTING 选路失败时回落的子 Agent {@code name}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaaWorkflowSpec(
        String pattern,
        String inputKeys,
        String outputKey,
        String streamResponseKind,
        String modelConfigKey,
        Integer maxIterations,
        String exitConditionKey,
        String exitConditionOp,
        String exitConditionValue,
        List<SaaSubAgentRef> subAgents,
        boolean applyDeepThinking,
        boolean enableBizParams,
        String bizParamInterpreterId,
        String bizParamRaw,
        String routerSystemPrompt,
        String routerInstruction,
        String routerFallbackAgent
) {

    /** LOOP 默认迭代次数 */
    public static final int DEFAULT_MAX_ITERATIONS = 3;
    /** LOOP 硬上限 */
    public static final int HARD_MAX_ITERATIONS = 10;

    /** @see LlmRequestContext#ACE_DEEP_THINKING_KEY */
    public static final String DEEP_THINKING_STATE_KEY = LlmRequestContext.ACE_DEEP_THINKING_KEY;

    public SaaWorkflowSpec {
        subAgents = subAgents == null ? List.of() : List.copyOf(subAgents);
        if (enableBizParams) {
            if (bizParamInterpreterId == null || bizParamInterpreterId.isBlank()) {
                bizParamInterpreterId = DefaultStringBizParamInterpreter.ID;
            } else {
                bizParamInterpreterId = bizParamInterpreterId.trim();
            }
        }
        routerSystemPrompt = blankToNull(routerSystemPrompt);
        routerInstruction = blankToNull(routerInstruction);
        routerFallbackAgent = blankToNull(routerFallbackAgent);
    }

    /**
     * 兼容旧构造（无通道 / 无路由器提示词字段）。
     */
    public static SaaWorkflowSpec of(
            String pattern,
            String inputKeys,
            String outputKey,
            String streamResponseKind,
            String modelConfigKey,
            Integer maxIterations,
            String exitConditionKey,
            String exitConditionOp,
            String exitConditionValue,
            List<SaaSubAgentRef> subAgents) {
        return new SaaWorkflowSpec(
                pattern, inputKeys, outputKey, streamResponseKind, modelConfigKey,
                maxIterations, exitConditionKey, exitConditionOp, exitConditionValue, subAgents,
                false, false, null, null, null, null, null);
    }

    /** 解析后的 pattern；无法识别时 null */
    public SaaWorkflowPattern resolvedPattern() {
        return SaaWorkflowPattern.fromString(pattern);
    }

    /**
     * 按配置声明顺序返回 inputKeys（去重）。
     *
     * @return 输入键列表；未配置时空列表
     */
    public List<String> inputKeyList() {
        if (inputKeys == null || inputKeys.isBlank()) {
            return List.of();
        }
        return Arrays.stream(inputKeys.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** 有效 maxIterations（空白回落默认） */
    public int effectiveMaxIterations() {
        return maxIterations == null || maxIterations <= 0 ? DEFAULT_MAX_ITERATIONS : maxIterations;
    }

    /**
     * 子 Agent outputKey 列表（保持顺序，跳过空白）。
     *
     * @return 子输出键
     */
    public List<String> subAgentOutputKeys() {
        List<String> keys = new ArrayList<>();
        for (SaaSubAgentRef ref : subAgents) {
            if (ref != null && ref.outputKey() != null && !ref.outputKey().isBlank()) {
                keys.add(ref.outputKey().trim());
            }
        }
        return List.copyOf(keys);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
