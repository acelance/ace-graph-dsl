package io.acelance.graph.dsl.ai.media;

import java.util.Map;

/**
 * P0 L1.5：续轮 body 无附件时，从会话记忆把已落库附件 URL 回灌进图 inputs {@code media_refs}。
 *
 * <p>框架中性：不规定 extras 键名；宿主从 ChatMemory USER extras 取 URL 原串。
 * 无 Bean 时入口跳过回灌并打日志。</p>
 */
@FunctionalInterface
public interface MediaRefsHydrator {

    /**
     * 原地合并回灌（与本轮已有 {@code media_refs} 去重）。
     *
     * @param inputs         图执行 inputs（可含已有 media_refs）
     * @param conversationId 会话 id；空则跳过
     * @param mediaRefsKey   state 键，通常 {@code media_refs}
     */
    void hydrate(Map<String, Object> inputs, String conversationId, String mediaRefsKey);
}
