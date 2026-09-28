package io.acelance.graph.dsl.streamkind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 空 / 非法 streamResponseKind 的唯一归一入口（须携带 graphId）。
 */
public class StreamResponseKindResolver {

    private static final Logger log = LoggerFactory.getLogger(StreamResponseKindResolver.class);

    private final StreamResponseKindCatalog catalog;

    public StreamResponseKindResolver(StreamResponseKindCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "StreamResponseKindCatalog 不能为空");
    }

    /**
     * 解析或回落目录首位。
     *
     * @param graphId 图 ID（目录可按图过滤；禁止写死 null 调用方）
     * @param raw     配置的 kind，可空
     * @return 非空 kind
     */
    public StreamResponseKind resolveOrDefault(String graphId, String raw) {
        List<StreamResponseKindItem> items = new ArrayList<>(catalog.list(graphId));
        items.sort(StreamResponseKindCatalog.ORDER);
        if (items.isEmpty()) {
            throw new IllegalStateException("流式类型目录为空，无法归一化 graphId=" + graphId);
        }
        String fallback = items.get(0).code();
        if (raw == null || raw.isBlank()) {
            log.warn("图 {} 节点未配置 streamResponseKind，回落到目录首位: {}", graphId, fallback);
            return StreamResponseKind.of(fallback);
        }
        String normalized = raw.trim();
        boolean known = items.stream().anyMatch(i -> normalized.equals(i.code()));
        if (!known) {
            log.warn("图 {} 的 streamResponseKind={} 不在目录内，回落首位 {}", graphId, normalized, fallback);
            return StreamResponseKind.of(fallback);
        }
        return StreamResponseKind.of(normalized);
    }
}
