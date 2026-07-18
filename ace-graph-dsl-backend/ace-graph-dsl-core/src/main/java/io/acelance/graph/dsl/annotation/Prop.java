package io.acelance.graph.dsl.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 单个可配置属性描述（用于 {@link GraphNode#configurableProps()}）。
 *
 * <p>{@code type/label/defaultValue} 直接表达；{@code extra}（如 {@code min/max/select 选项}）
 * 是 {@code Map<String,Object>}，Java 注解无法直写，故用 {@code "key=value"} 字符串数组约定，
 * 由 {@link GraphNodeAnnotationSupport} 解析进 Map。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Prop {

    /** 属性 key（schema 的 map key）。 */
    String key();

    /** 类型：number / string / boolean / select。 */
    String type() default "string";

    /** 显示标签。 */
    String label() default "";

    /** 默认值（字符串；支持自动转换为数字）。 */
    String defaultValue() default "";

    /** 额外约束，形如 "min=0"、"max=1"、"options=a,b,c"。 */
    String[] extra() default {};
}
