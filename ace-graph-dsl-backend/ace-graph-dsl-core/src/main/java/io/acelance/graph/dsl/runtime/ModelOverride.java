package io.acelance.graph.dsl.runtime;

/**
 * 请求级模型覆盖（单次执行内生效，不写入图定义）。
 *
 * <p>用于在调用时动态指定某个 / 全部 {@code GENERIC_AGENT} 节点使用哪个模型，
 * 而非依赖图定义里静态写死的 {@code modelId} / {@code modelBaseUrl} / {@code modelApiKey}。
 * 典型场景：同一张图在不同请求里用不同厂商 / 不同档位模型，或 A/B 对比。</p>
 */
public record ModelOverride(
        String modelId,
        String modelBaseUrl,
        String modelApiKey
) {

    /** 是否存在任何有效覆盖项（三项皆空视为无覆盖） */
    public boolean isEmpty() {
        return modelId == null && modelBaseUrl == null && modelApiKey == null;
    }
}
