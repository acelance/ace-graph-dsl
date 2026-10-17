package io.acelance.graph.dsl.agent;

/**
 * 子 Agent 解析结果。
 *
 * <p>{@link #executable()} 为实现模块产出的可执行句柄（saa-agent 中为 SAA 官方
 * {@code BaseAgent} 子类实例）。core 不依赖 agent-framework，故类型为 {@link Object}。</p>
 *
 * @param name       子 Agent 名
 * @param impl       实现类型
 * @param ref        原始引用
 * @param outputKey  写回 state 的键
 * @param executable 可挂入 FlowAgent.subAgents 的官方 Agent 句柄
 */
public record SubAgentBinding(
        String name,
        String impl,
        String ref,
        String outputKey,
        Object executable
) {
}
