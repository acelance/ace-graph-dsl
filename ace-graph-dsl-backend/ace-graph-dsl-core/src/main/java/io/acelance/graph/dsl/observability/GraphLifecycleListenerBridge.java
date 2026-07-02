package io.acelance.graph.dsl.observability;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.List;
import java.util.Map;

/**
 * 将本库的 {@link GraphExecutionListener} SPI 桥接到 graph-core 的
 * {@link GraphLifecycleListener}，在编译期注入 {@code CompileConfig}。
 *
 * <p>一个桥实例聚合多个 SPI 实现，逐一转发；单个监听器抛错不影响其余监听器与图执行。</p>
 */
public class GraphLifecycleListenerBridge implements GraphLifecycleListener {

    private final List<GraphExecutionListener> delegates;

    public GraphLifecycleListenerBridge(List<GraphExecutionListener> delegates) {
        this.delegates = delegates != null ? delegates : List.of();
    }

    @Override
    public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
        for (GraphExecutionListener l : delegates) {
            safe(() -> l.onStart(nodeId, state, config));
        }
    }

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        for (GraphExecutionListener l : delegates) {
            safe(() -> l.before(nodeId, state, config, curTime));
        }
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        for (GraphExecutionListener l : delegates) {
            safe(() -> l.after(nodeId, state, config, curTime));
        }
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable ex, RunnableConfig config) {
        for (GraphExecutionListener l : delegates) {
            safe(() -> l.onError(nodeId, state, ex, config));
        }
    }

    @Override
    public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
        for (GraphExecutionListener l : delegates) {
            safe(() -> l.onComplete(nodeId, state, config));
        }
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 监听器异常不得影响图执行
        }
    }
}
