package io.acelance.graph.dsl.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;

/**
 * Checkpoint Saver 提供者 SPI。
 *
 * <p>每个实现对应一种 {@code compile.saver} 类型（如 {@code memory} / {@code redis} / {@code jdbc}）。
 * 宿主可注册自定义实现（如对接业务库的 JDBC saver）以扩展可用的 checkpoint 后端，而无需修改库代码。</p>
 *
 * <p>{@link #create()} 在每次图编译时调用，返回一个可用的 {@link BaseCheckpointSaver} 实例。
 * 对于无状态共享后端（如 Redis），可返回包装同一连接的新实例；对于内存型，建议每次返回新实例以隔离不同图。</p>
 */
public interface CheckpointSaverProvider {

    /**
     * 本提供者支持的 saver 类型标识（大小写不敏感，建议小写），如 {@code memory}。
     */
    String type();

    /**
     * 创建一个 checkpoint saver 实例。
     */
    BaseCheckpointSaver create();
}
