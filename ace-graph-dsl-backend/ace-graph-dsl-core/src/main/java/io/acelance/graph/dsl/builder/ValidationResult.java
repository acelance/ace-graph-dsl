package io.acelance.graph.dsl.builder;

import java.util.List;

/**
 * 校验结果。
 *
 * @param ok      是否通过
 * @param errors  错误信息列表
 */
public record ValidationResult(boolean ok, List<String> errors) {

    /** 构造通过的结果（避免与 record 访问器 ok() 同名，使用 pass） */
    public static ValidationResult pass() {
        return new ValidationResult(true, List.of());
    }

    /** 构造失败的结果 */
    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, errors);
    }
}
