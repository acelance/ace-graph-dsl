package io.acelance.graph.dsl.ai.advisor;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 业务侧 ChatClient Advisor 钩子（P3.8 / 设计 §4.2.2）。
 *
 * <p>典型用途：按 {@link io.acelance.graph.dsl.llm.MemoryMode} 返回 Spring AI /
 * lesso Memory Advisor。无 Bean 时 Template 跳过，行为与今日一致。</p>
 *
 * <p>产品<strong>不</strong>实现 ChatMemory Store；userId/bizKey 由业务 Context 承载。</p>
 *
 * <p>流式路径不挂记忆 Advisor（避免 stream after 丢 ASSISTANT）；读历史请实现
 * {@link #mergeHistoryForPrompt}，由 Template 在调模型前调用一次。</p>
 *
 * <p><strong>勿</strong>再标 {@code @FunctionalInterface} / 勿用只写 {@code provide} 的 lambda
 * 做包装：lambda 不会覆盖 {@link #mergeHistoryForPrompt}，会静默走 default no-op（R4b）。</p>
 */
public interface ChatClientAdvisorProvider {

    /**
     * 按节点记忆模式提供 Advisor；无记忆时返回 {@link ChatClientAdvisorBundle#empty()}。
     *
     * <p>建议关键日志：mode / conversationId / agentCode / nodeId / advisor 数量。</p>
     */
    ChatClientAdvisorBundle provide(ChatClientAdvisorRequest request);

    /**
     * 流式调模型前：把历史（及材料注记等）合并进 Prompt 消息列表。
     *
     * <p>默认原样返回 {@code seedMessages}。实现方<strong>只改返回列表</strong>，
     * <strong>不得</strong>在此方法内 remote add / 落盘（落盘仍走 sync Advisor 或 echo）。</p>
     *
     * @param request      与 {@link #provide} 同形入参（含 mode / writes / conversationId）
     * @param seedMessages 本轮种子消息（含当前 USER，未拼历史）
     * @return 合并后的 Prompt 消息；null 或空则 Template 回退为 seed
     */
    default List<Message> mergeHistoryForPrompt(ChatClientAdvisorRequest request,
                                                List<Message> seedMessages) {
        return seedMessages;
    }
}
