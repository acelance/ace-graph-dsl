package io.acelance.graph.dsl.web.webflux;

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
 * [ace-graph-dsl-web-webflux]产品 {@code StreamResponseKindController}。
 */
@RestController
@RequestMapping("/api/stream-response-kinds")
public class StreamResponseKindController {

    private static final Logger log = LoggerFactory.getLogger(StreamResponseKindController.class);

    private final StreamResponseKindCatalog catalog;

    public StreamResponseKindController(StreamResponseKindCatalog catalog) {
        this.catalog = catalog;
        log.info("注册 [ace-graph-dsl-web-webflux]StreamResponseKindController（WebFlux /api/stream-response-kinds）");
    }

    @GetMapping
    public List<StreamResponseKindItem> list(@RequestParam(required = false) String graphId) {
        List<StreamResponseKindItem> items = new ArrayList<>(catalog.list(graphId));
        items.sort(Comparator.comparingInt(StreamResponseKindItem::order)
                .thenComparing(StreamResponseKindItem::code, Comparator.nullsLast(String::compareTo)));
        log.info("查询 stream-response-kinds: graphId={}, size={}", graphId, items.size());
        return items;
    }
}
