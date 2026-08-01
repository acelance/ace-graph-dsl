package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;

import java.util.List;

/**
 * mcp 工具提供者 SPI：按工具名列表解析为 {@link AgentTool}。
 * 默认内存实现 {@link InMemoryMcpToolProvider}；可替换为真实 MCP client 适配。
 */
public interface McpToolProvider {

    List<AgentTool> resolve(List<String> toolNames, GenericAgentSpec spec);
}
