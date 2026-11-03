package io.acelance.graph.dsl.agentscope;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.agentscope.core.model.Model;

/**
 * 将 ACE {@link GenericAgentSpec} 解析为 AgentScope {@link Model}（按 key / 内联）。
 *
 * <p>宿主可覆盖 Bean；测试可注入 stub Model。</p>
 *
 * <p>{@code modelConfigKey} 挂载依赖执行入口的 {@code agentCode}，应在<strong>运行时</strong>
 * 调用 {@link #create(String, String, String, GenericAgentSpec, String)}；
 * 发布编译期仅适合内联模型（无 Spec 绑定）。</p>
 */
@FunctionalInterface
public interface AgentScopeModelFactory {

    /**
     * 无 agentCode：仅内联模型安全；若 Spec 启用 modelConfigKey 会失败或需运行时重载。
     *
     * @param graphId 图 ID（日志）
     * @param nodeId  父高阶节点 ID（日志）
     * @param agentId ACE 注册 Agent id（agentscope:{id} 中的 id）
     * @param spec    ACE GenericAgentSpec
     * @return AgentScope Model；不可为 null
     */
    Model create(String graphId, String nodeId, String agentId, GenericAgentSpec spec);

    /**
     * 运行时挂载：带执行入口写入的 agentCode，用于 Card→Spec→modelConfigKey。
     *
     * @param agentCode 执行入口身份；走 modelConfigKey 时必填
     */
    default Model create(String graphId, String nodeId, String agentId, GenericAgentSpec spec,
                         String agentCode) {
        return create(graphId, nodeId, agentId, spec);
    }
}
