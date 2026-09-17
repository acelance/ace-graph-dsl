package io.acelance.graph.dsl.bizparam;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注业务节点附加参数解释器，供 Catalog / UI 发现。
 *
 * <p>{@code id} 为稳定编码（写入图 JSON），勿用类名。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NodeBizParamHandler {

    /** 稳定 id，写入 {@code GenericAgentSpec.bizParamInterpreterId} */
    String id();

    /** 设计器下拉展示名 */
    String displayName() default "";

    /** 排序，越小越靠前 */
    int order() default 100;
}
