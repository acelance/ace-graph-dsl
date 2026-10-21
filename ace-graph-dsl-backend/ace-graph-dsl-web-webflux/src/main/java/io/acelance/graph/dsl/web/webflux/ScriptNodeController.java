package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.script.ScriptEngineDescriptor;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.service.ScriptNodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code ScriptNodeController}：{@code /api/graph/nodes/**}。
 *
 * <p>产品 WebFlux 设计器 API（{@code ace.graph.dsl.web.enabled} + REACTIVE）；对齐 Servlet 版脚本 API；
 * 设计器「校验语法 / 试跑 / 创建」依赖本镜像，缺省会 404。</p>
 */
@RestController
@RequestMapping("/api/graph/nodes")
public class ScriptNodeController {

    private static final Logger log = LoggerFactory.getLogger(ScriptNodeController.class);

    private final ObjectProvider<ScriptNodeService> scriptNodeServices;
    private final ObjectProvider<ScriptEngineRegistry> engineRegistries;
    private final GraphDefinitionRepository graphDefRepository;

    public ScriptNodeController(ObjectProvider<ScriptNodeService> scriptNodeServices,
                                                ObjectProvider<ScriptEngineRegistry> engineRegistries,
                                                GraphDefinitionRepository graphDefRepository) {
        this.scriptNodeServices = scriptNodeServices;
        this.engineRegistries = engineRegistries;
        this.graphDefRepository = graphDefRepository;
        log.info("注册 [ace-graph-dsl-web-webflux]DesignerScriptNodeController（WebFlux /api/graph/nodes 读写+校验+试跑）");
    }

    @GetMapping("/engines")
    public List<ScriptEngineDescriptor> listEngines() {
        ScriptEngineRegistry registry = engineRegistries.getIfAvailable();
        return registry == null ? List.of() : registry.listDescriptors();
    }

    @GetMapping("/definitions")
    public List<DynamicNodeDefinition> listDefinitions() {
        return requireService().listDefinitions();
    }

    @GetMapping("/definitions/{nodeId}")
    public DynamicNodeDefinition getDefinition(@PathVariable String nodeId) {
        return requireService().getDefinition(nodeId);
    }

    @GetMapping("/references")
    public List<String> listReferencingGraphs(@RequestParam("nodeId") String nodeId) {
        return graphDefRepository.listAll().stream()
                .filter(def -> def.nodes().stream().anyMatch(n -> nodeId.equals(n.nodeId())))
                .map(GraphDefinition::graphId)
                .collect(Collectors.toList());
    }

    @GetMapping("/orphans")
    public List<DynamicNodeDefinition> listOrphans() {
        ScriptNodeService svc = requireService();
        Set<String> referencedNodeIds = graphDefRepository.listAll().stream()
                .flatMap(def -> def.nodes().stream())
                .map(n -> n.nodeId())
                .collect(Collectors.toSet());
        return svc.listDefinitions().stream()
                .filter(def -> !referencedNodeIds.contains(def.nodeId()))
                .toList();
    }

    /** 创建脚本节点（设计器「创建」按钮）。 */
    @PostMapping
    public DynamicNodeDefinition create(@RequestBody ScriptNodeRequest req) {
        log.info("[designer-script] 创建脚本节点 nodeId={} engine={}",
                req == null ? null : req.nodeId(), req == null ? null : req.engine());
        return requireService().create(req.toDefinition());
    }

    /** 更新脚本节点。 */
    @PutMapping("/{nodeId}")
    public DynamicNodeDefinition update(@PathVariable String nodeId, @RequestBody ScriptNodeRequest req) {
        log.info("[designer-script] 更新脚本节点 nodeId={} engine={}", nodeId, req == null ? null : req.engine());
        return requireService().update(nodeId, req.toDefinition());
    }

    /** 删除脚本节点。 */
    @DeleteMapping("/{nodeId}")
    public Map<String, Object> delete(@PathVariable String nodeId) {
        log.info("[designer-script] 删除脚本节点 nodeId={}", nodeId);
        requireService().delete(nodeId);
        return Map.of("success", true, "nodeId", nodeId);
    }

    /** 校验脚本语法（设计器「校验语法」→ POST /validate-script）。 */
    @PostMapping("/validate-script")
    public Map<String, Object> validateScript(@RequestBody ValidateScriptRequest req) {
        log.info("[designer-script] 校验语法 engine={} bodyChars={}",
                req == null ? null : req.engine(),
                req == null || req.scriptBody() == null ? 0 : req.scriptBody().length());
        requireService().validateScript(req.engine(), req.scriptBody());
        return Map.of("valid", true);
    }

    /** 试跑草稿脚本（设计器「试跑」→ POST /test-run）。 */
    @PostMapping("/test-run")
    public Map<String, Object> testRunDraft(@RequestBody TestRunDraftRequest req) {
        log.info("[designer-script] 试跑草稿 engine={} inputKeys={} outputKeys={}",
                req == null ? null : req.engine(),
                req == null ? null : req.inputKeys(),
                req == null ? null : req.outputKeys());
        Map<String, Object> output = requireService().testRunDraft(
                req.engine(),
                req.scriptBody(),
                toSet(req.inputKeys()),
                toSet(req.outputKeys()),
                req.mockState(),
                req.config());
        return Map.of("output", output);
    }

    /** 基于已存定义试跑。 */
    @PostMapping("/{nodeId}/test-run")
    public Map<String, Object> testRun(@PathVariable String nodeId, @RequestBody(required = false) TestRunRequest req) {
        log.info("[designer-script] 试跑已存节点 nodeId={}", nodeId);
        Map<String, Object> output = requireService().testRun(
                nodeId,
                req != null ? req.mockState() : Map.of(),
                req != null ? req.config() : Map.of());
        return Map.of("output", output);
    }

    private ScriptNodeService requireService() {
        ScriptNodeService svc = scriptNodeServices.getIfAvailable();
        if (svc == null) {
            log.error("[designer-script] ScriptNodeService 不可用");
            throw new IllegalStateException("ScriptNodeService 不可用");
        }
        return svc;
    }

    private static Set<String> toSet(List<String> list) {
        return list != null ? Set.copyOf(list) : Set.of();
    }

    /** 脚本节点创建/更新请求体（对齐产品 ScriptNodeController）。 */
    public record ScriptNodeRequest(
            String nodeId,
            String displayName,
            String category,
            String description,
            List<String> inputKeys,
            List<String> outputKeys,
            Boolean supportsParallel,
            String version,
            String engine,
            String scriptBody,
            List<String> permissionTags,
            String operator) {

        public DynamicNodeDefinition toDefinition() {
            return new DynamicNodeDefinition(
                    nodeId,
                    displayName,
                    category,
                    description,
                    toSet(inputKeys),
                    toSet(outputKeys),
                    Boolean.TRUE.equals(supportsParallel),
                    version,
                    engine,
                    scriptBody,
                    null,
                    null,
                    null,
                    toSet(permissionTags),
                    operator,
                    null,
                    null,
                    true);
        }
    }

    public record ValidateScriptRequest(String engine, String scriptBody) {}

    public record TestRunDraftRequest(
            String engine,
            String scriptBody,
            List<String> inputKeys,
            List<String> outputKeys,
            Map<String, Object> mockState,
            Map<String, Object> config) {}

    public record TestRunRequest(Map<String, Object> mockState, Map<String, Object> config) {}
}
