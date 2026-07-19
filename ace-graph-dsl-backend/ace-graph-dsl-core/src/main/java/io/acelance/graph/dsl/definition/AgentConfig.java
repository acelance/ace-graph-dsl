package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Agent 循环节点配置（subagent 内核）。
 *
 * <p>spring-ai-alibaba-graph 没有一等公民的"子智能体节点"，agent 由
 * "返回 {@code Command} 的节点动作 + 自环边"组合而成。此处仅声明式记录该循环，
 * 真正的循环逻辑由 {@code actionRef} 指向的已注册 {@code RegisteredAgentNode}
 * （如内置 {@code agent:script}）承载。DSL 无法可视化循环体内部，属已知失真点。</p>
 *
 * @param actionRef     已注册的 agent 动作节点 ID（须实现 RegisteredAgentNode），如 agent:script
 * @param maxIterations 最大迭代次数；超过即强制退出循环（防死循环护栏），默认 25
 * @param loopTo        循环回边目标；"__SELF__" 表示回到自身，或指定某个节点 ID，默认 "__SELF__"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(
        String actionRef,
        int maxIterations,
        String loopTo
) {

    public AgentConfig {
        if (maxIterations <= 0) {
            maxIterations = 25;
        }
        if (loopTo == null || loopTo.isBlank()) {
            loopTo = "__SELF__";
        }
    }

    /** 仅指定动作节点时的便捷构造 */
    public AgentConfig(String actionRef) {
        this(actionRef, 25, "__SELF__");
    }
}
