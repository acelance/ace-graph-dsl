package io.acelance.graph.dsl.security.menu;

/**
 * 菜单 / 功能项元数据（不含授权结果）。
 *
 * @param key   权限 key，见 {@link GraphMenuPermissions}
 * @param label 显示名
 * @param group 分组
 */
public record MenuDescriptor(String key, String label, String group) {
}
