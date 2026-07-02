package io.acelance.graph.dsl.observability;

import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.Map;

/**
 * 图执行生命周期监听 SPI（节点级）。
 *
 * <p>宿主实现本接口并声明为 Spring {@code @Component} 即可接入图执行可观测能力
 * （如 Langfuse / OpenTelemetry trace、节点耗时统计、链路埋点）。所有实现会被
 * {@code DynamicGraphBuilder} 自动收集，桥接到 graph-core 的执行生命周期。</p>
 *
 * <p>所有方法均为默认空实现，按需覆写即可。</p>
 */
public interface GraphExecutionListener {

    /** 图开始执行（首个节点进入前）。 */
    default void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
    }

    /** 节点执行前。{@code curTimeMillis} 为 graph-core 提供的时间戳。 */
    default void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTimeMillis) {
    }

    /** 节点执行后。{@code curTimeMillis} 为 graph-core 提供的时间戳。 */
    default void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTimeMillis) {
    }

    /** 节点执行异常。 */
    default void onError(String nodeId, Map<String, Object> state, Throwable error, RunnableConfig config) {
    }

    /** 图执行完成（最后一个节点之后）。 */
    default void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
    }
}
