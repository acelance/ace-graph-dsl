package io.acelance.graph.dsl.security;

import io.acelance.graph.dsl.registry.GraphNodeDescriptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 节点/Dispatcher 访问过滤工具。
 */
public final class NodeAccessFilter {

    private NodeAccessFilter() {
    }

    public static List<GraphNodeDescriptor> filterNodes(
            List<GraphNodeDescriptor> all,
            GraphNodeAccessControl accessControl) {

        Optional<Set<String>> allowedIds = accessControl.allowedNodeIds();
        if (allowedIds.isPresent()) {
            Set<String> ids = allowedIds.get();
            return all.stream().filter(d -> ids.contains(d.nodeId())).toList();
        }
        Optional<Set<String>> allowedTags = accessControl.allowedTags();
        if (allowedTags.isPresent()) {
            Set<String> tags = allowedTags.get();
            return all.stream()
                    .filter(d -> d.permissionTags().stream().anyMatch(tags::contains))
                    .toList();
        }
        return all;
    }

    public static <T> List<T> filterById(
            List<T> all,
            Function<T, String> idExtractor,
            GraphNodeAccessControl accessControl) {

        return accessControl.allowedDispatcherIds()
                .map(ids -> all.stream().filter(item -> ids.contains(idExtractor.apply(item))).toList())
                .orElse(all);
    }
}
