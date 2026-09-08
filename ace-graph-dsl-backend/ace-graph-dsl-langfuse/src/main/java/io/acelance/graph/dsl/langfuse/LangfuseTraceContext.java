package io.acelance.graph.dsl.langfuse;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Langfuse trace / span 关联上下文（进程内）。
 *
 * <p>在 {@link LangfuseGraphExecutionListener}（开 trace + 每节点 span）与
 * {@link LangfuseTraceRecorder}（节点内 LLM generation）之间共享 runId→traceId、runId+nodeId→spanId
 * 的映射，使 generation 能挂到正确的 span 之下。键均为进程内、按 runId 隔离，不跨请求串用。</p>
 */
@Component
public class LangfuseTraceContext {

    private final ConcurrentHashMap<String, String> traceByRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> spanByNode = new ConcurrentHashMap<>();

    public String getOrCreateTrace(String runId) {
        return traceByRun.computeIfAbsent(runId, k -> UUID.randomUUID().toString());
    }

    public String getTrace(String runId) {
        return traceByRun.get(runId);
    }

    public void putSpan(String runId, String nodeId, String spanId) {
        spanByNode.put(key(runId, nodeId), spanId);
    }

    public String getSpan(String runId, String nodeId) {
        return spanByNode.get(key(runId, nodeId));
    }

    public void removeSpan(String runId, String nodeId) {
        spanByNode.remove(key(runId, nodeId));
    }

    public void removeTrace(String runId) {
        traceByRun.remove(runId);
        // span 由 removeSpan 逐个清理；此处不遍历，避免与进行中的节点争用
    }

    private static String key(String runId, String nodeId) {
        return runId + "::" + nodeId;
    }
}
