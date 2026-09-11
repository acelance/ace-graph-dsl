package io.acelance.graph.dsl.ai.template;

import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.tool.LocalToolResolver;
import io.acelance.graph.dsl.ai.tool.McpToolResolver;
import io.acelance.graph.dsl.prompt.PromptContentResolver;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.skill.SkillCatalogResolver;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillResourceLoader;
import io.acelance.graph.dsl.streamkind.StreamResponseKindResolver;

import java.util.Objects;

/**
 * Resolver / Factory 单例依赖集合（方案 §4.5.2 / P3.5）。
 *
 * <p>由自动配置组装一次，随 {@link StreamingLlmTemplate} 复用。</p>
 */
public record LlmResolvers(
        PromptContentResolver prompts,
        PromptRenderer promptRenderer,
        ModelEndpointResolver modelEndpoints,
        ChatModelFactory chatModels,
        LocalToolResolver localTools,
        McpToolResolver mcpTools,
        SkillCatalogResolver skillCatalog,
        SkillContentLoader skillContent,
        SkillResourceLoader skillResources,
        MediaRefResolver media,
        StreamResponseKindResolver kinds
) {
    public LlmResolvers {
        Objects.requireNonNull(prompts, "PromptContentResolver 不能为空");
        Objects.requireNonNull(promptRenderer, "PromptRenderer 不能为空");
        Objects.requireNonNull(modelEndpoints, "ModelEndpointResolver 不能为空");
        Objects.requireNonNull(chatModels, "ChatModelFactory 不能为空");
        Objects.requireNonNull(localTools, "LocalToolResolver 不能为空");
        Objects.requireNonNull(mcpTools, "McpToolResolver 不能为空");
        Objects.requireNonNull(skillCatalog, "SkillCatalogResolver 不能为空");
        Objects.requireNonNull(skillContent, "SkillContentLoader 不能为空");
        Objects.requireNonNull(skillResources, "SkillResourceLoader 不能为空");
        Objects.requireNonNull(media, "MediaRefResolver 不能为空");
        // kinds 可选：ai 模块单独使用时 starter 可能尚未装配
    }
}
