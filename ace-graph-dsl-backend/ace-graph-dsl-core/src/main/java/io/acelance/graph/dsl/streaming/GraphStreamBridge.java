package io.acelance.graph.dsl.streaming;

import reactor.core.publisher.Flux;

/**
 * 流式输出桥接器：在「框架图执行 flux」之外，为自定义节点（如 {@code GenericAgentNode}）
 * 提供一条按 {@code runId} 汇聚 LLM 逐 token 片段的带外通道。
 *
 * <h2>为什么需要它</h2>
 * <p>spring-ai-alibaba-graph 的 {@code CompiledGraph.stream()} 仅对框架<em>内置</em>流式节点
 * 透传逐 token 片段；自定义 {@code AsyncNodeAction}（返回 {@code Map}）无法把逐 token 片段注入该
 * flux。本桥接器让节点在执行期内把 {@link TokenChunk} push 进来，由 {@code GraphExecutionController}
 * 在 {@code /stream} 时把「图 flux」与「桥接 flux」合并后统一下发。</p>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>控制器为本次 {@code threadId(=runId)} 调用 {@link #register(String)} 取得 flux 并订阅；</li>
 *   <li>节点每产出一个 token 调用 {@link #emit(String, TokenChunk)}；</li>
 *   <li>流结束（图执行完成 / 异常）时控制器调用 {@link #complete(String)} 关闭并回收，避免泄漏。</li>
 * </ul>
 *
 * <p>节点侧以「是否存在非 NOOP 的 Bean」判断本次是否开启流式；缺失时退化为阻塞调用，
 * 不会向本桥接器写入任何内容。</p>
 */
public interface GraphStreamBridge {

    /** 空实现：未接入流式桥接时使用，所有操作均为 no-op。 */
    GraphStreamBridge NOOP = new GraphStreamBridge() {
        @Override
        public void emit(String runId, TokenChunk chunk) {
        }

        @Override
        public void complete(String runId) {
        }

        @Override
        public Flux<TokenChunk> register(String runId) {
            return Flux.empty();
        }
    };

    /**
     * 推送一个流式片段。
     *
     * @param runId 本次执行 runId（与 controller 的 threadId 对齐）
     * @param chunk 流式片段（token 文本 + 输出类型 + 是否末段）
     */
    void emit(String runId, TokenChunk chunk);

    /** 关闭并回收指定 runId 的通道（流结束 / 异常时由控制器调用）。 */
    void complete(String runId);

    /**
     * 为指定 runId 注册一条 flux 并订阅。
     *
     * @return 该 runId 的流式片段流；若该 runId 无后续 emit，则为空流
     */
    Flux<TokenChunk> register(String runId);
}
