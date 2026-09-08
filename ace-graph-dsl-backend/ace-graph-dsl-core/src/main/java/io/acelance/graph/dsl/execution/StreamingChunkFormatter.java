package io.acelance.graph.dsl.execution;

/**
 * 流式输出格式定制入口（SPI）。
 *
 * <h2>用途</h2>
 * <p>ace-graph-dsl 默认按「原生 SSE 格式」下发每个流式片段；业务项目在集成时若需与前端
 * 约定私有协议（例如 JSON 协议 chunk、携带 {@code thinking}/{@code isEnd} 等业务字段、
 * 或不输出 {@code type:message} 类片段），只需提供一个 {@code @Bean StreamingChunkFormatter}
 * 实现即可覆盖默认格式，无需改动框架。</p>
 *
 * <h2>优先级</h2>
 * <ol>
 *   <li>存在自定义 {@code StreamingChunkFormatter} Bean → 使用它（业务定制格式）；</li>
 *   <li>否则若自定义了旧的 {@link GraphExecutionEventAdapter} → 委派给它（向后兼容）；</li>
 *   <li>否则使用原生默认格式（{@code DefaultStreamingChunkFormatter}）。</li>
 * </ol>
 *
 * <p>契约：实现必须<strong>绝不抛异常</strong>，异常应由控制器兜底处理。</p>
 */
public interface StreamingChunkFormatter {

    /**
     * 将一个流式片段转换为可序列化的 SSE 负载对象（通常为 {@code Map} 或业务 DTO）。
     *
     * @param ctx 片段上下文（含原始输出、graphId、nodeId、是否最后片段等）
     * @return 可序列化负载对象
     */
    Object format(StreamingContext ctx);
}
