package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.definition.GenericAgentDefinition;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.security.AccessDeniedException;
import io.acelance.graph.dsl.security.GraphNodeAccessControl;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.service.GenericAgentNodeService;
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
 * 通用 agent 节点定义 REST API：CRUD、元数据校验、试跑。
 *
 * <p>与脚本节点的 {@code /nodes} 完全对称，相对路径前缀 {@code /agents}
 * （完整前缀由 {@code ace.graph.dsl.web.base-path} 决定，默认 {@code /api/graph/agents}）。</p>
 */
@RestController
@RequestMapping("/agents")
public class GenericAgentNodeController {

    private final GenericAgentNodeService agentNodeService;
    private final GraphNodeAccessControl accessControl;
    private final GraphMenuPermissionResolver menuPermissions;
    private final GraphDefinitionRepository graphDefRepository;

    public GenericAgentNodeController(GenericAgentNodeService agentNodeService,
                                      GraphNodeAccessControl accessControl,
                                      GraphMenuPermissionResolver menuPermissions,
                                      GraphDefinitionRepository graphDefRepository) {
        this.agentNodeService = agentNodeService;
        this.accessControl = accessControl;
        this.menuPermissions = menuPermissions;
        this.graphDefRepository = graphDefRepository;
    }

    /** 列出所有 agent 节点定义 */
    @GetMapping("/definitions")
    public List<GenericAgentDefinition> listDefinitions() {
        return agentNodeService.listDefinitions();
    }

    /** 获取单个 agent 节点定义 */
    @GetMapping("/definitions/{nodeId}")
    public GenericAgentDefinition getDefinition(@PathVariable String nodeId) {
        return agentNodeService.getDefinition(nodeId);
    }

    /** 查询引用指定 agent 节点的图 ID 列表（删除前的引用检查） */
    @GetMapping("/references")
    public List<String> listReferencingGraphs(@RequestParam("nodeId") String nodeId) {
        return graphDefRepository.listAll().stream()
                .filter(def -> def.nodes().stream().anyMatch(n -> nodeId.equals(n.nodeId())))
                .map(GraphDefinition::graphId)
                .collect(Collectors.toList());
    }

    /** 列出孤儿 agent 节点（未被任何图定义引用） */
    @GetMapping("/orphans")
    public List<GenericAgentDefinition> listOrphans() {
        Set<String> referenced = graphDefRepository.listAll().stream()
                .flatMap(def -> def.nodes().stream())
                .map(n -> n.nodeId())
                .collect(Collectors.toSet());
        return agentNodeService.listDefinitions().stream()
                .filter(def -> !referenced.contains(def.nodeId()))
                .toList();
    }

    /** 创建 agent 节点定义 */
    @PostMapping
    public GenericAgentDefinition create(@RequestBody AgentNodeRequest req) {
        requireManage();
        return agentNodeService.create(req.toDefinition());
    }

    /** 更新 agent 节点定义 */
    @PutMapping("/{nodeId}")
    public GenericAgentDefinition update(@PathVariable String nodeId, @RequestBody AgentNodeRequest req) {
        requireManage();
        return agentNodeService.update(nodeId, req.toDefinition());
    }

    /** 删除 agent 节点定义 */
    @DeleteMapping("/{nodeId}")
    public Map<String, Object> delete(@PathVariable String nodeId) {
        if (!accessControl.canDeleteAgentNodes()) {
            throw new AccessDeniedException("无权删除通用 agent 节点");
        }
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_DELETE, "无权删除通用 agent 节点");
        agentNodeService.delete(nodeId);
        return Map.of("success", true, "nodeId", nodeId);
    }

    /** 校验 agent 元数据（保存前的即时反馈） */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody AgentNodeRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_TEST, "无权校验通用 agent 节点");
        agentNodeService.validateSpec(req.toSpec());
        return Map.of("valid", true);
    }

    /** 试跑草稿元数据（未持久化） */
    @PostMapping("/test-run")
    public Map<String, Object> testRunDraft(@RequestBody TestRunDraftRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_TEST, "无权试跑通用 agent 节点");
        Map<String, Object> output = agentNodeService.testRunDraft(req.toSpec(), req.mockState());
        return Map.of("output", output);
    }

    /** 基于已存定义试跑 */
    @PostMapping("/{nodeId}/test-run")
    public Map<String, Object> testRun(@PathVariable String nodeId, @RequestBody(required = false) TestRunRequest req) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_TEST, "无权试跑通用 agent 节点");
        Map<String, Object> output = agentNodeService.testRun(
                nodeId, req != null ? req.mockState() : Map.of());
        return Map.of("output", output);
    }

    private void requireManage() {
        if (!accessControl.canManageAgentNodes()) {
            throw new AccessDeniedException("无权管理通用 agent 节点");
        }
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_CREATE, "无权管理通用 agent 节点");
    }

    /**
     * agent 节点创建 / 更新请求体。
     *
     * <p>前端可平铺提交 12 个元数据字段（与 PropertyPanel 内联编辑器同名），
     * 也可整体提交 {@code spec} 对象；两者同时存在时以 {@code spec} 为准。</p>
     */
    public record AgentNodeRequest(
            String nodeId,
            String displayName,
            String description,
            String version,
            GenericAgentSpec spec,
            String modelBaseUrl,
            String modelApiKey,
            Boolean apiKeyMasked,
            String modelId,
            String prompt,
            String promptKey,
            String skill,
            String skillKey,
            String mcp,
            String mcpKey,
            List<String> tools,
            String inputKeys,
            String outputKey,
            List<String> permissionTags,
            String operator) {

        public GenericAgentSpec toSpec() {
            if (spec != null) {
                return spec;
            }
            return new GenericAgentSpec(
                    modelBaseUrl, modelApiKey, Boolean.TRUE.equals(apiKeyMasked), modelId,
                    prompt, promptKey, skill, skillKey, mcp, mcpKey,
                    tools != null ? tools : List.of(), inputKeys, outputKey);
        }

        public GenericAgentDefinition toDefinition() {
            return new GenericAgentDefinition(
                    nodeId,
                    displayName,
                    description,
                    version,
                    toSpec(),
                    permissionTags != null ? Set.copyOf(permissionTags) : Set.of(),
                    operator,
                    null,
                    null,
                    true);
        }
    }

    /** 草稿试跑请求体 */
    public record TestRunDraftRequest(
            GenericAgentSpec spec,
            String modelBaseUrl,
            String modelApiKey,
            Boolean apiKeyMasked,
            String modelId,
            String prompt,
            String promptKey,
            String skill,
            String skillKey,
            String mcp,
            String mcpKey,
            List<String> tools,
            String inputKeys,
            String outputKey,
            Map<String, Object> mockState) {

        public GenericAgentSpec toSpec() {
            if (spec != null) {
                return spec;
            }
            return new GenericAgentSpec(
                    modelBaseUrl, modelApiKey, Boolean.TRUE.equals(apiKeyMasked), modelId,
                    prompt, promptKey, skill, skillKey, mcp, mcpKey,
                    tools != null ? tools : List.of(), inputKeys, outputKey);
        }
    }

    /** 已存定义试跑请求体 */
    public record TestRunRequest(Map<String, Object> mockState) {}
}
