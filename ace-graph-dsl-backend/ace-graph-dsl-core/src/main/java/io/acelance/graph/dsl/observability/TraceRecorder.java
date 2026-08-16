package io.acelance.graph.dsl.observability;

/**
 * LLM 调用级观测 SPI（与节点级 {@link GraphExecutionListener} 互补）。
 *
 * <p>宿主实现本接口并声明为 Spring {@code @Component}，即可在「每个节点实际发起 LLM 调用」
 * 时收到 {@link TraceLLMEvent}，从而把节点内的模型调用细节（实际模型 / prompt / 响应 / 错误 / 耗时）
 * 接入 Langfuse / OpenTelemetry 等观测后端。</p>
 *
 * <p>core 内置实现为空操作（{@link #NOOP}），未注册时节点零开销、不埋任何点。</p>
 *
 * <p><b>契约约束</b>：实现必须保证 {@link #recordLLM} <b>永不抛出</b>任何异常——它是节点执行
 * {@code finally} 路径的一部分，抛错会直接影响图执行正确性。</p>
 */
public interface TraceRecorder {

    /** 空实现：未接入观测后端时使用。 */
    TraceRecorder NOOP = event -> { };

    /**
     * 记录一次节点内 LLM 调用细节。
     * @param event 调用细节（实际模型、prompt、响应、错误、耗时等）
     */
    void recordLLM(TraceLLMEvent event);
}
