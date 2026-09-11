package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.List;

/**
 * 本地工具解析 SPI（方案 §4.3 / P3.1）。
 *
 * <p>按节点勾选的 {@code localToolKeys} 返回业务侧注册的 {@link NamedToolCallback}。
 * 未实现或返回空时，框架不挂载本地工具（开箱可跑）。</p>
 *
 * <p>单个 key 失败时应跳过并打日志，勿使整节点失败（§7.4）。</p>
 */
@FunctionalInterface
public interface LocalToolResolver {

    /**
     * 解析本地工具列表。
     *
     * @param ctx      请求上下文
     * @param toolKeys 本节点启用的本地工具 key；空则返回空列表
     * @return NamedToolCallback 列表；不得为 null（可空列表）
     */
    List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> toolKeys);
}
