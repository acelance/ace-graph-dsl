package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;

import java.util.List;

/**
 * mcp 工具提供者 SPI：按工具名列表解析为 {@link AgentTool}。
 * 默认内存实现 {@link InMemoryMcpToolProvider}；可替换为真实 MCP client 适配。
 *
 * <p>解析入口有两个重载：</p>
 * <ul>
 *   <li>{@link #resolve(List, GenericAgentSpec)} —— 仅凭 spec（含内联 {@code mcp} 文本）解析；</li>
 *   <li>{@link #resolve(List, GenericAgentSpec, McpServerConfig)} —— 额外拿到由
 *       {@link McpServerRegistry} 按 {@code mcpKey} 解析出的 server 配置。</li>
 * </ul>
 * 实现方通常只需覆写三参版本；默认实现会把它降级委派给两参版本，保证既有实现不破坏。
 */
public interface McpToolProvider {

    List<AgentTool> resolve(List<String> toolNames, GenericAgentSpec spec);

    /**
     * 带 MCP server 配置的解析（推荐实现此版本）。
     *
     * @param toolNames    启用的工具名列表
     * @param spec         节点元数据（内联 mcp 文本见 {@code spec.mcp()}）
     * @param serverConfig 由 {@code mcpKey} 解析出的 server 配置；未配置 key 时为 {@code null}
     */
    default List<AgentTool> resolve(List<String> toolNames, GenericAgentSpec spec, McpServerConfig serverConfig) {
        return resolve(toolNames, spec);
    }
}
