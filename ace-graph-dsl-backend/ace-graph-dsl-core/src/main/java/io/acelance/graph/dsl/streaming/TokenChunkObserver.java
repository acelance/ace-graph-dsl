package io.acelance.graph.dsl.streaming;

/**
 * 流式 TokenChunk 同步观察入口（O2）。
 *
 * <p>在 {@link GraphStreamBridge#emit} 写入 sink <strong>之前</strong>同步回调，
 * 供业务挂接（如 ThinkingBuffer.append）。框架不解析 SSE thinking 协议。</p>
 */
@FunctionalInterface
public interface TokenChunkObserver {

    /**
     * @param runId 本次执行 runId
     * @param chunk 即将推入桥接通道的片段
     */
    void onEmit(String runId, TokenChunk chunk);
}
