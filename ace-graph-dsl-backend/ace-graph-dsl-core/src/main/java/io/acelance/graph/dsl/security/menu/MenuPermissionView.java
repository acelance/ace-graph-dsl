package io.acelance.graph.dsl.security.menu;

import java.util.List;
import java.util.Set;

/**
 * 菜单权限视图，作为 REST 响应返回给设计器 UI。
 *
 * @param principal   当前登录主体（可为 null）
 * @param menus       全部菜单项及其授权结果
 * @param grantedKeys 已授权的菜单 key 集合（便于前端快速判定）
 */
public record MenuPermissionView(MenuPrincipal principal, List<MenuItem> menus, Set<String> grantedKeys) {
}
