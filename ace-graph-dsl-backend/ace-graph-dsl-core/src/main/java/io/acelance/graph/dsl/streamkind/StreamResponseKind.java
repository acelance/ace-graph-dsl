package io.acelance.graph.dsl.streamkind;

/**
 * 流式响应类型标签（BIZ / OUTPUT / 业务扩展）。
 *
 * <p>空值不允许：须经 {@link StreamResponseKindResolver#resolveOrDefault} 归一后再构造。</p>
 */
public record StreamResponseKind(String code) {

    public StreamResponseKind {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("streamResponseKind 不能为空（请走 resolveOrDefault）");
        }
        code = code.trim();
    }

    /**
     * 严格构造：空值 fail fast（与方案 §9.7.4 一致）。
     */
    public static StreamResponseKind of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("StreamResponseKind.of 不接受空值，请使用 resolveOrDefault");
        }
        return new StreamResponseKind(raw);
    }
}
