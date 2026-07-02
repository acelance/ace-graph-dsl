package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.builder.ValidationResult;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.service.GraphPreviewService;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.SaveDraftRequest;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.store.GraphRuntime;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图定义 REST API：保存草稿、校验、预览、版本管理。
 */
@RestController
@RequestMapping("/definitions")
public class GraphDefinitionController {

    private final GraphDefinitionRepository store;
    private final GraphRuntime runtime;
    private final GraphPreviewService previewService;
    private final GraphMenuPermissionResolver menuPermissions;

    public GraphDefinitionController(GraphDefinitionRepository store, GraphRuntime runtime,
                                     GraphPreviewService previewService, GraphMenuPermissionResolver menuPermissions) {
        this.store = store;
        this.runtime = runtime;
        this.previewService = previewService;
        this.menuPermissions = menuPermissions;
    }

    /** 列出所有图定义（最新版本） */
    @GetMapping
    public List<GraphDefinition> listAll() {
        return store.listAll();
    }

    /** 获取最新版本 */
    @GetMapping("/{graphId}")
    public GraphDefinition getLatest(@PathVariable String graphId) {
        GraphDefinition def = store.loadLatest(graphId);
        if (def == null) {
            throw new IllegalArgumentException("图定义不存在: " + graphId);
        }
        return def;
    }

    /** 获取指定版本 */
    @GetMapping("/{graphId}/versions/{version}")
    public GraphDefinition getVersion(@PathVariable String graphId, @PathVariable String version) {
        GraphDefinition def = store.loadVersion(graphId, version);
        if (def == null) {
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        return def;
    }

    /** 列出所有版本 */
    @GetMapping("/{graphId}/versions")
    public List<GraphDefinition> listVersions(@PathVariable String graphId) {
        return store.listVersions(graphId);
    }

    /** 保存草稿（相对 baseVersion 比对内容；有变更则必须新版本号且不可覆盖历史） */
    @PostMapping("/{graphId}/draft")
    public SaveDraftResult saveDraft(@PathVariable String graphId, @RequestBody SaveDraftRequest body) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_SAVE, "无权保存 Graph 草稿");
        GraphDefinition def = body.definition();
        if (def == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        if (!graphId.equals(def.graphId())) {
            throw new IllegalArgumentException("graphId 不一致: path=" + graphId + ", body=" + def.graphId());
        }
        return store.saveDraft(def, body.baseVersion());
    }

    /** 校验图定义 */
    @PostMapping("/{graphId}/validate")
    public ValidationResult validate(@PathVariable String graphId, @RequestBody GraphDefinition def) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VALIDATE, "无权校验 Graph");
        return runtime.validate(def);
    }

    /** PlantUML 预览 */
    @PostMapping("/{graphId}/preview/plantuml")
    public Map<String, String> previewPlantUml(@PathVariable String graphId, @RequestBody GraphDefinition def) throws GraphStateException {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_PREVIEW, "无权预览 Graph");
        return Map.of("content", previewService.toPlantUml(def));
    }

    /** Mermaid 预览 */
    @PostMapping("/{graphId}/preview/mermaid")
    public Map<String, String> previewMermaid(@PathVariable String graphId, @RequestBody GraphDefinition def) throws GraphStateException {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_PREVIEW, "无权预览 Graph");
        return Map.of("content", previewService.toMermaid(def));
    }

    /** 当前启用版本 */
    @GetMapping("/{graphId}/enabled")
    public GraphDefinition getEnabled(@PathVariable String graphId) {
        GraphDefinition def = store.getEnabled(graphId);
        if (def == null) {
            throw new IllegalStateException("图定义未启用: " + graphId);
        }
        return def;
    }
}
