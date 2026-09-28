package io.acelance.graph.dsl.agentscope;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.agentscope.core.model.Model;

/**
 * 将 ACE {@link GenericAgentSpec} 解析为 AgentScope {@link Model}（按 key / 内联）。
 *
 * <p>宿主可覆盖 Bean；测试可注入 stub Model。</p>
 */
@FunctionalInterface
public interface AgentScopeModelFactory {

    /**
     * @param graphId  图 ID（日志）
     * @param nodeId   父高阶节点 ID（日志）
     * @param agentId  ACE 注册 Agent id（agentscope:{id} 中的 id）
     * @param spec     ACE GenericAgentSpec
     * @return AgentScope Model；不可为 null
     */
    Model create(String graphId, String nodeId, String agentId, GenericAgentSpec spec);
}
