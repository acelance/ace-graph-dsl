package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.security.AccessDeniedException;
import io.acelance.graph.dsl.security.GraphNodeAccessControl;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.service.ScriptNodeService;
import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本（动态）节点 REST API：CRUD、语法校验、试跑。
 *
 * <p>与前端 designer 的脚本节点编辑器契约对齐，相对路径前缀 {@code /nodes}
 * （完整前缀由 {@code ace.graph.dsl.web.base-path} 决定，默认 {@code /api/graph/nodes}）。</p>
 */
@RestController
@RequestMapping("/nodes")
public class ScriptNodeController {

    private final ScriptNodeService scriptNodeService;
    private final GraphNodeAccessControl accessControl;
    private final GraphMenuPermissionResolver menuPermissions;
    private final ScriptEngineRegistry engineRegistry;

    public ScriptNodeController(ScriptNodeService scriptNodeService, GraphNodeAccessControl accessControl,
                               GraphMenuPermissionResolver menuPermissions,
                               ScriptEngineRegistry engineRegistry) {
        this.scriptNodeService = scriptNodeService;
        this.accessControl = accessControl;
        this.menuPermissions = menuPermissions;
        this.engineRegistry = engineRegistry;
    }

    /** 列出可用的脚本引擎 */
    @GetMapping("/engines")
    public List<EngineMeta> listEngines() {
        return engineRegistry.listEngineIds().stream()
                .map(EngineMeta::new)
                .toList();
    }

    /** 列出所有脚本节点定义 */
    @GetMapping("/definitions")
    public List<DynamicNodeDefinition> listDefinitions() {
        return scriptNodeService.listDefinitions();
    }

    /** 获取单个脚本节点定义 */
    @GetMapping("/definitions/{nodeId}")
    public DynamicNodeDefinition getDefinition(@PathVariable String nodeId) {
        return scriptNodeService.getDefinition(nodeId);
    }

    /** 创建脚本节点 */
    @PostMapping
    public DynamicNodeDefinition create(@RequestBody ScriptNodeRequest req) {
        requireManage();
        return scriptNodeService.create(req.toDefinition());
    }

    /** 更新脚本节点 */
    @PutMapping("/{nodeId}")
    public DynamicNodeDefinition update(@PathVariable String nodeId, @RequestBody ScriptNodeRequest req) {
        requireManage();
        return scriptNodeService.update(nodeId, req.toDefinition());
    }

    /** 删除脚本节点 */
    @DeleteMapping("/{nodeId}")
    public Map<String, Object> delete(@PathVariable String nodeId) {
        if (!accessControl.canDeleteScriptNodes()) {
            throw new AccessDeniedException("无权删除脚本节点");
        }
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.SCRIPT_NODE_DELETE, "无权删除脚本节点");
        scriptNodeService.delete(nodeId);
        return Map.of("success", true, "nodeId", nodeId);
    }

    /** 校验脚本语法 */
    @PostMapping("/validate-script")
    public Map<String, Object> validateScript(@RequestBody ValidateScriptRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.SCRIPT_NODE_TEST, "无权校验脚本");
        scriptNodeService.validateScript(req.engine(), req.scriptBody());
        return Map.of("valid", true);
    }

    /** 试跑草稿脚本 */
    @PostMapping("/test-run")
    public Map<String, Object> testRunDraft(@RequestBody TestRunDraftRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.SCRIPT_NODE_TEST, "无权试跑脚本");
        Map<String, Object> output = scriptNodeService.testRunDraft(
                req.engine(), req.scriptBody(),
                toSet(req.inputKeys()), toSet(req.outputKeys()),
                req.mockState(), req.config());
        return Map.of("output", output);
    }

    /** 基于已存定义试跑 */
    @PostMapping("/{nodeId}/test-run")
    public Map<String, Object> testRun(@PathVariable String nodeId, @RequestBody TestRunRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.SCRIPT_NODE_TEST, "无权试跑脚本");
        Map<String, Object> output = scriptNodeService.testRun(
                nodeId,
                req != null ? req.mockState() : Map.of(),
                req != null ? req.config() : Map.of());
        return Map.of("output", output);
    }

    private void requireManage() {
        if (!accessControl.canManageScriptNodes()) {
            throw new AccessDeniedException("无权管理脚本节点");
        }
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.SCRIPT_NODE_CREATE, "无权管理脚本节点");
    }

    private static Set<String> toSet(List<String> list) {
        return list != null ? Set.copyOf(list) : Set.of();
    }

    /** 脚本节点创建/更新请求体 */
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

    /** 脚本语法校验请求体 */
    public record ValidateScriptRequest(String engine, String scriptBody) {}

    /** 草稿试跑请求体 */
    public record TestRunDraftRequest(
            String engine,
            String scriptBody,
            List<String> inputKeys,
            List<String> outputKeys,
            Map<String, Object> mockState,
            Map<String, Object> config) {}

    /** 已存节点试跑请求体 */
    public record TestRunRequest(Map<String, Object> mockState, Map<String, Object> config) {}

    /** 引擎元数据（供前端下拉选择） */
    public record EngineMeta(String id, String label) {
        public EngineMeta(String id) {
            this(id, toLabel(id));
        }

        private static String toLabel(String id) {
            return switch (id) {
                case "aviator" -> "Aviator 表达式";
                case "spel" -> "SpEL 表达式";
                default -> id;
            };
        }
    }
}
