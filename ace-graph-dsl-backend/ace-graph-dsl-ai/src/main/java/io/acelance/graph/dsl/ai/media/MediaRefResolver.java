package io.acelance.graph.dsl.ai.media;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import org.springframework.ai.content.Media;

import java.util.List;

/**
 * 将 {@link MediaRef} 解析为 Spring AI {@link Media}（§8.2.3）。
 */
@FunctionalInterface
public interface MediaRefResolver {

    /**
     * @return 解析成功的列表；失败条目跳过不抛异常
     */
    ResolveResult resolve(LlmRequestContext ctx, List<MediaRef> refs);

    /** 解析结果：成功 Media + 需以文本附注的跳过项 */
    record ResolveResult(List<Media> medias, List<String> skippedNotes) {
        public ResolveResult {
            medias = medias == null ? List.of() : List.copyOf(medias);
            skippedNotes = skippedNotes == null ? List.of() : List.copyOf(skippedNotes);
        }

        public static ResolveResult empty() {
            return new ResolveResult(List.of(), List.of());
        }
    }
}
