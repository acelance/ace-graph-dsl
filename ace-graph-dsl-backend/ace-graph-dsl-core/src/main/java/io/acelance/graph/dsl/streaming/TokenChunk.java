package io.acelance.graph.dsl.streaming;

import com.alibaba.cloud.ai.graph.streaming.OutputType;

/**
 * 一个 LLM 流式 token 片段（框架无关），经 {@link GraphStreamBridge} 由节点推给控制器。
 *
 * <p>选择「自定义记录」而非复用框架 {@code StreamingOutput}，是因为后者构造器对 {@code chunk}
 * 字段的赋值规则随版本不稳定（多数构造器不赋值、且无法同时指定 outputType），直接 new 极易错位。
 * 本记录仅携带格式化所需的最小信息，由 {@link io.acelance.graph.dsl.execution.StreamingChunkFormatter}
 * 决定最终下发形状。</p>
 */
public record TokenChunk(String nodeId, String token, OutputType outputType, boolean last) {
}
