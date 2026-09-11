package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带按 server key 缓存与热刷新的 {@link McpToolResolver} 装饰器（P2.2）。
 *
 * <p>缓存粒度：单个 {@code mcpKey}（非整批 keys），便于 Nacos 变更时只失效一台 MCP。
 * 同 uniqueName 去重仍由 {@link ToolDeduper} 负责（先到保留 + warn，避免热刷新抖动）。</p>
 *
 * <p>可选 TTL：{@code ttlMillis <= 0} 表示仅主动 {@link #invalidate} 时失效；
 * {@code > 0} 时过期条目在下次读取时被动淘汰。</p>
 */
public final class CachingMcpToolResolver implements McpToolResolver, McpToolCache {

    private static final Logger log = LoggerFactory.getLogger(CachingMcpToolResolver.class);

    /** 默认 LRU 容量（按 mcpKey） */
    public static final int DEFAULT_MAX_SIZE = 64;

    /** 默认仅主动失效，不设 TTL */
    public static final long DEFAULT_TTL_MILLIS = 0L;

    private final McpToolResolver delegate;
    private final int maxSize;
    private final long ttlMillis;
    private final Map<String, CacheEntry> cache;

    public CachingMcpToolResolver(McpToolResolver delegate) {
        this(delegate, DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS);
    }

    public CachingMcpToolResolver(McpToolResolver delegate, int maxSize, long ttlMillis) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize 必须 >= 1");
        }
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        // access-order LinkedHashMap 作 LRU；外层 ConcurrentHashMap 存最终条目以便按 key 失效
        // 实际采用 ConcurrentHashMap + 手动 trim，避免 LinkedHashMap 与并发冲突
        this.cache = new ConcurrentHashMap<>();
    }

    @Override
    public List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> mcpKeys) {
        Objects.requireNonNull(ctx, "ctx");
        if (mcpKeys == null || mcpKeys.isEmpty()) {
            return List.of();
        }
        String nodeId = ctx.nodeId();
        List<NamedToolCallback> out = new ArrayList<>();
        List<String> missKeys = new ArrayList<>();
        for (String raw : mcpKeys) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.trim();
            List<NamedToolCallback> hit = getIfFresh(key);
            if (hit != null) {
                log.debug("节点 {} MCP 工具缓存命中: key={}, tools={}", nodeId, key, hit.size());
                out.addAll(hit);
            } else {
                missKeys.add(key);
            }
        }
        if (!missKeys.isEmpty()) {
            log.info("节点 {} MCP 工具缓存未命中 keys={}，委派加载", nodeId, missKeys);
            // 仅对 miss 的 keys 调用 delegate，避免命中项被重复拉取
            List<NamedToolCallback> loaded = safeResolve(ctx, missKeys);
            Map<String, List<NamedToolCallback>> byServer = groupByServerKey(loaded);
            for (String key : missKeys) {
                List<NamedToolCallback> tools = byServer.getOrDefault(key, List.of());
                put(key, tools);
                out.addAll(tools);
            }
        }
        log.info("节点 {} CachingMcpToolResolver 返回工具数={}（cacheSize={}）",
                nodeId, out.size(), size());
        return List.copyOf(out);
    }

    @Override
    public void invalidate(String mcpKey) {
        if (mcpKey == null || mcpKey.isBlank()) {
            return;
        }
        String key = mcpKey.trim();
        CacheEntry removed = cache.remove(key);
        if (removed != null) {
            log.info("MCP 工具缓存已失效: key={}, hadTools={}", key, removed.tools().size());
        } else {
            log.info("MCP 工具缓存失效请求无条目: key={}", key);
        }
    }

    @Override
    public void invalidateAll() {
        int before = cache.size();
        cache.clear();
        log.info("MCP 工具缓存已全部清空: removed={}", before);
    }

    @Override
    public int size() {
        return cache.size();
    }

    private List<NamedToolCallback> getIfFresh(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (ttlMillis > 0 && (System.currentTimeMillis() - entry.cachedAtMillis()) > ttlMillis) {
            cache.remove(key, entry);
            log.info("MCP 工具缓存 TTL 过期已剔除: key={}, ttlMillis={}", key, ttlMillis);
            return null;
        }
        return entry.tools();
    }

    private void put(String key, List<NamedToolCallback> tools) {
        List<NamedToolCallback> snapshot = tools == null ? List.of() : List.copyOf(tools);
        cache.put(key, new CacheEntry(snapshot, System.currentTimeMillis()));
        trimIfNeeded();
        log.info("MCP 工具已写入缓存: key={}, tools={}, cacheSize={}", key, snapshot.size(), cache.size());
    }

    private void trimIfNeeded() {
        if (cache.size() <= maxSize) {
            return;
        }
        // 简单淘汰：按插入时间最早的若干条（非严格 LRU，足够运维场景）
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().cachedAtMillis(), b.getValue().cachedAtMillis()));
        int overflow = cache.size() - maxSize;
        for (int i = 0; i < overflow && i < entries.size(); i++) {
            String evictKey = entries.get(i).getKey();
            cache.remove(evictKey);
            log.info("MCP 工具缓存超限淘汰: key={}, maxSize={}", evictKey, maxSize);
        }
    }

    private List<NamedToolCallback> safeResolve(LlmRequestContext ctx, List<String> keys) {
        try {
            List<NamedToolCallback> loaded = delegate.resolve(ctx, keys);
            return loaded == null ? List.of() : loaded;
        } catch (Exception e) {
            log.error("节点 {} MCP delegate 解析失败 keys={}: {}",
                    ctx.nodeId(), keys, e.getMessage(), e);
            return List.of();
        }
    }

    private static Map<String, List<NamedToolCallback>> groupByServerKey(List<NamedToolCallback> loaded) {
        Map<String, List<NamedToolCallback>> byServer = new LinkedHashMap<>();
        for (NamedToolCallback t : loaded) {
            String sk = t.serverKey();
            if (sk == null || sk.isBlank()) {
                log.warn("MCP 工具缺少 serverKey，无法写入按 key 缓存: uniqueName={}", t.uniqueName());
                continue;
            }
            byServer.computeIfAbsent(sk, k -> new ArrayList<>()).add(t);
        }
        return byServer;
    }

    private record CacheEntry(List<NamedToolCallback> tools, long cachedAtMillis) {
    }
}
