package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.definition.GenericAgentDefinition;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.RemovedAgentFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 通用 agent 节点定义 REST API。
 */
@RestController
@RequestMapping("/agents")
public class GenericAgentNodeController {

    private final GenericAgentNodeService agentNodeService;
    private final GraphNodeAccessControl accessControl;
    private final GraphMenuPermissionResolver menuPermissions;
    private final GraphDefinitionRepository graphDefRepository;
    private final ObjectMapper objectMapper;

    public GenericAgentNodeController(GenericAgentNodeService agentNodeService,
                                      GraphNodeAccessControl accessControl,
                                      GraphMenuPermissionResolver menuPermissions,
                                      GraphDefinitionRepository graphDefRepository,
                                      ObjectMapper objectMapper) {
        this.agentNodeService = agentNodeService;
        this.accessControl = accessControl;
        this.menuPermissions = menuPermissions;
        this.graphDefRepository = graphDefRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/definitions")
    public List<GenericAgentDefinition> listDefinitions() {
        return agentNodeService.listDefinitions();
    }

    @GetMapping("/definitions/{nodeId}")
    public GenericAgentDefinition getDefinition(@PathVariable String nodeId) {
        return agentNodeService.getDefinition(nodeId);
    }

    @GetMapping("/references")
    public List<String> listReferencingGraphs(@RequestParam("nodeId") String nodeId) {
        return graphDefRepository.listAll().stream()
                .filter(def -> def.nodes().stream().anyMatch(n -> nodeId.equals(n.nodeId())))
                .map(GraphDefinition::graphId)
                .collect(Collectors.toList());
    }

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

    @PostMapping
    public GenericAgentDefinition create(@RequestBody JsonNode body) {
        requireManage();
        rejectRemovedFields(body);
        AgentNodeRequest req = objectMapper.convertValue(body, AgentNodeRequest.class);
        return agentNodeService.create(req.toDefinition());
    }

    @PutMapping("/{nodeId}")
    public GenericAgentDefinition update(@PathVariable String nodeId, @RequestBody JsonNode body) {
        requireManage();
        rejectRemovedFields(body);
        AgentNodeRequest req = objectMapper.convertValue(body, AgentNodeRequest.class);
        return agentNodeService.update(nodeId, req.toDefinition());
    }

    @DeleteMapping("/{nodeId}")
    public void delete(@PathVariable String nodeId) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_DELETE, "无权删除通用 agent 节点");
        agentNodeService.delete(nodeId);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody JsonNode body) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_TEST, "无权校验通用 agent 节点");
        rejectRemovedFields(body);
        AgentNodeRequest req = objectMapper.convertValue(body, AgentNodeRequest.class);
        agentNodeService.validateSpec(req.toSpec());
        return Map.of("ok", true);
    }

    @PostMapping("/test-run")
    public Map<String, Object> testRunDraft(@RequestBody JsonNode body) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.AGENT_NODE_TEST, "无权试跑通用 agent 节点");
        rejectRemovedFields(body);
        TestRunDraftRequest req = objectMapper.convertValue(body, TestRunDraftRequest.class);
        Map<String, Object> output = agentNodeService.testRunDraft(req.toSpec(),
                req.mockState() != null ? req.mockState() : Map.of());
        return Map.of("output", output);
    }

    @SuppressWarnings("unchecked")
    private void rejectRemovedFields(JsonNode body) {
        if (body == null || body.isNull()) {
            return;
        }
        Map<String, Object> asMap = objectMapper.convertValue(body, Map.class);
        RemovedAgentFields.assertAbsentInRequest(asMap);
    }

    @PostMapping("/{nodeId}/test-run")
    public Map<String, Object> testRun(@PathVariable String nodeId,
                                       @RequestBody(required = false) TestRunRequest req) {
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
     * 创建/更新请求：优先嵌套 {@code spec}；旧字段若出现非空则 fail fast（D3）。
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
            String inputKeys,
            String outputKey,
            String streamResponseKind,
            List<String> permissionTags,
            String operator,
            // --- D3 已删字段（仅用于检出，不得使用）---
            String promptKey,
            String skillKey,
            String mcpKey,
            List<String> tools,
            String skill,
            String mcp) {

        public GenericAgentSpec toSpec() {
            if (spec != null) {
                return spec;
            }
            return new GenericAgentSpec(
                    modelBaseUrl, modelApiKey, Boolean.TRUE.equals(apiKeyMasked), modelId,
                    prompt, inputKeys, outputKey, streamResponseKind, null,
                    true, List.of(), false, null, false, List.of(),
                    false, List.of(), Map.of(), false, List.of());
        }

        public GenericAgentDefinition toDefinition() {
            return new GenericAgentDefinition(
                    nodeId, displayName, description, version, toSpec(),
                    permissionTags != null ? Set.copyOf(permissionTags) : Set.of(),
                    operator, null, null, true);
        }
    }

    public record TestRunDraftRequest(
            GenericAgentSpec spec,
            String modelBaseUrl,
            String modelApiKey,
            Boolean apiKeyMasked,
            String modelId,
            String prompt,
            String inputKeys,
            String outputKey,
            Map<String, Object> mockState,
            String promptKey,
            String skillKey,
            String mcpKey,
            List<String> tools,
            String skill,
            String mcp) {

        public GenericAgentSpec toSpec() {
            if (spec != null) {
                return spec;
            }
            return new GenericAgentSpec(
                    modelBaseUrl, modelApiKey, Boolean.TRUE.equals(apiKeyMasked), modelId,
                    prompt, inputKeys, outputKey, null, null,
                    true, List.of(), false, null, false, List.of(),
                    false, List.of(), Map.of(), false, List.of());
        }
    }

    public record TestRunRequest(Map<String, Object> mockState) {}
}
