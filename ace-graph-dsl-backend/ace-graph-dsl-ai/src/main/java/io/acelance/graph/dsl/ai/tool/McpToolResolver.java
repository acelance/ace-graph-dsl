package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.List;

/**
 * MCP 工具解析 SPI（方案 §4.2 / §7.1.1）。
 *
 * <p>按节点勾选的 {@code mcpKeys} 返回该批 server 暴露的工具（server 级全集）。
 * 节点级工具白名单由框架 {@link McpToolFilter} 统一施加，本接口实现<strong>不要</strong>再读
 * {@code ResourceBinding.mcpToolWhitelist}。</p>
 *
 * <p>缓存 / 热刷新策略由实现自行决定：框架提供默认
 * {@link CachingMcpToolResolver}；业务也可每次请求直连 MCP {@code list_tools}。</p>
 */
@FunctionalInterface
public interface McpToolResolver {

    /**
     * 解析 MCP 工具列表。
     *
     * @param ctx     请求上下文（含 agentCode / binding）
     * @param mcpKeys 本节点启用的 MCP server key；空则返回空列表
     * @return NamedToolCallback 列表；单个 key 失败时应跳过并打日志，勿让整批失败（§7.4）
     */
    List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> mcpKeys);
}
