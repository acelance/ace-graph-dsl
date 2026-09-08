package io.acelance.graph.dsl.observability;

/**
 * 节点内 LLM 调用细节事件（由 {@code GenericAgentNode} 在调用边界推送）。
 *
 * <p>用于观测后端（Langfuse 等）记录「本次请求该节点 <b>实际使用的模型</b>、prompt、响应、错误、耗时」——
 * 尤其是模型在请求级动态指定的场景，trace 中记录的是运行时真实模型，而非图定义里的静态值。</p>
 *
 * @param graphId           所属图 ID
 * @param nodeId            节点 ID
 * @param runId             本次执行 runId（通常即 {@code RunnableConfig.threadId()}）
 * @param modelId           实际使用的模型标识（已应用请求级覆盖）
 * @param modelBaseUrl      实际使用的模型端点
 * @param promptTemplate    实际拼装后的 prompt
 * @param response          模型响应原文（可能是 JSON 字符串）
 * @param durationMs        调用耗时（毫秒）
 * @param startedAtEpochMs  调用开始时间戳（epoch 毫秒）
 * @param error             调用异常（无则 null）
 */
public record TraceLLMEvent(
        String graphId,
        String nodeId,
        String runId,
        String modelId,
        String modelBaseUrl,
        String promptTemplate,
        String response,
        Long durationMs,
        Long startedAtEpochMs,
        Throwable error
) {
}
