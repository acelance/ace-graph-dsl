package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.acelance.graph.dsl.streaming.TokenChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调试流式格式化器（{@code /debug/stream}）：含 responseKind / resource_miss 等诊断字段。
 */
public class DebugStreamingChunkFormatter implements StreamingChunkFormatter {

    /** TokenChunk.responseKind 标记：资源加载 miss */
    public static final String KIND_RESOURCE_MISS = "resource_miss";

    @Override
    public Object format(StreamingContext ctx) {
        TokenChunk tc = ctx.getTokenChunk();
        if (tc != null && KIND_RESOURCE_MISS.equals(tc.responseKind())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "resource_miss");
            payload.put("graphId", nullToEmpty(ctx.getGraphId()));
            payload.put("node", nullToEmpty(ctx.getNodeId()));
            payload.put("message", tc.token());
            payload.put("responseKind", KIND_RESOURCE_MISS);
            return payload;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "debug_chunk");
        payload.put("graphId", nullToEmpty(ctx.getGraphId()));
        payload.put("node", nullToEmpty(ctx.getNodeId()));
        payload.put("streaming", ctx.isStreaming());
        payload.put("isEnd", ctx.isLast());
        if (ctx.getResponseKind() != null) {
            payload.put("responseKind", ctx.getResponseKind());
        }
        if (ctx.getOutputType() != null) {
            payload.put("outputType", ctx.getOutputType().name());
        }

        if (tc != null) {
            payload.put("chunk", tc.token());
            return payload;
        }
        NodeOutput output = ctx.getOutput();
        if (output instanceof StreamingOutput<?> streaming) {
            payload.put("chunk", streaming.chunk());
            return payload;
        }
        if (output instanceof InterruptionMetadata metadata) {
            payload.put("type", "debug_interrupt");
            payload.put("interrupted", true);
            if (metadata.state() != null) {
                payload.put("data", metadata.state().data());
            }
            return payload;
        }
        payload.put("type", "debug_node");
        if (output != null && output.state() != null) {
            payload.put("data", output.state().data());
        }
        return payload;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
