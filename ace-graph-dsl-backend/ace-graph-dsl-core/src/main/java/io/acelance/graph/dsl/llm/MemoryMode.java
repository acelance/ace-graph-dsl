package io.acelance.graph.dsl.llm;

/**
 * 节点对话记忆模式（P3.8 / 设计 §4.2.2）。
 *
 * <p>默认 {@link #NONE}。多节点图勿在同一次用户回合挂多个 {@link #READ_WRITE}
 *（会各自写 ASSISTANT，污染会话）。</p>
 */
public enum MemoryMode {
    /** 本节点不参与对话记忆 */
    NONE,
    /** 读历史；可选写 USER；不写 ASSISTANT */
    READ_ONLY,
    /** 读历史 + 写 USER + 写 ASSISTANT */
    READ_WRITE;

    /**
     * 解析 Spec / JSON 字符串；空白或未知 → {@link #NONE}。
     */
    public static MemoryMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return MemoryMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
