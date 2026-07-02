package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.MenuPermissionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单 / 功能权限 REST API，为设计器 UI 的菜单显隐与按钮禁用提供数据支撑。
 *
 * <p>权限判定委托给 {@link io.acelance.graph.dsl.security.menu.GraphMenuAccessControl} SPI，
 * 宿主应用可实现该接口对接自有权限框架；未接入时默认全部放行。</p>
 */
@RestController
@RequestMapping("/permissions")
public class GraphPermissionController {

    private final GraphMenuPermissionResolver resolver;

    public GraphPermissionController(GraphMenuPermissionResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 返回当前用户的菜单权限视图。
     */
    @GetMapping("/menus")
    public MenuPermissionView menus() {
        return resolver.resolve();
    }
}
