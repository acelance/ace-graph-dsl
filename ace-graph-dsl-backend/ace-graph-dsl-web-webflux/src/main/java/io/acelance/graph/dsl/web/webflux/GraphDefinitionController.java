package io.acelance.graph.dsl.web.webflux;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import io.acelance.graph.dsl.autoconfigure.BuiltinGraphRegistry;
import io.acelance.graph.dsl.builder.DynamicGraphBuilder;
import io.acelance.graph.dsl.builder.ValidationResult;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.SaveDraftRequest;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.resource.ResourceKeyValidationGate;
import io.acelance.graph.dsl.resource.ResourceKeyValidator;
import io.acelance.graph.dsl.service.GraphPreviewService;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code GraphDefinitionController}：路径 {@code /api/graph/definitions/**}。
 */
@RestController
@RequestMapping("/api/graph/definitions")
public class GraphDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(GraphDefinitionController.class);

    private final GraphDefinitionRepository store;
    private final GraphRuntime runtime;
    private final ObjectProvider<GraphPreviewService> previewServices;
    private final ObjectProvider<BuiltinGraphRegistry> builtinRegistries;
    private final ObjectProvider<DynamicGraphBuilder> builders;
    private final ObjectProvider<ResourceKeyValidator> resourceKeyValidators;

    public GraphDefinitionController(GraphDefinitionRepository store,
                                                GraphRuntime runtime,
                                                ObjectProvider<GraphPreviewService> previewServices,
                                                ObjectProvider<BuiltinGraphRegistry> builtinRegistries,
                                                ObjectProvider<DynamicGraphBuilder> builders,
                                                ObjectProvider<ResourceKeyValidator> resourceKeyValidators) {
        this.store = store;
        this.runtime = runtime;
        this.previewServices = previewServices;
        this.builtinRegistries = builtinRegistries;
        this.builders = builders;
        this.resourceKeyValidators = resourceKeyValidators;
        log.info("注册 [ace-graph-dsl-web-webflux]DesignerDefinitionController（WebFlux /api/graph/definitions）");
    }

    @GetMapping
    public List<GraphDefinition> listAll() {
        BuiltinGraphRegistry builtins = builtins();
        List<GraphDefinition> all = new ArrayList<>(builtins.listAll());
        for (GraphDefinition def : store.listAll()) {
            if (!builtins.contains(def.graphId())) {
                all.add(def);
            }
        }
        return all;
    }

    @GetMapping("/{graphId}")
    public GraphDefinition getLatest(@PathVariable String graphId) {
        GraphDefinition builtin = builtins().get(graphId);
        if (builtin != null) {
            return builtin;
        }
        GraphDefinition def = store.loadLatest(graphId);
        if (def == null) {
            throw new IllegalArgumentException("图定义不存在: " + graphId);
        }
        return def;
    }

    @GetMapping("/{graphId}/versions/{version}")
    public GraphDefinition getVersion(@PathVariable String graphId, @PathVariable String version) {
        GraphDefinition builtin = builtins().get(graphId);
        if (builtin != null) {
            if (builtin.version().equals(version)) {
                return builtin;
            }
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        GraphDefinition def = store.loadVersion(graphId, version);
        if (def == null) {
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        return def;
    }

    @GetMapping("/{graphId}/versions")
    public List<GraphDefinition> listVersions(@PathVariable String graphId) {
        GraphDefinition builtin = builtins().get(graphId);
        if (builtin != null) {
            return List.of(builtin);
        }
        return store.listVersions(graphId);
    }

    @PostMapping("/{graphId}/draft")
    public SaveDraftResult saveDraft(@PathVariable String graphId, @RequestBody SaveDraftRequest body) {
        if (builtins().contains(graphId)) {
            throw new IllegalArgumentException("内置图不允许编辑: " + graphId);
        }
        GraphDefinition def = body.definition();
        if (def == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        if (!graphId.equals(def.graphId())) {
            throw new IllegalArgumentException("graphId 不一致: path=" + graphId + ", body=" + def.graphId());
        }
        ResourceKeyValidationGate.assertValidGraph(resourceKeyValidators.getIfAvailable(), null, def);
        return store.saveDraft(def, body.baseVersion());
    }

    @PostMapping("/{graphId}/validate")
    public ValidationResult validate(@PathVariable String graphId, @RequestBody GraphDefinition def) {
        return runtime.validate(def);
    }

    @PostMapping("/{graphId}/preview/plantuml")
    public Map<String, String> previewPlantUml(@PathVariable String graphId, @RequestBody GraphDefinition def)
            throws GraphStateException {
        GraphPreviewService preview = previewServices.getIfAvailable();
        if (preview == null) {
            throw new IllegalStateException("GraphPreviewService 不可用");
        }
        return Map.of("content", preview.toPlantUml(def));
    }

    @PostMapping("/{graphId}/preview/mermaid")
    public Map<String, String> previewMermaid(@PathVariable String graphId, @RequestBody GraphDefinition def)
            throws GraphStateException {
        GraphPreviewService preview = previewServices.getIfAvailable();
        if (preview == null) {
            throw new IllegalStateException("GraphPreviewService 不可用");
        }
        return Map.of("content", preview.toMermaid(def));
    }

    @GetMapping("/{graphId}/enabled")
    public GraphDefinition getEnabled(@PathVariable String graphId) {
        GraphDefinition builtin = builtins().get(graphId);
        if (builtin != null) {
            return builtin;
        }
        GraphDefinition def = store.getEnabled(graphId);
        if (def == null) {
            throw new IllegalStateException("图定义未启用: " + graphId);
        }
        return def;
    }

    @PostMapping("/{graphId}/dry-run")
    public Map<String, Object> dryRun(@PathVariable String graphId, @RequestBody DryRunRequest req) {
        GraphDefinition def = req.definition();
        if (def == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        DynamicGraphBuilder builder = builders.getIfAvailable();
        if (builder == null) {
            return Map.of("error", "DynamicGraphBuilder 不可用");
        }
        try {
            CompiledGraph compiled = builder.build(def);
            String threadId = UUID.randomUUID().toString();
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            Map<String, Object> inputs = req.inputs() != null ? req.inputs() : Map.of();
            List<Map<String, Object>> trace = new ArrayList<>();
            Flux<NodeOutput> flux = compiled.stream(inputs, config);
            flux.doOnNext(out -> {
                Map<String, Object> state = out.state() != null ? out.state().data() : Map.of();
                trace.add(Map.of("node", out.node(), "state", state));
            }).blockLast();
            @SuppressWarnings("unchecked")
            Map<String, Object> finalState = trace.isEmpty()
                    ? Map.of()
                    : (Map<String, Object>) trace.get(trace.size() - 1).get("state");
            return Map.of("trace", trace, "finalState", finalState);
        }
        catch (Exception e) {
            return Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @PostMapping("/{graphId}/publish")
    public GraphRuntime.PublishResult publish(@PathVariable String graphId, @RequestBody PublishRequest req) {
        return runtime.publish(graphId, req.version(), req.operator());
    }

    @PostMapping("/{graphId}/rollback")
    public GraphRuntime.PublishResult rollback(@PathVariable String graphId, @RequestBody PublishRequest req) {
        return runtime.rollback(graphId, req.version(), req.operator());
    }

    private BuiltinGraphRegistry builtins() {
        BuiltinGraphRegistry reg = builtinRegistries.getIfAvailable();
        return reg != null ? reg : EMPTY_BUILTINS;
    }

    private static final BuiltinGraphRegistry EMPTY_BUILTINS = new BuiltinGraphRegistry();

    public record DryRunRequest(GraphDefinition definition, Map<String, Object> inputs) {}

    public record PublishRequest(String version, String operator) {}
}
