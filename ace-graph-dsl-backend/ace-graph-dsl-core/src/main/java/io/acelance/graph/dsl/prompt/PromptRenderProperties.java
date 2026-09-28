package io.acelance.graph.dsl.prompt;

/**
 * Prompt 变量渲染配置（§4.4.2）。
 *
 * @param strictVariables 缺失变量是否 fail fast（默认 false → 空串 + warn）
 * @param maxValueLength  单变量渲染上限字符数（默认 8192）
 * @param maxTotalLength  渲染后总长上限（默认 32000）
 */
public record PromptRenderProperties(
        boolean strictVariables,
        int maxValueLength,
        int maxTotalLength
) {
    public static final int DEFAULT_MAX_VALUE = 8192;
    public static final int DEFAULT_MAX_TOTAL = 32_000;

    public PromptRenderProperties {
        if (maxValueLength <= 0) {
            maxValueLength = DEFAULT_MAX_VALUE;
        }
        if (maxTotalLength <= 0) {
            maxTotalLength = DEFAULT_MAX_TOTAL;
        }
    }

    /** 默认：非严格、单变量 8K、总长 32K */
    public static PromptRenderProperties defaults() {
        return new PromptRenderProperties(false, DEFAULT_MAX_VALUE, DEFAULT_MAX_TOTAL);
    }
}
