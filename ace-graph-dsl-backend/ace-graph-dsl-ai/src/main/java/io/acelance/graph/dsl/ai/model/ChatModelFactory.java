package io.acelance.graph.dsl.ai.model;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 按 {@link ModelEndpoint} 创建 {@link ChatModel} 的工厂 SPI。
 */
@FunctionalInterface
public interface ChatModelFactory {

    /**
     * @param endpoint 已解析的端点（含明文 apiKey）
     * @return ChatModel；不可返回 null
     */
    ChatModel create(ModelEndpoint endpoint);
}
