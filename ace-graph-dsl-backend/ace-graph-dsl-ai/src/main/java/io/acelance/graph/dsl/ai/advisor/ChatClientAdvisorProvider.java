package io.acelance.graph.dsl.ai.advisor;

/**
 * 业务侧 ChatClient Advisor 钩子（P3.8 / 设计 §4.2.2）。
 *
 * <p>典型用途：按 {@link io.acelance.graph.dsl.llm.MemoryMode} 返回 Spring AI /
 * lesso Memory Advisor。无 Bean 时 Template 跳过，行为与今日一致。</p>
 *
 * <p>产品<strong>不</strong>实现 ChatMemory Store；userId/bizKey 由业务 Context 承载。</p>
 */
@FunctionalInterface
public interface ChatClientAdvisorProvider {

    /**
     * 按节点记忆模式提供 Advisor；无记忆时返回 {@link ChatClientAdvisorBundle#empty()}。
     *
     * <p>建议关键日志：mode / conversationId / agentCode / nodeId / advisor 数量。</p>
     */
    ChatClientAdvisorBundle provide(ChatClientAdvisorRequest request);
}
