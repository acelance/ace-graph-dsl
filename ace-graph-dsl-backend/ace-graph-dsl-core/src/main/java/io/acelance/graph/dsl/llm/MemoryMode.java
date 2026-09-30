package io.acelance.graph.dsl.llm;

/**
 * 节点对话记忆模式（P3.8 / 设计 §4.2.2）。
 *
 * <p>默认 {@link #NONE}。</p>
 *
 * <p><b>ace-graph 多节点</b>：优先用 Spec {@code memoryWrites} 可组合写意图
 *（{@link MemoryWriteFlag}）；未配置时本枚举仍有效。
 * 时机上各写意图在节点完成时即时生效（USER/MAIN_TEXT → remote add；THINKING → Buffer），
 * 禁止学 Vertical 推到图尾。</p>
 */
public enum MemoryMode {
    /** 本节点不参与对话记忆 */
    NONE,
    /** 读历史；可选写 USER；不写 ASSISTANT（无 memoryWrites 时走 ReadOnly Advisor） */
    READ_ONLY,
    /**
     * 兼容旧配置：等价 {@code memoryWrites=
     * [WRITE_USER, WRITE_ASSISTANT_THINKING, WRITE_ASSISTANT_MAIN_TEXT]}。
     */
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
