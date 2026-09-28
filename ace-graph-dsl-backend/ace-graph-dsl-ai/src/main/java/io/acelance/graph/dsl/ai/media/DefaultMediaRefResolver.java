package io.acelance.graph.dsl.ai.media;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.media.MediaUrlSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 缺省 MediaRefResolver：显式 mime + URL 扩展名；含 SSRF / 条数限制；按消费能力分流。
 *
 * <ul>
 *   <li>image/audio/video → {@code medias}</li>
 *   <li>办公文档 / PDF 等 → {@code materialNotes}（不进 UserMessage.media）</li>
 *   <li>不安全 / 无法处理 → {@code skippedNotes}</li>
 * </ul>
 */
public class DefaultMediaRefResolver implements MediaRefResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultMediaRefResolver.class);

    public static final int DEFAULT_MAX_ITEMS = 10;

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
        List<String> materials = new ArrayList<>();
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
            String mime = MediaMaterialSupport.resolveMime(ref);
            if (mime == null) {
                // 仍把 url 交给工具侧（§8.2.2 文本附注），不伪装成模型 Media
                materials.add(MediaMaterialSupport.materialNote(ref.url(), null));
                log.info("节点 {} media mime 未识别，已写入材料注记: url={}",
                        nodeId, MediaMaterialSupport.abbreviate(ref.url()));
                continue;
            }
            if (!MediaMaterialSupport.isModelNative(mime, ref)) {
                materials.add(MediaMaterialSupport.materialNote(ref.url(), mime));
                log.info("节点 {} 材料注记: mime={}, url={}",
                        nodeId, mime, MediaMaterialSupport.abbreviate(ref.url()));
                continue;
            }
            try {
                Media media = Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType(mime))
                        .data(URI.create(ref.url()))
                        .id(ref.mediaId())
                        .build();
                medias.add(media);
                log.info("节点 {} 挂载 media: mime={}, url={}",
                        nodeId, mime, MediaMaterialSupport.abbreviate(ref.url()));
            } catch (Exception e) {
                log.warn("节点 {} 构造 Media 失败 url={}: {}",
                        nodeId, MediaMaterialSupport.abbreviate(ref.url()), e.getMessage());
                skipped.add("附件：" + ref.url() + "（解析失败）");
            }
        }
        return new ResolveResult(medias, materials, skipped);
    }
}
