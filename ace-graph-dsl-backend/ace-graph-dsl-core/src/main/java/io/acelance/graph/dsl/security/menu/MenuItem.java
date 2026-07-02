package io.acelance.graph.dsl.security.menu;

/**
 * 解析后的菜单项（含当前用户是否被授权）。
 *
 * @param key       权限 key
 * @param label     显示名
 * @param group     分组
 * @param permitted 当前用户是否被授权
 */
public record MenuItem(String key, String label, String group, boolean permitted) {
}
