package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.autoconfigure.BuiltinGraphRegistry;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 多业务 Graph DSL 目录 API。
 *
 * <p>合并持久化仓库中的用户图与内存中的内置图（{@link BuiltinGraphRegistry}）。</p>
 */
@RestController
@RequestMapping("/catalog")
public class GraphCatalogController {

    private final GraphDefinitionRepository repository;
    private final BuiltinGraphRegistry builtinRegistry;

    public GraphCatalogController(GraphDefinitionRepository repository, BuiltinGraphRegistry builtinRegistry) {
        this.repository = repository;
        this.builtinRegistry = builtinRegistry;
    }

    @GetMapping("/graph-ids")
    public List<String> listGraphIds() {
        List<String> ids = new ArrayList<>(repository.listGraphIds());
        for (GraphDefinition def : builtinRegistry.listAll()) {
            if (!ids.contains(def.graphId())) {
                ids.add(def.graphId());
            }
        }
        return ids;
    }

    /**
     * 内置图排在最前面（bootstrap=true），持久化的图排在后面。
     */
    @GetMapping("/summaries")
    public List<GraphDefinition> listSummaries() {
        List<GraphDefinition> all = new ArrayList<>(builtinRegistry.listAll());
        List<GraphDefinition> persisted = repository.listAll();
        // 过滤掉与内置图同 graphId 的持久化记录（内置图优先）
        for (GraphDefinition def : persisted) {
            if (!builtinRegistry.contains(def.graphId())) {
                all.add(def);
            }
        }
        return all;
    }
}
