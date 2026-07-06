package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.autoconfigure.BuiltinGraphRegistry;
import io.acelance.graph.dsl.builder.ValidationResult;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.SaveDraftRequest;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.service.GraphPreviewService;
import io.acelance.graph.dsl.store.GraphRuntime;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图定义 REST API：保存草稿、校验、预览、版本管理。
 *
 * <p>查询接口会自动回退到 {@link BuiltinGraphRegistry} 中的内存内置图。</p>
 */
@RestController
@RequestMapping("/definitions")
public class GraphDefinitionController {

    private final GraphDefinitionRepository store;
    private final GraphRuntime runtime;
    private final GraphPreviewService previewService;
    private final GraphMenuPermissionResolver menuPermissions;
    private final BuiltinGraphRegistry builtinRegistry;

    public GraphDefinitionController(GraphDefinitionRepository store, GraphRuntime runtime,
                                     GraphPreviewService previewService, GraphMenuPermissionResolver menuPermissions,
                                     BuiltinGraphRegistry builtinRegistry) {
        this.store = store;
        this.runtime = runtime;
        this.previewService = previewService;
        this.menuPermissions = menuPermissions;
        this.builtinRegistry = builtinRegistry;
    }

    /** 列出所有图定义（最新版本），合并内置图 */
    @GetMapping
    public List<GraphDefinition> listAll() {
        List<GraphDefinition> all = new ArrayList<>(builtinRegistry.listAll());
        for (GraphDefinition def : store.listAll()) {
            if (!builtinRegistry.contains(def.graphId())) {
                all.add(def);
            }
        }
        return all;
    }

    /** 获取最新版本，内置图回退到内存 */
    @GetMapping("/{graphId}")
    public GraphDefinition getLatest(@PathVariable String graphId) {
        GraphDefinition builtin = builtinRegistry.get(graphId);
        if (builtin != null) return builtin;
        GraphDefinition def = store.loadLatest(graphId);
        if (def == null) {
            throw new IllegalArgumentException("图定义不存在: " + graphId);
        }
        return def;
    }

    /** 获取指定版本，内置图回退到内存（仅 1.0.0） */
    @GetMapping("/{graphId}/versions/{version}")
    public GraphDefinition getVersion(@PathVariable String graphId, @PathVariable String version) {
        GraphDefinition builtin = builtinRegistry.get(graphId);
        if (builtin != null) {
            if (builtin.version().equals(version)) return builtin;
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        GraphDefinition def = store.loadVersion(graphId, version);
        if (def == null) {
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        return def;
    }

    /** 列出所有版本，内置图仅返回当前版本 */
    @GetMapping("/{graphId}/versions")
    public List<GraphDefinition> listVersions(@PathVariable String graphId) {
        GraphDefinition builtin = builtinRegistry.get(graphId);
        if (builtin != null) return List.of(builtin);
        return store.listVersions(graphId);
    }

    /** 保存草稿 — 内置图拒绝写入 */
    @PostMapping("/{graphId}/draft")
    public SaveDraftResult saveDraft(@PathVariable String graphId, @RequestBody SaveDraftRequest body) {
        if (builtinRegistry.contains(graphId)) {
            throw new IllegalArgumentException("内置图不允许编辑: " + graphId);
        }
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

    /** 当前启用版本，内置图回退到内存 */
    @GetMapping("/{graphId}/enabled")
    public GraphDefinition getEnabled(@PathVariable String graphId) {
        GraphDefinition builtin = builtinRegistry.get(graphId);
        if (builtin != null) return builtin;
        GraphDefinition def = store.getEnabled(graphId);
        if (def == null) {
            throw new IllegalStateException("图定义未启用: " + graphId);
        }
        return def;
    }
}
