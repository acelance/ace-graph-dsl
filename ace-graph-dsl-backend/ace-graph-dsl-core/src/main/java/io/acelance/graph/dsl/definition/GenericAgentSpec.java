package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通用 agent 节点（GENERIC_AGENT）元数据规格。
 *
 * <p>节点仅声明式携带「模型 / prompt / skill / mcp / tools」等元数据，
 * 后端据这些元数据动态装配一个模板 graph 节点（{@code GenericAgentNode}），
 * 一般模型调用无需再写内嵌 Java 代码。</p>
 *
 * <p>字段设计遵循「二选一」约定：{@code prompt} 与 {@code promptKey}、{@code skill} 与
 * {@code skillKey}、{@code mcp} 与 {@code mcpKey} 分别二选一；优先使用内联值，
 * 若内联为空则回落到 key 经对应 {@code *Repository} / {@code *Provider} SPI 加载。</p>
 *
 * @param modelBaseUrl  OpenAI 兼容端点（如 DashScope 兼容模式地址）
 * @param modelApiKey   api-key；落库时会被掩码（仅留后 4 位），运行时经 SecretResolver 还原
 * @param apiKeyMasked  true 表示 modelApiKey 为掩码/引用值，需还原后才能调用
 * @param modelId       模型标识（如 qwen-plus / gpt-4o）
 * @param prompt        内联 prompt 模板，支持 {{state.key}} 占位
 * @param promptKey     或：prompt 资源 key（经 PromptRepository 加载）
 * @param skill         内联 skill 描述（可选）
 * @param skillKey      或：skill 资源 key
 * @param mcp           内联 mcp server 描述（可选）
 * @param mcpKey        或：mcp 资源 key
 * @param tools         启用的工具名列表（经 McpToolProvider 解析为 AgentTool）
 * @param inputKeys     逗号分隔的 state key（prompt 读取，供可达性校验；可空）
 * @param outputKey     写回 state 的 key，默认 agent_result
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenericAgentSpec(
        String modelBaseUrl,
        String modelApiKey,
        boolean apiKeyMasked,
        String modelId,
        String prompt,
        String promptKey,
        String skill,
        String skillKey,
        String mcp,
        String mcpKey,
        List<String> tools,
        String inputKeys,
        String outputKey
) {

    /** 默认 output key */
    public static final String DEFAULT_OUTPUT_KEY = "agent_result";

    public GenericAgentSpec {
        if (outputKey == null || outputKey.isBlank()) {
            outputKey = DEFAULT_OUTPUT_KEY;
        }
        if (tools == null) {
            tools = List.of();
        }
    }

    /** 便捷构造：仅给最常用字段（其余置空/默认） */
    public GenericAgentSpec(String modelBaseUrl, String modelApiKey, String modelId,
                             String prompt, List<String> tools) {
        this(modelBaseUrl, modelApiKey, false, modelId, prompt, null,
                null, null, null, null, tools, null, DEFAULT_OUTPUT_KEY);
    }

    /** 返回输出 key（非空保证） */
    public String effectiveOutputKey() {
        return (outputKey == null || outputKey.isBlank()) ? DEFAULT_OUTPUT_KEY : outputKey;
    }

    /** 解析 inputKeys 为集合（逗号/空白分隔，去空） */
    public java.util.Set<String> inputKeySet() {
        if (inputKeys == null || inputKeys.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(inputKeys.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 返回掩码副本：仅保留 api-key 后 4 位，并置 apiKeyMasked=true。
     * 用于落库脱敏；运行时由 SecretResolver 还原真实值。
     */
    public GenericAgentSpec masked() {
        if (apiKeyMasked || modelApiKey == null || modelApiKey.isBlank()) {
            return this;
        }
        String masked = modelApiKey.length() <= 4
                ? "****"
                : "****" + modelApiKey.substring(modelApiKey.length() - 4);
        return new GenericAgentSpec(modelBaseUrl, masked, true, modelId,
                prompt, promptKey, skill, skillKey, mcp, mcpKey, tools, inputKeys, outputKey);
    }

    /** 用还原后的真实 api-key生成新副本（apiKeyMasked=false） */
    public GenericAgentSpec withResolvedApiKey(String realKey) {
        return new GenericAgentSpec(modelBaseUrl, realKey, false, modelId,
                prompt, promptKey, skill, skillKey, mcp, mcpKey, tools, inputKeys, outputKey);
    }
}
