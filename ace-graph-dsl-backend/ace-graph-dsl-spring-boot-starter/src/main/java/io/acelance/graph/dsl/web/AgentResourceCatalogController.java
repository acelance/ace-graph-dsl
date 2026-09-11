package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.resource.AgentResourceCatalog;
import io.acelance.graph.dsl.resource.ResourceItem;
import io.acelance.graph.dsl.resource.ResourceType;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
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
 * 设计期资源 Catalog API（P1.1 / §7.2）。
 */
@RestController
@RequestMapping("/api/agent-resources")
public class AgentResourceCatalogController {

    private static final Logger log = LoggerFactory.getLogger(AgentResourceCatalogController.class);

    private final Map<ResourceType, AgentResourceCatalog> catalogs;
    private final GraphMenuPermissionResolver menuPermissions;

    public AgentResourceCatalogController(List<AgentResourceCatalog> catalogList,
                                          GraphMenuPermissionResolver menuPermissions) {
        this.catalogs = new EnumMap<>(ResourceType.class);
        if (catalogList != null) {
            for (AgentResourceCatalog c : catalogList) {
                catalogs.put(c.type(), c);
            }
        }
        this.menuPermissions = menuPermissions;
        log.info("AgentResourceCatalog 已注册: {}", catalogs.keySet());
    }

    @GetMapping("/prompts")
    public Map<String, Object> prompts(@RequestParam(required = false) String agentCode,
                                       @RequestParam(required = false) String graphId,
                                       @RequestParam(required = false) String agentDefId) {
        return list(ResourceType.PROMPT, agentCode, graphId, agentDefId);
    }

    @GetMapping("/models")
    public Map<String, Object> models(@RequestParam(required = false) String agentCode,
                                      @RequestParam(required = false) String graphId,
                                      @RequestParam(required = false) String agentDefId) {
        return list(ResourceType.MODEL, agentCode, graphId, agentDefId);
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestParam(required = false) String agentCode,
                                     @RequestParam(required = false) String graphId,
                                     @RequestParam(required = false) String agentDefId) {
        return list(ResourceType.LOCAL_TOOL, agentCode, graphId, agentDefId);
    }

    @GetMapping("/mcp")
    public Map<String, Object> mcp(@RequestParam(required = false) String agentCode,
                                   @RequestParam(required = false) String graphId,
                                   @RequestParam(required = false) String agentDefId) {
        return list(ResourceType.MCP, agentCode, graphId, agentDefId);
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(@RequestParam(required = false) String agentCode,
                                      @RequestParam(required = false) String graphId,
                                      @RequestParam(required = false) String agentDefId) {
        return list(ResourceType.SKILL, agentCode, graphId, agentDefId);
    }

    private Map<String, Object> list(ResourceType type, String agentCode, String graphId, String agentDefId) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VIEW, "无权查看资源 Catalog");
        AgentResourceCatalog catalog = catalogs.get(type);
        List<ResourceItem> items = catalog == null
                ? List.of()
                : new ArrayList<>(catalog.list(agentCode, graphId, agentDefId));
        log.info("Catalog.list type={}, agentCode={}, graphId={}, size={} (withChildren={})",
                type, agentCode, graphId, items.size(),
                items.stream().anyMatch(i -> i.children() != null && !i.children().isEmpty()));
        return Map.of("items", items);
    }
}
