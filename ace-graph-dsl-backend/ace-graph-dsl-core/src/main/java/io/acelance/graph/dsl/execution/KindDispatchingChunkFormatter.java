package io.acelance.graph.dsl.execution;

import java.util.Locale;

/**
 * 按 {@code streamResponseKind} 分派的便利基类（§9.3.5，可选）。
 *
 * <p>框架不提供任何分支默认协议字段；业务覆盖 {@link #onBiz}/{@link #onOutput}/{@link #onOther}
 * 自行决定 SSE 形状。不想用时可直接实现 {@link StreamingChunkFormatter}。</p>
 */
public abstract class KindDispatchingChunkFormatter implements StreamingChunkFormatter {

    public static final String BIZ = "BIZ";
    public static final String OUTPUT = "OUTPUT";

    @Override
    public final Object format(StreamingContext ctx) {
        String kind = ctx.getResponseKind();
        if (kind == null || kind.isBlank()) {
            return onOther(ctx, null);
        }
        String code = kind.trim().toUpperCase(Locale.ROOT);
        return switch (code) {
            case BIZ -> onBiz(ctx);
            case OUTPUT -> onOutput(ctx);
            default -> onOther(ctx, code);
        };
    }

    /** BIZ 通道 */
    protected abstract Object onBiz(StreamingContext ctx);

    /** OUTPUT 通道 */
    protected abstract Object onOutput(StreamingContext ctx);

    /**
     * 自定义 kind 与无 kind 片段。
     *
     * @param kind 已大写的 code；无 kind 时为 null
     */
    protected abstract Object onOther(StreamingContext ctx, String kind);
}
