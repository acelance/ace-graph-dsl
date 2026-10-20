package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.RegisteredEdgeDispatcher;
import io.acelance.graph.dsl.security.GraphNodeAccessControl;
import io.acelance.graph.dsl.security.NodeAccessFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code NodeRegistryController}：画板节点 / dispatcher 列表。
 */
@RestController
@RequestMapping("/api/graph")
public class NodeRegistryController {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistryController.class);

    private final GraphNodeRegistry nodeRegistry;
    private final EdgeDispatcherRegistry dispatcherRegistry;
    private final GraphNodeAccessControl accessControl;

    public NodeRegistryController(GraphNodeRegistry nodeRegistry,
                                                  EdgeDispatcherRegistry dispatcherRegistry,
                                                  GraphNodeAccessControl accessControl) {
        this.nodeRegistry = nodeRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.accessControl = accessControl;
        log.info("注册 [ace-graph-dsl-web-webflux]DesignerNodeRegistryController（WebFlux /api/graph/nodes|dispatchers）");
    }

    @GetMapping("/nodes")
    public List<GraphNodeDescriptor> listNodes(@RequestParam(defaultValue = "ALL") String origin) {
        NodeOrigin filter = parseOrigin(origin);
        List<GraphNodeDescriptor> all = nodeRegistry.listDescriptors(filter);
        return NodeAccessFilter.filterNodes(all, accessControl);
    }

    @GetMapping("/dispatchers")
    public List<DispatcherSummary> listDispatchers() {
        List<RegisteredEdgeDispatcher> all = dispatcherRegistry.list();
        List<RegisteredEdgeDispatcher> filtered = NodeAccessFilter.filterById(
                all, RegisteredEdgeDispatcher::dispatcherId, accessControl);
        return filtered.stream()
                .map(d -> new DispatcherSummary(d.dispatcherId(), d.possibleTargets()))
                .toList();
    }

    private static NodeOrigin parseOrigin(String origin) {
        try {
            return NodeOrigin.valueOf(origin.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return NodeOrigin.ALL;
        }
    }

    public record DispatcherSummary(String dispatcherId, Set<String> possibleTargets) {}
}
