package io.acelance.graph.dsl.security.menu;

/**
 * 标准菜单 / 功能权限 key 常量。
 *
 * <p>这些 key 是 ace-graph-dsl 设计器内置功能的稳定标识，宿主应用在对接自有权限框架时，
 * 可将自身的权限码映射到这些 key（或扩展 {@link GraphMenuCatalog} 增加自定义菜单项）。</p>
 */
public final class GraphMenuPermissions {

    private GraphMenuPermissions() {
    }

    /** 分组：Graph 编排 */
    public static final String GROUP_GRAPH = "graph";
    /** 分组：脚本节点 */
    public static final String GROUP_SCRIPT_NODE = "script-node";

    /** 查看 Graph 目录 / 定义 */
    public static final String GRAPH_VIEW = "graph:view";
    /** 新建 Graph DSL */
    public static final String GRAPH_CREATE = "graph:create";
    /** 保存草稿 */
    public static final String GRAPH_SAVE = "graph:save";
    /** 校验 */
    public static final String GRAPH_VALIDATE = "graph:validate";
    /** 预览（PlantUML / Mermaid） */
    public static final String GRAPH_PREVIEW = "graph:preview";
    /** 发布 */
    public static final String GRAPH_PUBLISH = "graph:publish";
    /** 回滚 */
    public static final String GRAPH_ROLLBACK = "graph:rollback";

    /** 查看脚本节点 */
    public static final String SCRIPT_NODE_VIEW = "script-node:view";
    /** 新建 / 编辑脚本节点 */
    public static final String SCRIPT_NODE_CREATE = "script-node:create";
    /** 删除脚本节点 */
    public static final String SCRIPT_NODE_DELETE = "script-node:delete";
    /** 校验 / 试跑脚本 */
    public static final String SCRIPT_NODE_TEST = "script-node:test";
}
