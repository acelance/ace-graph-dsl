package io.acelance.graph.dsl.ai.media;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.media.MediaUrlSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 缺省 MediaRefResolver：显式 mime + URL 扩展名（§8.2.2 第 1、3 级）；含 SSRF / 条数限制。
 *
 * <p>不做 HEAD / 魔数（网络策略交业务扩展实现）。</p>
 */
public class DefaultMediaRefResolver implements MediaRefResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultMediaRefResolver.class);

    public static final int DEFAULT_MAX_ITEMS = 10;

    private static final Map<String, String> EXT_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("mp4", "video/mp4")
    );

    private final int maxItems;

    public DefaultMediaRefResolver() {
        this(DEFAULT_MAX_ITEMS);
    }

    public DefaultMediaRefResolver(int maxItems) {
        this.maxItems = Math.max(1, maxItems);
    }

    @Override
    public ResolveResult resolve(LlmRequestContext ctx, List<MediaRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return ResolveResult.empty();
        }
        String nodeId = ctx != null ? ctx.nodeId() : "?";
        List<MediaRef> limited = refs;
        if (refs.size() > maxItems) {
            log.warn("节点 {} media 条数 {} 超过上限 {}，已截断", nodeId, refs.size(), maxItems);
            limited = refs.subList(0, maxItems);
        }
        List<Media> medias = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (MediaRef ref : limited) {
            if (ref == null || !ref.hasUrl()) {
                log.warn("节点 {} 跳过无 url 的 MediaRef", nodeId);
                continue;
            }
            if (!MediaUrlSafety.isAllowed(ref.url(), nodeId)) {
                skipped.add("附件：" + ref.url() + "（URL 不安全，已跳过）");
                continue;
            }
            String mime = resolveMime(ref);
            if (mime == null) {
                log.warn("节点 {} media mime 无法识别，跳过挂载: url={}", nodeId, abbreviate(ref.url()));
                skipped.add("附件：" + ref.url() + "（类型未识别）");
                continue;
            }
            try {
                MimeType mimeType = MimeTypeUtils.parseMimeType(mime);
                Media media = Media.builder()
                        .mimeType(mimeType)
                        .data(URI.create(ref.url()))
                        .id(ref.mediaId())
                        .build();
                medias.add(media);
                log.info("节点 {} 挂载 media: mime={}, url={}", nodeId, mime, abbreviate(ref.url()));
            } catch (Exception e) {
                log.warn("节点 {} 构造 Media 失败 url={}: {}", nodeId, abbreviate(ref.url()), e.getMessage());
                skipped.add("附件：" + ref.url() + "（解析失败）");
            }
        }
        return new ResolveResult(medias, skipped);
    }

    private static String resolveMime(MediaRef ref) {
        if (ref.mime() != null && !ref.mime().isBlank()) {
            return ref.mime().trim();
        }
        String path = ref.url();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXT_MIME.get(ext);
    }

    private static String abbreviate(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 96 ? url.substring(0, 96) + "…" : url;
    }
}
