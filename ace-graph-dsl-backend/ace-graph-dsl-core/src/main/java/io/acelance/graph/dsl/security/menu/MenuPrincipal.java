package io.acelance.graph.dsl.security.menu;

import java.util.List;

/**
 * 当前登录主体的精简信息，供设计器 UI 展示（可选）。
 *
 * @param id    用户唯一标识
 * @param name  显示名
 * @param roles 角色 / 权限组（可空）
 */
public record MenuPrincipal(String id, String name, List<String> roles) {

    public MenuPrincipal {
        roles = roles != null ? List.copyOf(roles) : List.of();
    }
}
