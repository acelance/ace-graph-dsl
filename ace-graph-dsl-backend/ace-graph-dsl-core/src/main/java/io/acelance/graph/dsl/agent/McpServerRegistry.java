package io.acelance.graph.dsl.agent;

import java.util.Optional;

/**
 * MCP server 按 key 解析 SPI（与 {@link PromptRepository} / {@link SkillRegistry} 对称）。
 *
 * <p>用途：通用 agent 节点只在 DSL 里记 {@code mcpKey}，真实的 server 地址 / 鉴权头 /
 * 工具白名单等敏感或易变配置留在外部配置管理平台。接入方只需把自己的实现注册为
 * Spring Bean（单例即可），框架会在节点执行时自动接管解析：</p>
 *
 * <pre>{@code
 * @Component
 * public class NacosMcpServerRegistry implements McpServerRegistry {
 *     @Override
 *     public Optional<McpServerConfig> load(String key) {
 *         return Optional.ofNullable(configService.get("mcp/" + key))
 *                 .map(json -> McpServerConfig.ofRaw(key, json));
 *     }
 * }
 * }</pre>
 *
 * <p>容器中不存在任何实现时回落到 {@link InMemoryMcpServerRegistry}，保证开箱可用。</p>
 */
public interface McpServerRegistry {

    /**
     * 按 key 加载 MCP server 配置。
     *
     * @param key 资源 key（对应 {@code GenericAgentSpec.mcpKey()}）
     * @return 配置；不存在时返回 {@link Optional#empty()}
     */
    Optional<McpServerConfig> load(String key);
}
