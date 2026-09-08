package io.acelance.graph.dsl.execution;

import io.acelance.graph.dsl.streaming.TokenChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 委派式格式化器：把节点级事件直接交给旧版 {@link GraphExecutionEventAdapter} 处理，
 * 以保持既有定制行为的向后兼容。
 *
 * <p>当业务项目仅自定义了 {@code GraphExecutionEventAdapter}（未提供 {@code StreamingChunkFormatter}）
 * 时，控制器会自动选用本实现：节点级事件沿用旧 adapter 的形状；LLM 逐 token 片段退化为内置
 * chunk 结构（{@code {type:"chunk", node, chunk, isEnd}}），保证前端仍可消费，无需业务改动。</p>
 */
public class AdapterDelegatingStreamingChunkFormatter implements StreamingChunkFormatter {

    private final GraphExecutionEventAdapter delegate;

    public AdapterDelegatingStreamingChunkFormatter(GraphExecutionEventAdapter delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object format(StreamingContext ctx) {
        if (ctx.getTokenChunk() != null) {
            TokenChunk tc = ctx.getTokenChunk();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "chunk");
            payload.put("node", tc.nodeId());
            payload.put("chunk", tc.token());
            if (tc.last()) {
                payload.put("isEnd", true);
            }
            return payload;
        }
        return delegate.toPayload(ctx.getOutput());
    }
}
