package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认事件适配器：输出 {@code {type, node, data/chunk, interrupted?}} 结构的 Map。
 *
 * <ul>
 *   <li>普通节点输出：{@code {type:"node", node, data}}</li>
 *   <li>LLM 流式 chunk：{@code {type:"chunk", node, chunk}}</li>
 *   <li>HITL 中断：{@code {type:"interrupt", node, interrupted:true, data}}</li>
 * </ul>
 */
public class DefaultGraphExecutionEventAdapter implements GraphExecutionEventAdapter {

    @Override
    public Object toPayload(NodeOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (output instanceof StreamingOutput<?> streaming) {
            payload.put("type", "chunk");
            payload.put("node", nullToEmpty(streaming.node()));
            payload.put("chunk", streaming.chunk());
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
