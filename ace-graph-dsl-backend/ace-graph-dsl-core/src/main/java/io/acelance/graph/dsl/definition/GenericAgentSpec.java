package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通用 agent 节点元数据（P0.5 D3：旧单 key / tools / 内联 skill·mcp 已删除）。
 *
 * <p>{@code prompt} 仅为节点特化追加；资源走 enable* + *Keys + mcpToolWhitelist。</p>
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
        List<String> skillKeys
) {

    public static final String DEFAULT_OUTPUT_KEY = "agent_result";

    public GenericAgentSpec {
        if (outputKey == null || outputKey.isBlank()) {
            outputKey = DEFAULT_OUTPUT_KEY;
        }
        promptKeys = promptKeys == null ? List.of() : List.copyOf(promptKeys);
        localToolKeys = localToolKeys == null ? List.of() : List.copyOf(localToolKeys);
        mcpKeys = mcpKeys == null ? List.of() : List.copyOf(mcpKeys);
        skillKeys = skillKeys == null ? List.of() : List.copyOf(skillKeys);
        mcpToolWhitelist = mcpToolWhitelist == null ? Map.of() : Map.copyOf(mcpToolWhitelist);
    }

    /** 测试 / Stub 便捷构造：内联模型 + 内联 prompt */
    public GenericAgentSpec(String modelBaseUrl, String modelApiKey, String modelId, String prompt) {
        this(modelBaseUrl, modelApiKey, false, modelId, prompt,
                null, DEFAULT_OUTPUT_KEY, null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of());
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

    public Set<String> inputKeySet() {
        if (inputKeys == null || inputKeys.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(inputKeys.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
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
                enableSkill, skillKeys);
    }
}
