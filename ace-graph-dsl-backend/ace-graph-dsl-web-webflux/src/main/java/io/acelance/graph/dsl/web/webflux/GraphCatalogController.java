package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.autoconfigure.BuiltinGraphRegistry;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code GraphCatalogController}：{@code /api/graph/catalog/**}。
 */
@RestController
@RequestMapping("/api/graph/catalog")
public class GraphCatalogController {

    private static final Logger log = LoggerFactory.getLogger(GraphCatalogController.class);

    private final GraphDefinitionRepository repository;
    private final ObjectProvider<BuiltinGraphRegistry> builtinRegistries;

    public GraphCatalogController(GraphDefinitionRepository repository,
                                             ObjectProvider<BuiltinGraphRegistry> builtinRegistries) {
        this.repository = repository;
        this.builtinRegistries = builtinRegistries;
        log.info("注册 [ace-graph-dsl-web-webflux]DesignerCatalogController（WebFlux /api/graph/catalog）");
    }

    @GetMapping("/graph-ids")
    public List<String> listGraphIds() {
        BuiltinGraphRegistry builtins = builtins();
        List<String> ids = new ArrayList<>(repository.listGraphIds());
        for (GraphDefinition def : builtins.listAll()) {
            if (!ids.contains(def.graphId())) {
                ids.add(def.graphId());
            }
        }
        return ids;
    }

    @GetMapping("/summaries")
    public List<GraphDefinition> listSummaries() {
        BuiltinGraphRegistry builtins = builtins();
        List<GraphDefinition> all = new ArrayList<>(builtins.listAll());
        for (GraphDefinition def : repository.listAll()) {
            if (!builtins.contains(def.graphId())) {
                all.add(def);
            }
        }
        return all;
    }

    private BuiltinGraphRegistry builtins() {
        BuiltinGraphRegistry reg = builtinRegistries.getIfAvailable();
        return reg != null ? reg : new BuiltinGraphRegistry();
    }
}
