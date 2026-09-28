package io.acelance.graph.dsl.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 运行期资源加载诊断（P0.5）：收集 miss/error，供日志与 debug 流 resource_miss 事件。
 */
public final class ResourceLoadDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(ResourceLoadDiagnostics.class);

    public enum Level { INFO, WARN, ERROR }

    public record Entry(Level level, String resourceType, String key, String message) {
    }

    private final String nodeId;
    private final List<Entry> entries = new ArrayList<>();

    public ResourceLoadDiagnostics(String nodeId) {
        this.nodeId = nodeId == null ? "" : nodeId;
    }

    /** 资源未命中（配置了 key 但加载不到） */
    public void miss(String resourceType, String key, String message) {
        Entry e = new Entry(Level.WARN, resourceType, key, message);
        entries.add(e);
        log.warn("节点 {} 资源 miss: type={}, key={}, msg={}", nodeId, resourceType, key, message);
    }

    /** 资源加载错误 */
    public void error(String resourceType, String key, String message, Throwable t) {
        Entry e = new Entry(Level.ERROR, resourceType, key, message);
        entries.add(e);
        log.error("节点 {} 资源 error: type={}, key={}, msg={}", nodeId, resourceType, key, message, t);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean hasIssues() {
        return !entries.isEmpty();
    }

    /** 转为 debug 流可下发的 payload 列表 */
    public List<java.util.Map<String, Object>> toDebugPayloads() {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", "resource_miss");
            m.put("node", nodeId);
            m.put("level", e.level().name());
            m.put("resourceType", e.resourceType());
            m.put("key", e.key());
            m.put("message", e.message());
            out.add(m);
        }
        return out;
    }
}
