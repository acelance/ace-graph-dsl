package io.acelance.graph.dsl.streaming;

import com.alibaba.cloud.ai.graph.streaming.OutputType;

/**
 * 一个 LLM 流式 token 片段（框架无关），经 {@link GraphStreamBridge} 由节点推给控制器。
 *
 * <p>{@code responseKind} 为流式类型标签（BIZ/OUTPUT/扩展），仅供业务
 * {@link io.acelance.graph.dsl.execution.StreamingChunkFormatter} 读取；默认下发协议
 * <b>不</b>自动附带该字段（§9.6.3）。</p>
 *
 * @param nodeId       产出节点
 * @param token        文本片段
 * @param outputType   框架 OutputType
 * @param responseKind 流式响应类型 KEY，可空
 * @param last         是否本段结束
 */
public record TokenChunk(
        String nodeId,
        String token,
        OutputType outputType,
        String responseKind,
        boolean last
) {
    /** 兼容旧调用：无 responseKind */
    public TokenChunk(String nodeId, String token, OutputType outputType, boolean last) {
        this(nodeId, token, outputType, null, last);
    }
}
