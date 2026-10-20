package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.resource.AgentResourceCatalog;
import io.acelance.graph.dsl.resource.ResourceItem;
import io.acelance.graph.dsl.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 设计期资源 Catalog API（WebFlux）。
 *
 * <p>路径与响应形状对齐产品 Servlet {@code AgentResourceCatalogController}：
 * {@code GET /api/agent-resources/{prompts|models|tools|mcp|skills}}，
 * query 含 {@code otherBizParams}（optional）。首期不接 MenuPermissionGuard。</p>
 */
@RestController
@RequestMapping("/api/agent-resources")
public class AgentResourceCatalogController {

    private static final Logger log = LoggerFactory.getLogger(AgentResourceCatalogController.class);

    private final Map<ResourceType, AgentResourceCatalog> catalogs;

    public AgentResourceCatalogController(List<AgentResourceCatalog> catalogList) {
        this.catalogs = new EnumMap<>(ResourceType.class);
        if (catalogList != null) {
            for (AgentResourceCatalog c : catalogList) {
                if (c != null && c.type() != null) {
                    catalogs.put(c.type(), c);
                }
            }
        }
        log.info("[ace-graph-dsl-web-webflux] AgentResourceCatalog 已注册: {}", catalogs.keySet());
    }

    @GetMapping("/prompts")
    public Map<String, Object> prompts(@RequestParam(required = false) String agentCode,
                                       @RequestParam(required = false) String graphId,
                                       @RequestParam(required = false) String agentDefId,
                                       @RequestParam(required = false) String otherBizParams) {
        return list(ResourceType.PROMPT, agentCode, graphId, agentDefId, otherBizParams);
    }

    @GetMapping("/models")
    public Map<String, Object> models(@RequestParam(required = false) String agentCode,
                                      @RequestParam(required = false) String graphId,
                                      @RequestParam(required = false) String agentDefId,
                                      @RequestParam(required = false) String otherBizParams) {
        return list(ResourceType.MODEL, agentCode, graphId, agentDefId, otherBizParams);
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestParam(required = false) String agentCode,
                                     @RequestParam(required = false) String graphId,
                                     @RequestParam(required = false) String agentDefId,
                                     @RequestParam(required = false) String otherBizParams) {
        return list(ResourceType.LOCAL_TOOL, agentCode, graphId, agentDefId, otherBizParams);
    }

    @GetMapping("/mcp")
    public Map<String, Object> mcp(@RequestParam(required = false) String agentCode,
                                   @RequestParam(required = false) String graphId,
                                   @RequestParam(required = false) String agentDefId,
                                   @RequestParam(required = false) String otherBizParams) {
        return list(ResourceType.MCP, agentCode, graphId, agentDefId, otherBizParams);
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(@RequestParam(required = false) String agentCode,
                                      @RequestParam(required = false) String graphId,
                                      @RequestParam(required = false) String agentDefId,
                                      @RequestParam(required = false) String otherBizParams) {
        return list(ResourceType.SKILL, agentCode, graphId, agentDefId, otherBizParams);
    }

    private Map<String, Object> list(ResourceType type, String agentCode, String graphId,
                                     String agentDefId, String otherBizParams) {
        AgentResourceCatalog catalog = catalogs.get(type);
        List<ResourceItem> items = catalog == null
                ? List.of()
                : new ArrayList<>(catalog.list(agentCode, graphId, agentDefId, otherBizParams));
        log.info("[ace-graph-dsl-web-webflux] Catalog.list type={}, agentCode={}, graphId={}, otherBizParamsLen={}, size={}",
                type, agentCode, graphId,
                otherBizParams == null ? 0 : otherBizParams.length(),
                items.size());
        return Map.of("items", items);
    }
}