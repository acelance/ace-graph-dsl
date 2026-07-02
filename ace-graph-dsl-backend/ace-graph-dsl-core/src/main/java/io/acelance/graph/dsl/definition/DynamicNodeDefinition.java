package io.acelance.graph.dsl.definition;

import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeOrigin;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 动态（脚本）节点定义，持久化到存储介质。
 */
public record DynamicNodeDefinition(
        String nodeId,
        String displayName,
        String category,
        String description,
        Set<String> inputKeys,
        Set<String> outputKeys,
        boolean supportsParallel,
        String version,
        String engine,
        String scriptBody,
        String scriptHash,
        Map<String, GraphNodeDescriptor.PropertySchema> configurableProps,
        NodeOrigin origin,
        Set<String> permissionTags,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        boolean enabled
) {

    public static final String SCRIPT_ID_PREFIX = "script:";

    public DynamicNodeDefinition {
        if (origin == null) {
            origin = NodeOrigin.SCRIPT;
        }
        if (permissionTags == null) {
            permissionTags = Set.of();
        }
        if (configurableProps == null) {
            configurableProps = Map.of();
        }
        if (category == null || category.isBlank()) {
            category = GraphNodeDescriptor.CATEGORY_NORMAL;
        }
        if (engine == null || engine.isBlank()) {
            engine = "aviator";
        }
    }

    public GraphNodeDescriptor toDescriptor() {
        return new GraphNodeDescriptor(
                nodeId,
                displayName,
                category,
                description,
                inputKeys,
                outputKeys,
                supportsParallel,
                version,
                configurableProps,
                origin,
                permissionTags
        );
    }
}
