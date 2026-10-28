package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.ai.media.DefaultMediaRefResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.media.MediaRefs;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * AGENTSCOPE 用户消息多模态装配：对齐经典图 {@code StreamingLlmTemplate} 行为。
 *
 * <ul>
 *   <li>模型原生图 → {@link ImageBlock}（URL / Base64）</li>
 *   <li>xlsx / pdf 等 → 材料注记追加到文本</li>
 * </ul>
 */
final class AgentScopeMediaMsgSupport {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeMediaMsgSupport.class);

    /** 平台入口写入的默认 state 键（与 AceGraphPlatformRequestMapper 对齐） */
    static final String DEFAULT_MEDIA_INPUT_KEY = "media_refs";

    private AgentScopeMediaMsgSupport() {
    }

    /**
     * 组装 USER {@link Msg}：文本 + 可选 ImageBlock；材料注记拼进文本。
     *
     * @param renderedText   已渲染 instruction / 兜底 user 文本
     * @param state          父/子 Flow state（含 media_refs）
     * @param mediaInputKey  Spec.mediaInputKey；空则回退 {@link #DEFAULT_MEDIA_INPUT_KEY}
     * @param mediaResolver  可空 → {@link DefaultMediaRefResolver}
     * @param ctx            已带 agentCode/nodeId 的请求上下文
     */
    static Msg buildUserMsg(String renderedText,
                            OverAllState state,
                            String mediaInputKey,
                            MediaRefResolver mediaResolver,
                            LlmRequestContext ctx) {
        String text = renderedText != null ? renderedText : "";
        String key = effectiveMediaKey(mediaInputKey);
        List<MediaRef> refs = MediaRefs.readFrom(state, key);
        if (refs.isEmpty()) {
            log.info("AGENTSCOPE 无 media_refs（key={}），纯文本 Msg, textLen={}", key, text.length());
            return Msg.builder().role(MsgRole.USER).textContent(text).build();
        }

        MediaRefResolver resolver = mediaResolver != null ? mediaResolver : new DefaultMediaRefResolver();
        MediaRefResolver.ResolveResult resolved = resolver.resolve(
                Objects.requireNonNull(ctx, "ctx"), refs);

        String userText = appendNotes(text, resolved.materialNotes(), resolved.skippedNotes());
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(userText).build());

        int imageOk = 0;
        int imageSkip = 0;
        for (Media media : resolved.medias()) {
            ImageBlock image = toImageBlock(media, ctx.nodeId());
            if (image != null) {
                blocks.add(image);
                imageOk++;
            } else {
                imageSkip++;
            }
        }

        log.info("AGENTSCOPE 多模态 Msg 已组装, nodeId={}, mediaKey={}, refs={}, images={}, "
                        + "imageSkip={}, materialNotes={}, skippedNotes={}, textLen={}",
                ctx.nodeId(), key, refs.size(), imageOk, imageSkip,
                resolved.materialNotes().size(), resolved.skippedNotes().size(), userText.length());
        return Msg.builder().role(MsgRole.USER).content(blocks).build();
    }

    /** Spec 未配时回退平台默认键，避免 SAA seed 已透传 media_refs 却读不到。 */
    static String effectiveMediaKey(String mediaInputKey) {
        if (StringUtils.hasText(mediaInputKey)) {
            return mediaInputKey.trim();
        }
        return DEFAULT_MEDIA_INPUT_KEY;
    }

    private static String appendNotes(String text, List<String> materials, List<String> skipped) {
        if ((materials == null || materials.isEmpty()) && (skipped == null || skipped.isEmpty())) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        if (materials != null) {
            for (String note : materials) {
                if (StringUtils.hasText(note)) {
                    sb.append('\n').append(note);
                }
            }
        }
        if (skipped != null) {
            for (String note : skipped) {
                if (StringUtils.hasText(note)) {
                    sb.append('\n').append(note);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Spring {@link Media} → AgentScope {@link ImageBlock}。
     *
     * <p>注意：Spring AI {@code Media.data(URI)} / {@code Media(MimeType,URI)} 会把 URI
     * {@code toString()} 成 {@link String} 存入 data，不是 {@link URI} 实例。</p>
     */
    static ImageBlock toImageBlock(Media media, String nodeId) {
        if (media == null) {
            return null;
        }
        try {
            MimeType mime = media.getMimeType();
            String mediaType = mime != null ? mime.toString() : "image/png";
            Object data = media.getData();
            String url = extractUrl(data);
            if (StringUtils.hasText(url)) {
                return ImageBlock.builder()
                        .source(URLSource.builder().url(url).mimeType(mediaType).build())
                        .build();
            }
            byte[] bytes = extractBytes(media, data);
            if (bytes == null || bytes.length == 0) {
                log.warn("AGENTSCOPE ImageBlock 跳过空字节, nodeId={}, mime={}, dataType={}",
                        nodeId, mediaType, data == null ? null : data.getClass().getSimpleName());
                return null;
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return ImageBlock.builder()
                    .source(Base64Source.builder().mediaType(mediaType).data(b64).build())
                    .build();
        } catch (Exception ex) {
            log.warn("AGENTSCOPE ImageBlock 转换失败, nodeId={}, err={}", nodeId, ex.toString());
            return null;
        }
    }

    /**
     * URI / URL 字符串 → 远程图；Spring AI 对 URI 入参存的是 String。
     */
    private static String extractUrl(Object data) {
        if (data instanceof URI uri) {
            return uri.toString();
        }
        if (data instanceof String s) {
            String t = s.trim();
            if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("data:")) {
                return t;
            }
        }
        return null;
    }

    /** byte[] / Resource；勿对 URL 型 Media 调 getDataAsByteArray（会抛 IllegalStateException）。 */
    private static byte[] extractBytes(Media media, Object data) throws Exception {
        if (data instanceof byte[] raw) {
            return raw;
        }
        if (data instanceof Resource resource) {
            return resource.getContentAsByteArray();
        }
        try {
            return media.getDataAsByteArray();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    /**
     * 构造媒体解析用 {@link LlmRequestContext}（binding 可空则用空 ResourceBinding）。
     */
    static LlmRequestContext mediaContext(String agentCode,
                                          String graphId,
                                          String nodeId,
                                          String runId,
                                          OverAllState state,
                                          ResourceBinding binding) {
        ResourceBinding b = binding != null ? binding : ResourceBinding.disabledAll();
        return new LlmRequestContext(
                agentCode != null ? agentCode : "",
                graphId != null ? graphId : "",
                nodeId != null ? nodeId : "",
                runId != null ? runId : "",
                null,
                state,
                b);
    }
}
