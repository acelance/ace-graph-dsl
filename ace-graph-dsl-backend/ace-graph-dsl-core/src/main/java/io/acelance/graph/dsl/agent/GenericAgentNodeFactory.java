package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;

import java.util.Set;

/**
 * GENERIC_AGENT 节点工厂（core 定义，由 ace-graph-dsl-ai 实现并注册为 Spring Bean）。
 *
 * <p>core 的 {@code DynamicGraphBuilder} / {@code GenericAgentNodeService} 只依赖本接口，
 * 从而在未引入 ai 模块时给出可操作报错，而非直接引用具体 {@code GenericAgentNode}。</p>
 */
public interface GenericAgentNodeFactory {

    /**
     * 按元数据构造 agent 节点。
     *
     * @param nodeId  节点 ID
     * @param graphId 图 ID（注册式共享定义可为 null，编译期再 withGraphId）
     * @param spec    节点元数据
     * @return 可注册 / 可执行的 agent 节点
     */
    GraphBoundAgentNode create(String nodeId, String graphId, GenericAgentSpec spec);

    /**
     * 按完整展示信息构造（注册入库通道）。
     *
     * @param nodeId         节点 ID
     * @param graphId        图 ID，注册式通常为 null
     * @param spec           元数据
     * @param displayName    展示名
     * @param description    描述
     * @param version        版本
     * @param permissionTags 权限标签
     * @return agent 节点
     */
    GraphBoundAgentNode create(String nodeId,
                              String graphId,
                              GenericAgentSpec spec,
                              String displayName,
                              String description,
                              String version,
                              Set<String> permissionTags);
}
