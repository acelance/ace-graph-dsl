package io.acelance.graph.dsl.ai.options;

import io.acelance.graph.dsl.ai.template.LlmCallRequest;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 业务侧 ChatOptions 定制 SPI（深度思考 extraBody、toolContext 播种等）。
 *
 * <p>{@code base} 可为 null（无工具且尚未建 Options）；返回 null 表示不改。</p>
 */
@FunctionalInterface
public interface LlmChatOptionsCustomizer {

    /**
     * @param base 框架已建 Options（常为带 toolCallbacks 的 ToolCallingChatOptions），可空
     * @param req  本轮 LLM 请求（含 {@link LlmCallRequest#deepThinking()}）
     * @return 最终 Options；null = 沿用 base
     */
    ChatOptions customize(ChatOptions base, LlmCallRequest req);
}
