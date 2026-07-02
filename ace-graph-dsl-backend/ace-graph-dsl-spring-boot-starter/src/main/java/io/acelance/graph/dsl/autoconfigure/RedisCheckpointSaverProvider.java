package io.acelance.graph.dsl.autoconfigure;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import io.acelance.graph.dsl.checkpoint.CheckpointSaverProvider;
import org.redisson.api.RedissonClient;

/**
 * 基于 Redisson 的 Redis checkpoint saver 提供者。
 *
 * <p>仅当 classpath 存在 {@link RedissonClient} 且容器中存在其 Bean 时由 starter 注册。
 * 多实例部署可共享 checkpoint，HITL 场景重启不丢状态。</p>
 */
public class RedisCheckpointSaverProvider implements CheckpointSaverProvider {

    /** 类型标识：{@value}。 */
    public static final String TYPE = "redis";

    private final RedissonClient redissonClient;

    public RedisCheckpointSaverProvider(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BaseCheckpointSaver create() {
        return RedisSaver.builder()
                .redisson(redissonClient)
                .build();
    }
}
