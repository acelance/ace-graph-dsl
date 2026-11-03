package io.acelance.graph.dsl.ai.memory;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.Map;

/**
 * 图节点级记忆落盘 SPI（不依赖 ChatClient Advisor）。
 *
 * <p>供 SAA 父节点等在 {@code NodeAction} 完成时按 {@code memoryWrites} 写 remote 记忆。
 * 框架不写业务 extras 键名；展示正文 / metadata 由既有 Display / PersistMetadata SPI 协作。</p>
 *
 * <p>未注册 Bean 时调用方应打 WARN 并跳过落盘（降级防护）。</p>
 */
public interface GraphNodeMemoryPersister {

    /**
     * 写 USER（对应 {@code WRITE_USER}）。
     *
     * @param request 落盘请求
     */
    void persistUser(PersistUserRequest request);

    /**
     * 写 ASSISTANT 正文并合并思考 Buffer（对应 {@code WRITE_ASSISTANT_MAIN_TEXT}）。
     *
     * @param request 落盘请求
     */
    void persistAssistantMain(PersistAssistantMainRequest request);

    /**
     * @param ctx             节点上下文（须含 conversationId）
     * @param nodeId          图节点 id
     * @param stateVariables  可用于解析展示正文的 state 快照（如 inputKeys）
     * @param llmUserFallback 无 Display SPI 时的兜底 USER 正文（可空）
     */
    record PersistUserRequest(
            LlmRequestContext ctx,
            String nodeId,
            Map<String, Object> stateVariables,
            String llmUserFallback
    ) {
    }

    /**
     * @param ctx             节点上下文（须含 conversationId）
     * @param nodeId          图节点 id
     * @param assistantText   ASSISTANT 正文（通常为父 outputKey 文本）
     */
    record PersistAssistantMainRequest(
            LlmRequestContext ctx,
            String nodeId,
            String assistantText
    ) {
    }
}
