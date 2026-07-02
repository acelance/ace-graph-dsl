package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多业务 Graph DSL 目录 API。
 */
@RestController
@RequestMapping("/catalog")
public class GraphCatalogController {

    private final GraphDefinitionRepository repository;

    public GraphCatalogController(GraphDefinitionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/graph-ids")
    public List<String> listGraphIds() {
        return repository.listGraphIds();
    }

    @GetMapping("/summaries")
    public List<GraphDefinition> listSummaries() {
        return repository.listAll();
    }
}
