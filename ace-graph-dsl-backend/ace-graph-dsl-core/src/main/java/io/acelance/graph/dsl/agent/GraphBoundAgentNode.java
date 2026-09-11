package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.registry.RegisteredGraphNode;

/**
 * 可绑定图归属的 agent 节点（core 侧抽象，避免 core 引用 ai 模块具体类型）。
 *
 * <p>注册中心实例为「无图归属」共享定义，编译期须按当前图
 * {@link #withGraphId(String)} 克隆，以免 SecretResolver 取错命名空间。</p>
 */
public interface GraphBoundAgentNode extends RegisteredGraphNode {

    /**
     * 克隆出绑定指定 graphId 的副本。
     *
     * @param graphId 当前编排图 ID
     * @return 绑定后的节点（可与原实例相同，若已绑定同一图）
     */
    GraphBoundAgentNode withGraphId(String graphId);

    /**
     * 以给定变量执行一次（试跑 / 单节点路径）。
     *
     * @param variables prompt 变量
     * @return 写回 state 的 Map（含 outputKey）
     */
    java.util.Map<String, Object> execute(java.util.Map<String, Object> variables);

    /**
     * 节点元数据（可选）。编译期 normalize streamResponseKind 时使用。
     *
     * @return Spec；无则 null
     */
    default io.acelance.graph.dsl.definition.GenericAgentSpec agentSpec() {
        return null;
    }
}
