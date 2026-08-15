package io.acelance.graph.dsl.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 MCP server 注册表（默认实现）。可通过 Spring 容器替换为配置中心适配。
 */
@Component
public class InMemoryMcpServerRegistry implements McpServerRegistry {

    private final Map<String, McpServerConfig> store = new ConcurrentHashMap<>();

    public void put(String key, McpServerConfig config) {
        store.put(key, config);
    }

    /** 快捷登记：仅远程端点 */
    public void put(String key, String endpoint) {
        store.put(key, McpServerConfig.ofEndpoint(key, endpoint));
    }

    @Override
    public Optional<McpServerConfig> load(String key) {
        return Optional.ofNullable(store.get(key));
    }
}
