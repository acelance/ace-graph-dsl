package io.acelance.graph.dsl.annotation;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import io.acelance.graph.dsl.checkpoint.CheckpointSaverProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Checkpoint Saver Tier 1 抽象基类：实现 {@link CheckpointSaverProvider}，
 * {@code type()} 由 {@link CheckpointSaver} 注解自动生成，子类只需实现 {@code create()}。
 *
 * <pre>{@code
 * @CheckpointSaver(type = "redis", description = "Redis 持久化 checkpoint")
 * public class RedisCheckpointSaverProvider extends AbstractCheckpointSaverProvider {
 *     private final RedissonClient redisson;
 *     public RedisCheckpointSaverProvider(RedissonClient redisson) { this.redisson = redisson; }
 *     @Override public BaseCheckpointSaver create() { return new RedisSaver(redisson); }
 * }
 * }</pre>
 */
public abstract class AbstractCheckpointSaverProvider implements CheckpointSaverProvider {

    @Override
    public final String type() {
        CheckpointSaver ann = AnnotatedElementUtils.findMergedAnnotation(getClass(), CheckpointSaver.class);
        if (ann == null) {
            throw new IllegalStateException(
                    "类 " + getClass().getName() + " 未被 @CheckpointSaver 标注");
        }
        return ann.type();
    }

    // 子类实现 create()
}
