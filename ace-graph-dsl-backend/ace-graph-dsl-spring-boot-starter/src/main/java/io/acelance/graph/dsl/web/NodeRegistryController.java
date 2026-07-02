package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.RegisteredEdgeDispatcher;
import io.acelance.graph.dsl.security.GraphNodeAccessControl;
import io.acelance.graph.dsl.security.NodeAccessFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 节点与 Dispatcher 注册中心 REST API。
 */
@RestController
public class NodeRegistryController {

    private final GraphNodeRegistry nodeRegistry;
    private final EdgeDispatcherRegistry dispatcherRegistry;
    private final GraphNodeAccessControl accessControl;

    public NodeRegistryController(
            GraphNodeRegistry nodeRegistry,
            EdgeDispatcherRegistry dispatcherRegistry,
            GraphNodeAccessControl accessControl) {
        this.nodeRegistry = nodeRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.accessControl = accessControl;
    }

    /**
     * 列出已注册节点的元数据描述。
     *
     * @param origin BUILTIN | SCRIPT | ALL（默认 ALL）
     */
    @GetMapping("/nodes")
    public List<GraphNodeDescriptor> listNodes(
            @RequestParam(defaultValue = "ALL") String origin) {
        NodeOrigin filter = parseOrigin(origin);
        List<GraphNodeDescriptor> all = nodeRegistry.listDescriptors(filter);
        return NodeAccessFilter.filterNodes(all, accessControl);
    }

    /**
     * 列出所有已注册 dispatcher。
     */
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
        } catch (IllegalArgumentException e) {
            return NodeOrigin.ALL;
        }
    }

    /** Dispatcher 摘要 */
    public record DispatcherSummary(String dispatcherId, Set<String> possibleTargets) {}
}
