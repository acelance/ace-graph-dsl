package io.acelance.graph.dsl.resource;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Catalog 列表条目（§7.2 / P3.3）。
 *
 * <p>MCP 三级树：server 级条目的 {@link #children()} 为该 server 暴露的工具列表；
 * 其它资源类型 children 通常为空。</p>
 *
 * @param key         资源 key
 * @param label       展示名
 * @param description 说明
 * @param children    子节点（如 MCP 工具）；无则空列表
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ResourceItem(
        String key,
        String label,
        String description,
        List<ResourceItem> children
) {
    public ResourceItem {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** 无子节点的扁平条目 */
    public ResourceItem(String key, String label, String description) {
        this(key, label, description, List.of());
    }

    /** 带工具子节点的 MCP server 条目 */
    public static ResourceItem mcpServer(String key, String label, String description,
                                         List<ResourceItem> tools) {
        return new ResourceItem(key, label, description, tools);
    }

    /** 工具叶子（通常不再嵌套） */
    public static ResourceItem tool(String key, String label) {
        return new ResourceItem(key, label, null, List.of());
    }
}
