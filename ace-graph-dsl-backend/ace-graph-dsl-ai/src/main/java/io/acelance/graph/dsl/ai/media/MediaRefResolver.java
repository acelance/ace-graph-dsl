package io.acelance.graph.dsl.ai.media;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import org.springframework.ai.content.Media;

import java.util.List;

/**
 * 将 {@link MediaRef} 解析为模型原生 {@link Media} 与/或工具材料注记（§8.2.3 / §8.3）。
 */
@FunctionalInterface
public interface MediaRefResolver {

    /**
     * @return 解析结果；失败条目进入 {@link ResolveResult#skippedNotes()}，不抛异常
     */
    ResolveResult resolve(LlmRequestContext ctx, List<MediaRef> refs);

    /**
     * @param medias         模型原生多模态（图/音视频等）→ {@code UserMessage.media}
     * @param materialNotes  工具/Skill 可消费的材料说明（xlsx/pdf…）→ 追加到 user 文本
     * @param skippedNotes   真正失败（不安全 / 超限等）→ 追加到 user 文本作告警
     */
    record ResolveResult(List<Media> medias, List<String> materialNotes, List<String> skippedNotes) {
        public ResolveResult {
            medias = medias == null ? List.of() : List.copyOf(medias);
            materialNotes = materialNotes == null ? List.of() : List.copyOf(materialNotes);
            skippedNotes = skippedNotes == null ? List.of() : List.copyOf(skippedNotes);
        }

        public static ResolveResult empty() {
            return new ResolveResult(List.of(), List.of(), List.of());
        }
    }
}
