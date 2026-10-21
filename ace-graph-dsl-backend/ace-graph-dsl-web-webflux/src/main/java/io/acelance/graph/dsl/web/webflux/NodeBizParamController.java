package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.bizparam.NodeBizParamCatalog;
import io.acelance.graph.dsl.bizparam.NodeBizParamItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code NodeBizParamController}。
 */
@RestController
@RequestMapping("/api/node-biz-param-interpreters")
public class NodeBizParamController {

    private static final Logger log = LoggerFactory.getLogger(NodeBizParamController.class);

    private final NodeBizParamCatalog catalog;

    public NodeBizParamController(NodeBizParamCatalog catalog) {
        this.catalog = catalog;
        log.info("注册 [ace-graph-dsl-web-webflux]NodeBizParamController（WebFlux /api/node-biz-param-interpreters）");
    }

    @GetMapping
    public List<NodeBizParamItem> list(@RequestParam(required = false) String graphId) {
        List<NodeBizParamItem> items = new ArrayList<>(catalog.list(graphId));
        items.sort(NodeBizParamCatalog.ORDER);
        log.info("查询 node-biz-param-interpreters: graphId={}, size={}", graphId, items.size());
        return items;
    }
}
