package io.acelance.graph.dsl.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

/**
 * 内置内存型 checkpoint saver 提供者（默认）。重启后丢失，仅适合单实例 / 开发场景。
 */
public class MemoryCheckpointSaverProvider implements CheckpointSaverProvider {

    /** 类型标识：{@value}。 */
    public static final String TYPE = "memory";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BaseCheckpointSaver create() {
        return new MemorySaver();
    }
}
