package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.bizparam.NodeBizParamCatalog;
import io.acelance.graph.dsl.bizparam.NodeBizParamItem;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点业务附加参数解释器目录 API，供设计器下拉选择。
 */
@RestController
@RequestMapping("/api/node-biz-param-interpreters")
public class NodeBizParamController {

    private static final Logger log = LoggerFactory.getLogger(NodeBizParamController.class);

    private final NodeBizParamCatalog catalog;
    private final GraphMenuPermissionResolver menuPermissions;

    public NodeBizParamController(NodeBizParamCatalog catalog,
                                  GraphMenuPermissionResolver menuPermissions) {
        this.catalog = catalog;
        this.menuPermissions = menuPermissions;
    }

    @GetMapping
    public List<NodeBizParamItem> list(@RequestParam(required = false) String graphId) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VIEW, "无权查看业务附加参数解释器目录");
        List<NodeBizParamItem> items = new ArrayList<>(catalog.list(graphId));
        items.sort(NodeBizParamCatalog.ORDER);
        log.info("查询 node-biz-param-interpreters: graphId={}, size={}", graphId, items.size());
        return items;
    }
}
