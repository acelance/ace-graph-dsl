package io.acelance.graph.dsl.registry;

import com.alibaba.cloud.ai.graph.action.CommandAction;

/**
 * 可注册的 Agent 动作节点接口。除普通 {@link RegisteredGraphNode#toAction(NodeRuntimeContext)}
 * 外，额外提供 {@link #toCommandAction(NodeRuntimeContext)}，返回 {@link CommandAction}
 * （同步 {@code BiFunction<OverAllState, RunnableConfig, Command>}），由构建器包装为
 * {@code AsyncCommandAction}。返回 {@code Command(gotoNode, update)} 即可形成
 * "续轮 / 退出" 的 agent 循环（subagent 内核）。
 */
public interface RegisteredAgentNode extends RegisteredGraphNode {

    /**
     * 根据运行时上下文创建 CommandAction 实例。
     *
     * @param ctx 节点运行时上下文（含节点级 config）
     * @return 同步 CommandAction，由 {@code AsyncCommandAction.node_async} 包装
     */
    CommandAction toCommandAction(NodeRuntimeContext ctx);
}
