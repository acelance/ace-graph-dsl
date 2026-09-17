package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.acelance.graph.dsl.bizparam.DefaultStringBizParamInterpreter;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 agent 节点元数据（P0.5 D3：旧单 key / tools / 内联 skill·mcp 已删除）。
 *
 * <p>{@code prompt} 仅为节点特化追加；资源走 enable* + *Keys + mcpToolWhitelist。
 * {@code memoryMode} 见 P3.8 / 设计 §4.2.2。
 * {@code applyDeepThinking}：是否允许应用请求级深度思考（与 state
 * {@link LlmRequestContext#ACE_DEEP_THINKING_KEY} AND）。
 * {@code enableBizParams}/{@code bizParamInterpreterId}/{@code bizParamRaw}：业务附加参数（框架只存
 * 原文与解释器 id，由 {@link io.acelance.graph.dsl.bizparam.NodeBizParamInterpreter} 解析）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenericAgentSpec(
        String modelBaseUrl,
        String modelApiKey,
        boolean apiKeyMasked,
        String modelId,
        String prompt,
        String inputKeys,
        String outputKey,
        String streamResponseKind,
        String mediaInputKey,
        boolean enablePrompt,
        List<String> promptKeys,
        boolean enableModel,
        String modelConfigKey,
        boolean enableLocalTools,
        List<String> localToolKeys,
        boolean enableMcp,
        List<String> mcpKeys,
        Map<String, List<String>> mcpToolWhitelist,
        boolean enableSkill,
        List<String> skillKeys,
        MemoryMode memoryMode,
        boolean applyDeepThinking,
        boolean enableBizParams,
        String bizParamInterpreterId,
        String bizParamRaw
) {

    public static final String DEFAULT_OUTPUT_KEY = "agent_result";

    /** @see LlmRequestContext#ACE_DEEP_THINKING_KEY */
    public static final String DEEP_THINKING_STATE_KEY = LlmRequestContext.ACE_DEEP_THINKING_KEY;

    public GenericAgentSpec {
        if (outputKey == null || outputKey.isBlank()) {
            outputKey = DEFAULT_OUTPUT_KEY;
        }
        promptKeys = promptKeys == null ? List.of() : List.copyOf(promptKeys);
        localToolKeys = localToolKeys == null ? List.of() : List.copyOf(localToolKeys);
        mcpKeys = mcpKeys == null ? List.of() : List.copyOf(mcpKeys);
        skillKeys = skillKeys == null ? List.of() : List.copyOf(skillKeys);
        mcpToolWhitelist = mcpToolWhitelist == null ? Map.of() : Map.copyOf(mcpToolWhitelist);
        memoryMode = memoryMode == null ? MemoryMode.NONE : memoryMode;
        if (enableBizParams) {
            if (bizParamInterpreterId == null || bizParamInterpreterId.isBlank()) {
                bizParamInterpreterId = DefaultStringBizParamInterpreter.ID;
            } else {
                bizParamInterpreterId = bizParamInterpreterId.trim();
            }
        }
    }

    /** 测试 / Stub 便捷构造：内联模型 + 内联 prompt */
    public GenericAgentSpec(String modelBaseUrl, String modelApiKey, String modelId, String prompt) {
        this(modelBaseUrl, modelApiKey, false, modelId, prompt,
                null, DEFAULT_OUTPUT_KEY, null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of(), MemoryMode.NONE, false,
                false, null, null);
    }

    /**
     * 兼容旧测试签名（第五参 tools 已废弃，忽略）。
     *
     * @deprecated 使用四参构造
     */
    @Deprecated
    public GenericAgentSpec(String modelBaseUrl, String modelApiKey, String modelId,
                            String prompt, List<String> ignoredTools) {
        this(modelBaseUrl, modelApiKey, modelId, prompt);
    }

    public String effectiveOutputKey() {
        return (outputKey == null || outputKey.isBlank()) ? DEFAULT_OUTPUT_KEY : outputKey;
    }

    public MemoryMode effectiveMemoryMode() {
        return memoryMode == null ? MemoryMode.NONE : memoryMode;
    }

    public Set<String> inputKeySet() {
        return java.util.Set.copyOf(inputKeyList());
    }

    /**
     * 按配置声明顺序返回 inputKeys（去重）。用于组装 LLM USER，避免 Set 丢序。
     */
    public java.util.List<String> inputKeyList() {
        if (inputKeys == null || inputKeys.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(inputKeys.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public GenericAgentSpec masked() {
        if (apiKeyMasked || modelApiKey == null || modelApiKey.isBlank()) {
            return this;
        }
        String masked = modelApiKey.length() <= 4
                ? "****"
                : "****" + modelApiKey.substring(modelApiKey.length() - 4);
        return copyWithApiKey(masked, true);
    }

    public GenericAgentSpec withResolvedApiKey(String realKey) {
        return copyWithApiKey(realKey, false);
    }

    public GenericAgentSpec withOverride(io.acelance.graph.dsl.runtime.ModelOverride ov) {
        if (ov == null) {
            return this;
        }
        String baseUrl = ov.modelBaseUrl() != null ? ov.modelBaseUrl() : modelBaseUrl;
        String apiKey = ov.modelApiKey() != null ? ov.modelApiKey() : modelApiKey;
        boolean masked = ov.modelApiKey() == null && apiKeyMasked;
        String id = ov.modelId() != null ? ov.modelId() : modelId;
        return copy(baseUrl, apiKey, masked, id, streamResponseKind);
    }

    public GenericAgentSpec withStreamResponseKind(String kind) {
        return copy(modelBaseUrl, modelApiKey, apiKeyMasked, modelId, kind);
    }

    private GenericAgentSpec copyWithApiKey(String apiKey, boolean masked) {
        return copy(modelBaseUrl, apiKey, masked, modelId, streamResponseKind);
    }

    private GenericAgentSpec copy(String baseUrl, String apiKey, boolean masked, String id, String kind) {
        return new GenericAgentSpec(
                baseUrl, apiKey, masked, id, prompt, inputKeys, outputKey,
                kind, mediaInputKey,
                enablePrompt, promptKeys, enableModel, modelConfigKey,
                enableLocalTools, localToolKeys, enableMcp, mcpKeys, mcpToolWhitelist,
                enableSkill, skillKeys, memoryMode, applyDeepThinking,
                enableBizParams, bizParamInterpreterId, bizParamRaw);
    }
}
