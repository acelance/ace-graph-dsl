package io.acelance.graph.dsl.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LRU 缓存的 {@link ChatModelFactory} 装饰器（§4.4 / CachingChatModelFactory）。
 *
 * <p>缓存键：{@code baseUrl + "|" + modelId}（不含 apiKey，避免密钥进 key；
 * 同端点换 key 时仍复用连接侧实现由底层决定）。</p>
 */
public final class CachingChatModelFactory implements ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(CachingChatModelFactory.class);

    /** 默认 LRU 容量 */
    public static final int DEFAULT_MAX_SIZE = 32;

    private final ChatModelFactory delegate;
    private final int maxSize;
    private final Map<String, ChatModel> cache;

    public CachingChatModelFactory(ChatModelFactory delegate) {
        this(delegate, DEFAULT_MAX_SIZE);
    }

    public CachingChatModelFactory(ChatModelFactory delegate, int maxSize) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize 必须 >= 1");
        }
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ChatModel> eldest) {
                boolean evict = size() > CachingChatModelFactory.this.maxSize;
                if (evict) {
                    log.info("ChatModel LRU 淘汰: key={}", eldest.getKey());
                }
                return evict;
            }
        };
    }

    @Override
    public ChatModel create(ModelEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String key = cacheKey(endpoint);
        synchronized (cache) {
            ChatModel hit = cache.get(key);
            if (hit != null) {
                log.debug("ChatModel 缓存命中: {}", key);
                return hit;
            }
            log.info("ChatModel 缓存未命中，创建: modelId={}, baseUrl={}",
                    endpoint.modelId(), endpoint.baseUrl());
            ChatModel created = delegate.create(endpoint);
            if (created == null) {
                throw new IllegalStateException("ChatModelFactory 返回 null: " + key);
            }
            cache.put(key, created);
            return created;
        }
    }

    /** 测试 / 运维：当前缓存条目数 */
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** 热刷新：清空全部 ChatModel 缓存（下次 create 重建） */
    public void invalidateAll() {
        synchronized (cache) {
            int n = cache.size();
            cache.clear();
            log.info("ChatModel 缓存已清空: cleared={}", n);
        }
    }

    /**
     * 热刷新：按 baseUrl 前缀淘汰（dataId 变更后无法精确映射到 modelId 时可用）。
     */
    public int invalidateByBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return 0;
        }
        String prefix = baseUrl.trim() + "|";
        synchronized (cache) {
            int before = cache.size();
            cache.entrySet().removeIf(e -> e.getKey() != null && e.getKey().startsWith(prefix));
            int removed = before - cache.size();
            if (removed > 0) {
                log.info("ChatModel 缓存按 baseUrl 淘汰: baseUrl={}, removed={}", baseUrl, removed);
            }
            return removed;
        }
    }

    private static String cacheKey(ModelEndpoint ep) {
        return nullToEmpty(ep.baseUrl()) + "|" + nullToEmpty(ep.modelId());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
