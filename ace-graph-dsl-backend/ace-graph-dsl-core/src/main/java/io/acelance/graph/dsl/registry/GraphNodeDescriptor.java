package io.acelance.graph.dsl.registry;

import java.util.Map;
import java.util.Set;

/**
 * 节点元数据描述。设计器节点面板渲染依据。
 *
 * @param nodeId           全局唯一节点标识，如 "intake_normalize"
 * @param displayName      显示名，如 "入参标准化"
 * @param category         节点类别：NORMAL / ROUTER / MERGE / HITL
 * @param description      节点描述
 * @param inputKeys        读取的 state key 集合
 * @param outputKeys       写入的 state key 集合
 * @param supportsParallel 是否可作为并行分支起点
 * @param version          节点实现版本（semver）
 * @param configurableProps 可视化可配置属性 schema（key → 属性描述）
 * @param origin           节点来源：BUILTIN / SCRIPT
 * @param permissionTags   权限标签，供 GraphNodeAccessControl 过滤
 */
public record GraphNodeDescriptor(
        String nodeId,
        String displayName,
        String category,
        String description,
        Set<String> inputKeys,
        Set<String> outputKeys,
        boolean supportsParallel,
        String version,
        Map<String, PropertySchema> configurableProps,
        NodeOrigin origin,
        Set<String> permissionTags
) {

    /** 节点类别常量 */
    public static final String CATEGORY_NORMAL = "NORMAL";
    public static final String CATEGORY_ROUTER = "ROUTER";
    public static final String CATEGORY_MERGE = "MERGE";
    public static final String CATEGORY_HITL = "HITL";

    /** 兼容旧构造：BUILTIN 来源，无权限标签 */
    public GraphNodeDescriptor(
            String nodeId,
            String displayName,
            String category,
            String description,
            Set<String> inputKeys,
            Set<String> outputKeys,
            boolean supportsParallel,
            String version,
            Map<String, PropertySchema> configurableProps) {
        this(nodeId, displayName, category, description, inputKeys, outputKeys,
                supportsParallel, version, configurableProps, NodeOrigin.BUILTIN, Set.of());
    }

    public GraphNodeDescriptor {
        if (origin == null) {
            origin = NodeOrigin.BUILTIN;
        }
        if (permissionTags == null) {
            permissionTags = Set.of();
        }
        if (configurableProps == null) {
            configurableProps = Map.of();
        }
    }

    /** 可配置属性 schema */
    public record PropertySchema(
            String type,        // number / string / boolean / select
            String label,
            Object defaultValue,
            Map<String, Object> extra
    ) {
        public PropertySchema {
            if (extra == null) {
                extra = Map.of();
            }
        }
    }
}
