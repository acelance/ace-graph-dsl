package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeOrigin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 agent 节点定义（可持久化 / 可复用）。
 *
 * <p>与脚本节点（{@link DynamicNodeDefinition}）对齐的「先定义 → 入库 → 复用」范式：
 * 用户在设计器中先创建一个 agent 节点定义（模型 / prompt / skill / mcp / tools 等元数据），
 * 落库后注册进 {@code GraphNodeRegistry}，即可像脚本节点一样被任意图按 {@code nodeId} 引用。</p>
 *
 * <p>与 {@link GenericAgentSpec} 的关系：{@code spec} 承载「执行所需」的纯元数据，
 * 本记录在其之上补齐「资产管理」维度（展示名 / 描述 / 版本 / 创建人 / 时间戳 / 启用位）。
 * 二者刻意分离——图内联（inline）通道仍只携带 {@code spec}，避免污染 DSL。</p>
 *
 * @param nodeId         节点 ID，必须以 {@code agent:} 开头
 * @param displayName    展示名（设计器节点面板显示）
 * @param description    描述
 * @param version        版本，默认 1.0.0
 * @param spec           agent 执行元数据（落库前 api-key 已掩码）
 * @param permissionTags 权限标签
 * @param createdBy      创建人
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 * @param enabled        是否启用（启动重载只加载启用项）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenericAgentDefinition(
        String nodeId,
        String displayName,
        String description,
        String version,
        GenericAgentSpec spec,
        Set<String> permissionTags,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        boolean enabled
) {

    /** 通用 agent 节点 ID 前缀（与脚本节点的 {@code script:} 对称） */
    public static final String AGENT_ID_PREFIX = "agent:";

    /** 默认版本 */
    public static final String DEFAULT_VERSION = "1.0.0";

    public GenericAgentDefinition {
        if (version == null || version.isBlank()) {
            version = DEFAULT_VERSION;
        }
        if (permissionTags == null) {
            permissionTags = Set.of();
        }
        if (spec == null) {
            spec = new GenericAgentSpec(null, null, false, null, null, null,
                    null, null, null, null, List.of(), null, GenericAgentSpec.DEFAULT_OUTPUT_KEY);
        }
    }

    /** 由 spec 快捷构造一个定义（时间戳留空，由 service 归一化时补齐） */
    public static GenericAgentDefinition fromSpec(String nodeId, String displayName, GenericAgentSpec spec) {
        return new GenericAgentDefinition(nodeId, displayName, null, DEFAULT_VERSION,
                spec, Set.of(), null, null, null, true);
    }

    /** 返回执行所需的 spec（非空保证由紧凑构造器给出） */
    public GenericAgentSpec toSpec() {
        return spec;
    }

    /** 替换 spec 的副本 */
    public GenericAgentDefinition withSpec(GenericAgentSpec newSpec) {
        return new GenericAgentDefinition(nodeId, displayName, description, version,
                newSpec, permissionTags, createdBy, createdAt, updatedAt, enabled);
    }

    /** 替换 nodeId 的副本（更新接口以路径 nodeId 为准） */
    public GenericAgentDefinition withNodeId(String newNodeId) {
        return new GenericAgentDefinition(newNodeId, displayName, description, version,
                spec, permissionTags, createdBy, createdAt, updatedAt, enabled);
    }

    /** 落库脱敏副本：api-key 仅留后 4 位 */
    public GenericAgentDefinition masked() {
        return withSpec(spec.masked());
    }

    /** 展示名（缺省回落到 nodeId） */
    public String effectiveDisplayName() {
        return (displayName == null || displayName.isBlank()) ? nodeId : displayName;
    }

    /** 生成注册中心元数据描述（供设计器节点面板渲染 / 拖拽） */
    public GraphNodeDescriptor toDescriptor() {
        Map<String, GraphNodeDescriptor.PropertySchema> props = new LinkedHashMap<>();
        props.put("modelId", new GraphNodeDescriptor.PropertySchema("string", "模型", spec.modelId(), Map.of()));
        props.put("modelBaseUrl", new GraphNodeDescriptor.PropertySchema("string", "模型端点", spec.modelBaseUrl(), Map.of()));
        props.put("prompt", new GraphNodeDescriptor.PropertySchema("string", "prompt 模板", spec.prompt(), Map.of()));
        props.put("promptKey", new GraphNodeDescriptor.PropertySchema("string", "prompt key", spec.promptKey(), Map.of()));
        props.put("outputKey", new GraphNodeDescriptor.PropertySchema("string", "输出 key", spec.effectiveOutputKey(), Map.of()));
        return new GraphNodeDescriptor(
                nodeId,
                effectiveDisplayName(),
                GraphNodeDescriptor.CATEGORY_GENERIC_AGENT,
                description,
                spec.inputKeySet(),
                Set.of(spec.effectiveOutputKey()),
                true,
                version,
                props,
                NodeOrigin.GENERIC_AGENT,
                permissionTags);
    }
}
