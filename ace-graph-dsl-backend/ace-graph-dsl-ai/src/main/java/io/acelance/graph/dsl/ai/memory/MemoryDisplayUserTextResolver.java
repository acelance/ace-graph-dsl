package io.acelance.graph.dsl.ai.memory;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.Map;

/**
 * 记忆落库用的「真实用户话」解析 SPI（与喂给 LLM 的材料拼接块分离）。
 *
 * <p>框架不穷举 state key；由业务侧 Bean 决定从哪些变量取展示正文。
 * 未注册 Bean 时不写 {@code display_content}，落库 USER 正文即为 LLM user 全文。</p>
 *
 * <p>便捷实现：{@link KeyListMemoryDisplayUserTextResolver}。</p>
 */
@FunctionalInterface
public interface MemoryDisplayUserTextResolver {

    /**
     * @return 非空则写入 UserMessage metadata {@code display_content}；
     *         {@code null}/空白表示不分离，记忆侧用 LLM user 原文
     */
    String resolve(MemoryDisplayUserTextRequest request);

    /**
     * @param ctx           节点上下文
     * @param variables     本节点渲染用变量（含 inputKeys 对应 state）
     * @param llmUserText   已渲染、将发给模型的 USER 正文（可能含材料块）
     */
    record MemoryDisplayUserTextRequest(
            LlmRequestContext ctx,
            Map<String, Object> variables,
            String llmUserText
    ) {
    }
}
