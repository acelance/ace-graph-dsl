package io.acelance.graph.dsl.ai.memory;

import io.acelance.graph.dsl.ai.media.MediaMaterialSupport;
import io.acelance.graph.dsl.media.MediaRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆 USER 落盘前的中性媒体拆分：按 mime 把 {@link MediaRef} 分成图片 URL 与文件 URL。
 *
 * <p>Excel 等办公件不会进入 {@code UserMessage.media}，必须把 URL 交给
 * {@link MemoryUserPersistMetadataResolver} 写成业务 extras。本类<b>不含</b>任何记忆协议键名。</p>
 */
public final class MemoryUserPersistMeta {

    private MemoryUserPersistMeta() {
    }

    public record SplitUrls(List<String> images, List<String> files) {
        public SplitUrls {
            images = images == null ? List.of() : List.copyOf(images);
            files = files == null ? List.of() : List.copyOf(files);
        }

        public static SplitUrls empty() {
            return new SplitUrls(List.of(), List.of());
        }
    }

    /** 按 mime/type 拆图片 URL 与文件 URL（去重）。 */
    public static SplitUrls splitUrls(List<MediaRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return SplitUrls.empty();
        }
        List<String> images = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (MediaRef ref : refs) {
            if (ref == null || !ref.hasUrl()) {
                continue;
            }
            String url = ref.url().trim();
            String mime = MediaMaterialSupport.resolveMime(ref);
            if (MediaMaterialSupport.isModelNative(mime, ref) && mime != null
                    && mime.toLowerCase().startsWith("image/")) {
                if (!images.contains(url)) {
                    images.add(url);
                }
            }
            else if (!files.contains(url)) {
                files.add(url);
            }
        }
        return new SplitUrls(images, files);
    }
}
