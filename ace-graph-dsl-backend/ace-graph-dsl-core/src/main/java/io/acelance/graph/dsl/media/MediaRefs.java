package io.acelance.graph.dsl.media;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 state 按 {@code mediaInputKey} 容错读取 {@link MediaRef} 列表（§8.2.1.1）。
 */
public final class MediaRefs {

    private static final Logger log = LoggerFactory.getLogger(MediaRefs.class);

    private MediaRefs() {
    }

    /**
     * @param state          图状态，可空
     * @param mediaInputKey  Spec 配置的 key；空则返回空列表
     */
    public static List<MediaRef> readFrom(OverAllState state, String mediaInputKey) {
        if (mediaInputKey == null || mediaInputKey.isBlank()) {
            return List.of();
        }
        if (state == null) {
            log.debug("mediaInputKey={} 但 state 为空，按纯文本处理", mediaInputKey);
            return List.of();
        }
        Object raw;
        try {
            raw = state.value(mediaInputKey.trim()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("读取 mediaInputKey={} 失败: {}", mediaInputKey, e.getMessage());
            return List.of();
        }
        if (raw == null) {
            log.debug("state 无 mediaInputKey={}，按纯文本处理", mediaInputKey);
            return List.of();
        }
        return normalize(raw, mediaInputKey);
    }

    /** 容错：List&lt;Map&gt; / List&lt;MediaRef&gt; / 单 Map / 单 MediaRef / 单 url 字符串 */
    @SuppressWarnings("unchecked")
    public static List<MediaRef> normalize(Object raw, String mediaInputKey) {
        List<MediaRef> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return List.of();
            }
            for (Object item : list) {
                MediaRef ref = toRef(item);
                if (ref != null) {
                    out.add(ref);
                }
            }
            return List.copyOf(out);
        }
        MediaRef single = toRef(raw);
        if (single != null) {
            return List.of(single);
        }
        log.warn("mediaInputKey={} 值类型无法解析为 MediaRef: {}", mediaInputKey,
                raw.getClass().getName());
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static MediaRef toRef(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof MediaRef ref) {
            return ref;
        }
        if (item instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            return new MediaRef(s.trim(), null, null, null);
        }
        if (item instanceof Map<?, ?> map) {
            Object url = map.get("url");
            Object mime = map.get("mime");
            Object mediaId = map.get("mediaId");
            Object type = map.get("type");
            return new MediaRef(
                    url == null ? null : String.valueOf(url),
                    mime == null ? null : String.valueOf(mime),
                    mediaId == null ? null : String.valueOf(mediaId),
                    type == null ? null : String.valueOf(type));
        }
        return null;
    }
}
