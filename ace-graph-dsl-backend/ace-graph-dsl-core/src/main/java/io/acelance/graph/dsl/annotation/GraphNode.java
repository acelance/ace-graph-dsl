package io.acelance.graph.dsl.annotation;

import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeOrigin;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 节点注解（Tier 1 / Tier 2 共用）。
 *
 * <p>元注解 {@link Component}，因此被标注的类会自动成为 Spring Bean，由
 * {@code GraphNodeRegistry} 自动发现。</p>
 *
 * <ul>
 *   <li><b>Tier 1（显式 toAction）</b>：标注在 {@code extends AbstractAnnotatedGraphNode} 的类上，
 *       仅用本注解承载描述性元数据，子类自行实现 {@code toAction(...)}。</li>
 *   <li><b>Tier 2（注解即注册，零方法）</b>：在注解上设置 {@link #action()}，由注册器合成
 *       {@code RegisteredGraphNode} Bean，其 {@code toAction} 通过
 *       {@code AutowireCapableBeanFactory.autowire(actionClass)} 按构造器解析依赖创建 action 实例。</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface GraphNode {

    /** 全局唯一节点标识，如 "ragQuickAnswer"。 */
    String nodeId();

    /** 显示名，如 "FAQ 快速回答"。 */
    String displayName();

    /** 节点类别：NORMAL / ROUTER / MERGE / HITL。 */
    String category() default GraphNodeDescriptor.CATEGORY_NORMAL;

    /** 节点描述。 */
    String description() default "";

    /** 读取的 state key 集合。 */
    String[] inputKeys() default {};

    /** 写入的 state key 集合。 */
    String[] outputKeys() default {};

    /** 是否可作为并行分支起点。 */
    boolean supportsParallel() default false;

    /** 节点实现版本（semver）。 */
    String version() default "1.0.0";

    /** 节点来源：BUILTIN / SCRIPT。 */
    NodeOrigin origin() default NodeOrigin.BUILTIN;

    /** 权限标签，供 GraphNodeAccessControl 过滤。 */
    String[] permissionTags() default {};

    /** 可视化可配置属性 schema。 */
    Prop[] configurableProps() default {};

    /**
     * Tier 2 专用：指向实现 {@code NodeAction} 的业务类。
     * 留 {@code Void.class} 表示不启用 Tier 2 合成（走 Tier 1 显式 toAction）。
     */
    Class<?> action() default Void.class;
}
