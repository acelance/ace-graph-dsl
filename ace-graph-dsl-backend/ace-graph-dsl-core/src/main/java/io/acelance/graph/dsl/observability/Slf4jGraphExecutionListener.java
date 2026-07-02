package io.acelance.graph.dsl.observability;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认监听器：基于 SLF4J 记录节点进入/完成及耗时（logger 名 {@code ace.graph.dsl.trace}）。
 *
 * <p>作为开箱即用的可观测基线，宿主可替换为 Langfuse / OpenTelemetry 等实现。</p>
 */
public class Slf4jGraphExecutionListener implements GraphExecutionListener {

    private static final Logger log = LoggerFactory.getLogger("ace.graph.dsl.trace");

    private final ConcurrentHashMap<String, Long> startNanos = new ConcurrentHashMap<>();

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        startNanos.put(key(nodeId, config), System.nanoTime());
        if (log.isDebugEnabled()) {
            log.debug("node-enter thread={} node={}", threadId(config), nodeId);
        }
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        Long start = startNanos.remove(key(nodeId, config));
        long costMs = start != null ? (System.nanoTime() - start) / 1_000_000 : -1;
        log.info("node-done thread={} node={} costMs={}", threadId(config), nodeId, costMs);
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable error, RunnableConfig config) {
        startNanos.remove(key(nodeId, config));
        log.warn("node-error thread={} node={} error={}", threadId(config), nodeId,
                error != null ? error.toString() : "unknown");
    }

    private static String key(String nodeId, RunnableConfig config) {
        return threadId(config) + "::" + nodeId;
    }

    private static String threadId(RunnableConfig config) {
        return config != null && config.threadId().isPresent() ? config.threadId().get() : "-";
    }
}
