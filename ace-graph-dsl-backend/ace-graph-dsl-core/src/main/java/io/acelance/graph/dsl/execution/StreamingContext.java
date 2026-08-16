package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.acelance.graph.dsl.streaming.TokenChunk;

/**
 * 流式片段格式化上下文：传递给 {@link StreamingChunkFormatter}，携带一次输出片段的
 * 全部可定制信息。
 *
 * <p>业务项目在自定义 {@link StreamingChunkFormatter} 时，可据此上下文决定输出结构，
 * 例如根据 {@link #isLast()} 设置 {@code isEnd}、根据 {@link #isStreaming()} 与
 * {@link #getOutputType()} 判断思考 / 生成阶段（对应业务约定的 {@code thinking} 等字段）。</p>
 *
 * <p>两种来源：</p>
 * <ul>
 *   <li>{@link #ofNode(NodeOutput, String)}：来自 {@code graph.stream()} 的节点输出
 *       （含框架内置流式节点的 {@link StreamingOutput}）；</li>
 *   <li>{@link #ofToken(TokenChunk, String)}：来自桥接通道的 LLM 逐 token 片段。</li>
 * </ul>
 */
public class StreamingContext {

    private final NodeOutput output;
    private final TokenChunk tokenChunk;
    private final String graphId;
    private final String nodeId;
    private final boolean streaming;
    private final OutputType outputType;
    private final boolean isLast;

    private StreamingContext(NodeOutput output,
                             TokenChunk tokenChunk,
                             String graphId,
                             String nodeId,
                             boolean streaming,
                             OutputType outputType,
                             boolean isLast) {
        this.output = output;
        this.tokenChunk = tokenChunk;
        this.graphId = graphId;
        this.nodeId = nodeId;
        this.streaming = streaming;
        this.outputType = outputType;
        this.isLast = isLast;
    }

    /** 来自 graph.stream() 的节点输出（框架内置流式节点会携带 StreamingOutput）。 */
    public static StreamingContext ofNode(NodeOutput output, String graphId) {
        boolean streaming = output instanceof StreamingOutput;
        OutputType ot = streaming ? ((StreamingOutput<?>) output).getOutputType() : null;
        boolean last = ot != null && ot.name().endsWith("FINISHED");
        return new StreamingContext(output, null, graphId, output.node(), streaming, ot, last);
    }

    /** 来自桥接通道的 LLM 逐 token 片段。 */
    public static StreamingContext ofToken(TokenChunk token, String graphId) {
        return new StreamingContext(null, token, graphId, token.nodeId(), true, token.outputType(), token.last());
    }

    /** 原始节点输出（可能为 null，当本片段来自桥接 token 时）。 */
    public NodeOutput getOutput() {
        return output;
    }

    /** 桥接 token 片段（可能为 null，当本片段来自 graph.stream() 时）。 */
    public TokenChunk getTokenChunk() {
        return tokenChunk;
    }

    /** 所属图 ID（用于业务分段 / 命名空间）。 */
    public String getGraphId() {
        return graphId;
    }

    /** 节点 ID（流式片段时为 token 所属节点，否则取自 {@code NodeOutput.node()}）。 */
    public String getNodeId() {
        return nodeId;
    }

    /** 该片段是否来自 LLM 流式输出（桥接 token 或框架内置流式节点）。 */
    public boolean isStreaming() {
        return streaming;
    }

    /** 流式片段的输出类型（{@code AGENT_MODEL_STREAMING} / {@code AGENT_MODEL_FINISHED} 等）；非流式时为 null。 */
    public OutputType getOutputType() {
        return outputType;
    }

    /**
     * 是否为本段流的最后一个片段。
     *
     * <p>桥接 token 由 {@link TokenChunk#last()} 决定；图内置流式节点由
     * {@code OutputType} 以 {@code _FINISHED} 结尾决定。供格式化器设置业务约定的
     * {@code isEnd:true} 等结束标记。</p>
     */
    public boolean isLast() {
        return isLast;
    }
}
