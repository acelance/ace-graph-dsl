package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.streamkind.StreamResponseKindCatalog;
import io.acelance.graph.dsl.streamkind.StreamResponseKindItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 流式响应类型目录 API（P0.4）：供设计器下拉与编译期 normalize 对齐。
 */
@RestController
@RequestMapping("/api/stream-response-kinds")
public class StreamResponseKindController {

    private static final Logger log = LoggerFactory.getLogger(StreamResponseKindController.class);

    private final StreamResponseKindCatalog catalog;
    private final GraphMenuPermissionResolver menuPermissions;

    public StreamResponseKindController(StreamResponseKindCatalog catalog,
                                        GraphMenuPermissionResolver menuPermissions) {
        this.catalog = catalog;
        this.menuPermissions = menuPermissions;
    }

    /**
     * 列出可用流式类型（按 order 排序）。
     *
     * @param graphId 可选；部分业务 Catalog 按图裁剪
     */
    @GetMapping
    public List<StreamResponseKindItem> list(@RequestParam(required = false) String graphId) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VIEW, "无权查看流式类型目录");
        List<StreamResponseKindItem> items = new ArrayList<>(catalog.list(graphId));
        items.sort(Comparator.comparingInt(StreamResponseKindItem::order)
                .thenComparing(StreamResponseKindItem::code, Comparator.nullsLast(String::compareTo)));
        log.info("查询 stream-response-kinds: graphId={}, size={}", graphId, items.size());
        return items;
    }
}
