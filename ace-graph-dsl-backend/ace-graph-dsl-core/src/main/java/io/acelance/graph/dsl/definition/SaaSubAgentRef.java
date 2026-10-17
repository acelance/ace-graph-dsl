package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 高阶节点内子 Agent 绑定：引用 ACE 目录能力 Spec，不内联完整 {@link GenericAgentSpec}。
 *
 * @param name        子 Agent 名（FlowAgent 内唯一）
 * @param impl        实现类型，默认 {@link SaaSubAgentRefs#IMPL_GENERIC_AGENT}
 * @param ref         引用，如 {@code generic:sql-gen} / {@code agentscope:xxx}
 * @param instruction 本次输入模板，支持 {@code {stateKey}} 占位
 * @param outputKey   子结果写入 state 的键
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaaSubAgentRef(
        String name,
        String impl,
        String ref,
        String instruction,
        String outputKey
) {

    public SaaSubAgentRef {
        if (impl == null || impl.isBlank()) {
            impl = SaaSubAgentRefs.IMPL_GENERIC_AGENT;
        } else {
            impl = impl.trim();
        }
    }

    /** 有效 impl（空白已回落默认） */
    public String effectiveImpl() {
        return (impl == null || impl.isBlank()) ? SaaSubAgentRefs.IMPL_GENERIC_AGENT : impl.trim();
    }
}
