package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图发布与回滚 REST API。
 */
@RestController
@RequestMapping("/definitions")
public class GraphPublishController {

    private final GraphRuntime runtime;
    private final GraphMenuPermissionResolver menuPermissions;

    public GraphPublishController(GraphRuntime runtime, GraphMenuPermissionResolver menuPermissions) {
        this.runtime = runtime;
        this.menuPermissions = menuPermissions;
    }

    /** 发布版本 */
    @PostMapping("/{graphId}/publish")
    public GraphRuntime.PublishResult publish(
            @PathVariable String graphId,
            @RequestBody PublishRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_PUBLISH, "无权发布 Graph");
        return runtime.publish(graphId, req.version(), req.operator());
    }

    /** 回滚到指定版本 */
    @PostMapping("/{graphId}/rollback")
    public GraphRuntime.PublishResult rollback(
            @PathVariable String graphId,
            @RequestBody PublishRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_ROLLBACK, "无权回滚 Graph");
        return runtime.rollback(graphId, req.version(), req.operator());
    }

    /** 发布请求体 */
    public record PublishRequest(String version, String operator) {}
}
