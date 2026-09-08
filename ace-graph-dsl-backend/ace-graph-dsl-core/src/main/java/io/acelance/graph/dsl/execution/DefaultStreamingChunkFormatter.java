package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.acelance.graph.dsl.streaming.TokenChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认流式片段格式化器：输出原生 SSE 格式（业务未定制时生效）。
 *
 * <ul>
 *   <li>桥接 token 或框架内置流式 chunk：{@code {type:"chunk", node, chunk}}，结束片段追加 {@code isEnd:true}</li>
 *   <li>HITL 中断：{@code {type:"interrupt", node, interrupted:true, data}}</li>
 *   <li>普通节点输出：{@code {type:"node", node, data}}</li>
 * </ul>
 *
 * <p>行为等价于 {@link io.acelance.graph.dsl.execution.DefaultGraphExecutionEventAdapter} 的默认形状，
 * 额外在结束片段补充 {@code isEnd}，便于前端判断流式收尾。</p>
 */
public class DefaultStreamingChunkFormatter implements StreamingChunkFormatter {

    @Override
    public Object format(StreamingContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        TokenChunk tc = ctx.getTokenChunk();
        if (tc != null) {
            payload.put("type", "chunk");
            payload.put("node", nullToEmpty(tc.nodeId()));
            payload.put("chunk", tc.token());
            if (ctx.isLast()) {
                payload.put("isEnd", true);
            }
            return payload;
        }
        NodeOutput output = ctx.getOutput();
        if (output instanceof StreamingOutput<?> streaming) {
            payload.put("type", "chunk");
            payload.put("node", nullToEmpty(streaming.node()));
            payload.put("chunk", streaming.chunk());
            if (ctx.isLast()) {
                payload.put("isEnd", true);
            }
            return payload;
        }
        if (output instanceof InterruptionMetadata metadata) {
            payload.put("type", "interrupt");
            payload.put("node", metadata.node());
            payload.put("interrupted", true);
            if (metadata.state() != null) {
                payload.put("data", metadata.state().data());
            }
            return payload;
        }
        payload.put("type", "node");
        payload.put("node", output.node());
        if (output.state() != null) {
            payload.put("data", output.state().data());
        }
        return payload;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
