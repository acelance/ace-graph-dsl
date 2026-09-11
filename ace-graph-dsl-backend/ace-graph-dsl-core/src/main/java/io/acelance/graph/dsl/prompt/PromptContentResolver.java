package io.acelance.graph.dsl.prompt;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.List;

/**
 * 提示词内容解析 SPI（方案 §4.3 / P3.5）。
 *
 * <p>按节点 {@code promptKeys} 顺序加载并合并正文；业务用 Nacos/DB 等实现。
 * 单个 key 缺失应由实现抛错或返回空并由调用方按 §7.4（Prompt 硬失败）处理。</p>
 */
@FunctionalInterface
public interface PromptContentResolver {

    /**
     * @param ctx        请求上下文
     * @param promptKeys 本节点勾选的 prompt key；空则返回空串
     * @return 合并后的 prompt 文本（可含多段，建议用 {@code \n\n} 分隔）
     */
    String resolve(LlmRequestContext ctx, List<String> promptKeys);
}
