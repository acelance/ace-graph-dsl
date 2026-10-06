package io.acelance.graph.dsl.ai.memory;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.List;
import java.util.Map;

/**
 * 记忆 USER 落库 metadata SPI。框架只提供中性载荷（展示正文 / 图片 URL / 文件 URL / 技能展示名），
 * <b>不</b>规定 extras 键名；由业务 Bean 映射到各自记忆协议。
 *
 * <p>未注册 Bean 时不往 {@code UserMessage.metadata} 写 extras。</p>
 */
@FunctionalInterface
public interface MemoryUserPersistMetadataResolver {

    /**
     * @return 写入 UserMessage.metadata 的键值；null/空表示本轮不挂 extras
     */
    Map<String, Object> resolve(MemoryUserPersistMetadataRequest request);

    /**
     * @param ctx             节点上下文
     * @param displayUserText 历史展示用用户原话（可空；与喂给 LLM 的 user 分离）
     * @param imageUrls       模型原生图片 URL
     * @param fileUrls        非原生附件 URL（如 xlsx）
     * @param skillLabels     技能展示名（非业务 extras 键）
     */
    record MemoryUserPersistMetadataRequest(
            LlmRequestContext ctx,
            String displayUserText,
            List<String> imageUrls,
            List<String> fileUrls,
            List<String> skillLabels
    ) {
    }
}
