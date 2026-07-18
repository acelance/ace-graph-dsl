package io.acelance.graph.dsl.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 条件边分发器注解（Tier 1 / Tier 2 共用），元注解 {@link Component}。
 *
 * <p>用法同 {@link GraphNode}：</p>
 * <ul>
 *   <li>Tier 1：标注在 {@code extends AbstractAnnotatedEdgeDispatcher} 的类上，子类实现 {@code toAction}。</li>
 *   <li>Tier 2：设置 {@link #action()} 指向 {@code EdgeAction} 实现类，由注册器合成
 *       {@code RegisteredEdgeDispatcher} Bean。</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface EdgeDispatcher {

    /** dispatcher 唯一标识，如 "humanReviewDispatcher"。 */
    String dispatcherId();

    /** 所有可能的目标节点 ID，用于前端 mapping 校验。 */
    String[] possibleTargets() default {};

    /** 描述。 */
    String description() default "";

    /** 类别，默认 DISPATCHER。 */
    String category() default "DISPATCHER";

    /**
     * Tier 2 专用：指向实现 {@code EdgeAction} 的业务类。
     * 留 {@code Void.class} 表示走 Tier 1 显式 toAction。
     */
    Class<?> action() default Void.class;
}
