package io.acelance.graph.dsl.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Checkpoint Saver 提供者注解（Tier 1）。元注解 {@link Component}。
 *
 * <p>标注在 {@code extends AbstractCheckpointSaverProvider} 的类上，由本注解承载
 * {@code type()} 元数据，{@code create()} 由子类实现。该 Bean 会被
 * {@code CheckpointSaverRegistry} 经 {@code ObjectProvider<CheckpointSaverProvider>} 自动收集。</p>
 *
 * <p>由于 {@code create()} 是用户自定义工厂逻辑（常需注入 DataSource / RedissonClient 等），
 * CheckpointSaver 仅提供 Tier 1（注解 + 实现接口），不提供 {@code action=} 形式的 Tier 2 合成。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface CheckpointSaver {

    /** saver 类型标识（大小写不敏感，建议小写），如 "redis" / "jdbc"。 */
    String type();

    /** 描述。 */
    String description() default "";
}
