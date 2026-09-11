package io.acelance.graph.dsl.ai.tool;

/**
 * MCP 工具缓存失效钩子（P2.2 热刷新）。
 *
 * <p>业务在 Nacos / 配置中心 / MCP server 变更时调用本接口，使下次
 * {@link McpToolResolver#resolve} 重新拉取工具清单。</p>
 *
 * <p>默认空实现 {@link #NOOP}：业务自管无缓存的 Resolver 时可注入此 Bean 占位。</p>
 */
public interface McpToolCache {

    /** 无操作实现（无缓存或不支持热刷新时使用） */
    McpToolCache NOOP = new McpToolCache() {
        @Override
        public void invalidate(String mcpKey) {
            // no-op
        }

        @Override
        public void invalidateAll() {
            // no-op
        }

        @Override
        public int size() {
            return 0;
        }
    };

    /**
     * 按 MCP server key 失效缓存。
     *
     * @param mcpKey server key；空白则忽略
     */
    void invalidate(String mcpKey);

    /** 清空全部 MCP 工具缓存 */
    void invalidateAll();

    /**
     * 当前缓存条目数（运维 / 单测）。
     *
     * @return 条目数；无缓存实现返回 0
     */
    int size();
}
