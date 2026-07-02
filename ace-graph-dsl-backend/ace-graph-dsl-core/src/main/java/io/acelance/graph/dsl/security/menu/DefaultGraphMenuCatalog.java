package io.acelance.graph.dsl.security.menu;

import java.util.List;

import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_CREATE;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_PREVIEW;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_PUBLISH;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_ROLLBACK;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_SAVE;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_VALIDATE;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GRAPH_VIEW;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GROUP_GRAPH;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.GROUP_SCRIPT_NODE;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.SCRIPT_NODE_CREATE;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.SCRIPT_NODE_DELETE;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.SCRIPT_NODE_TEST;
import static io.acelance.graph.dsl.security.menu.GraphMenuPermissions.SCRIPT_NODE_VIEW;

/**
 * 默认菜单目录：覆盖 ace-graph-dsl 设计器内置功能。
 */
public class DefaultGraphMenuCatalog implements GraphMenuCatalog {

    private static final List<MenuDescriptor> ITEMS = List.of(
            new MenuDescriptor(GRAPH_VIEW, "查看 Graph", GROUP_GRAPH),
            new MenuDescriptor(GRAPH_CREATE, "新建 Graph DSL", GROUP_GRAPH),
            new MenuDescriptor(GRAPH_SAVE, "保存草稿", GROUP_GRAPH),
            new MenuDescriptor(GRAPH_VALIDATE, "校验", GROUP_GRAPH),
            new MenuDescriptor(GRAPH_PREVIEW, "预览", GROUP_GRAPH),
            new MenuDescriptor(GRAPH_PUBLISH, "发布", GROUP_GRAPH),
            new MenuDescriptor(GRAPH_ROLLBACK, "回滚", GROUP_GRAPH),
            new MenuDescriptor(SCRIPT_NODE_VIEW, "查看脚本节点", GROUP_SCRIPT_NODE),
            new MenuDescriptor(SCRIPT_NODE_CREATE, "新建/编辑脚本节点", GROUP_SCRIPT_NODE),
            new MenuDescriptor(SCRIPT_NODE_DELETE, "删除脚本节点", GROUP_SCRIPT_NODE),
            new MenuDescriptor(SCRIPT_NODE_TEST, "校验/试跑脚本", GROUP_SCRIPT_NODE)
    );

    @Override
    public List<MenuDescriptor> items() {
        return ITEMS;
    }
}
