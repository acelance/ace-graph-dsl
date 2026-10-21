package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.MenuPermissionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code GraphPermissionController}：{@code /api/graph/permissions/**}。
 */
@RestController
@RequestMapping("/api/graph/permissions")
public class GraphPermissionController {

    private static final Logger log = LoggerFactory.getLogger(GraphPermissionController.class);

    private final GraphMenuPermissionResolver resolver;

    public GraphPermissionController(GraphMenuPermissionResolver resolver) {
        this.resolver = resolver;
        log.info("注册 [ace-graph-dsl-web-webflux]DesignerPermissionController（WebFlux /api/graph/permissions）");
    }

    @GetMapping("/menus")
    public MenuPermissionView menus() {
        return resolver.resolve();
    }
}
