package io.acelance.graph.dsl.security.menu;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将 {@link GraphMenuCatalog} 与 {@link GraphMenuAccessControl} 组合，解析出当前用户的菜单权限视图。
 */
public class GraphMenuPermissionResolver {

    private final GraphMenuCatalog catalog;
    private final GraphMenuAccessControl accessControl;

    public GraphMenuPermissionResolver(GraphMenuCatalog catalog, GraphMenuAccessControl accessControl) {
        this.catalog = catalog;
        this.accessControl = accessControl;
    }

    /**
     * 解析当前用户的菜单权限视图。
     *
     * @return 含主体信息、全部菜单项授权结果与已授权 key 集合的视图
     */
    public MenuPermissionView resolve() {
        List<MenuItem> menus = catalog.items().stream()
                .map(d -> new MenuItem(d.key(), d.label(), d.group(), accessControl.isMenuGranted(d.key())))
                .toList();
        Set<String> grantedKeys = new LinkedHashSet<>();
        for (MenuItem item : menus) {
            if (item.permitted()) {
                grantedKeys.add(item.key());
            }
        }
        return new MenuPermissionView(accessControl.currentPrincipal().orElse(null), menus, grantedKeys);
    }

    /**
     * 判定单个菜单 key 是否授权（供后端写操作做服务端兜底校验）。
     *
     * @param menuKey 菜单 key
     * @return 是否授权
     */
    public boolean isGranted(String menuKey) {
        return accessControl.isMenuGranted(menuKey);
    }
}
