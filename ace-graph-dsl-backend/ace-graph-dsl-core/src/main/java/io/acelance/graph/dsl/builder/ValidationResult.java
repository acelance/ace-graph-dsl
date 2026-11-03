package io.acelance.graph.dsl.builder;

import java.util.List;

/**
 * 校验结果。
 *
 * <p>{@code ok} 仅由 {@code errors} 是否为空决定；{@code warnings} 不挡通过（可提示双 MAIN 等）。</p>
 *
 * @param ok       是否通过（无 errors）
 * @param errors   错误信息列表
 * @param warnings 警告信息列表（可空）
 */
public record ValidationResult(boolean ok, List<String> errors, List<String> warnings) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 构造通过的结果（无错误、无警告）。 */
    public static ValidationResult pass() {
        return new ValidationResult(true, List.of(), List.of());
    }

    /**
     * 构造失败的结果（无警告）。
     *
     * @param errors 错误列表
     */
    public static ValidationResult fail(List<String> errors) {
        List<String> e = errors == null ? List.of() : List.copyOf(errors);
        return new ValidationResult(e.isEmpty(), e, List.of());
    }

    /**
     * 按错误与警告构造；{@code ok = errors 为空}。
     *
     * @param errors   错误（可空）
     * @param warnings 警告（可空）
     */
    public static ValidationResult of(List<String> errors, List<String> warnings) {
        List<String> e = errors == null ? List.of() : List.copyOf(errors);
        List<String> w = warnings == null ? List.of() : List.copyOf(warnings);
        return new ValidationResult(e.isEmpty(), e, w);
    }

    /** 是否有警告。 */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
