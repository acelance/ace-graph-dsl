package io.acelance.graph.dsl.ai.autoconfigure;

import io.acelance.graph.dsl.agent.DefaultGenericAgentNodeFactory;
import io.acelance.graph.dsl.agent.GenericAgentNodeFactory;
import io.acelance.graph.dsl.agent.InMemoryPromptRepository;
import io.acelance.graph.dsl.agent.PromptRepository;
import io.acelance.graph.dsl.agent.SecretResolver;
import io.acelance.graph.dsl.agent.SkillRegistry;
import io.acelance.graph.dsl.ai.media.DefaultMediaRefResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.ai.model.CachingChatModelFactory;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.ai.model.StubChatModelFactory;
import io.acelance.graph.dsl.ai.template.LlmResolvers;
import io.acelance.graph.dsl.ai.template.StreamingLlmTemplate;
import io.acelance.graph.dsl.ai.tool.EmptyLocalToolResolver;
import io.acelance.graph.dsl.ai.tool.EmptyMcpToolResolver;
import io.acelance.graph.dsl.ai.tool.LocalToolResolver;
import io.acelance.graph.dsl.ai.tool.McpToolCache;
import io.acelance.graph.dsl.ai.tool.McpToolResolver;
import io.acelance.graph.dsl.prompt.PromptContentResolver;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.prompt.PromptRepositoryAdapters;
import io.acelance.graph.dsl.skill.InMemorySkillStore;
import io.acelance.graph.dsl.skill.SkillCatalogResolver;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillRegistryAdapters;
import io.acelance.graph.dsl.skill.SkillResourceLoader;
import io.acelance.graph.dsl.streamkind.StreamResponseKindResolver;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * ace-graph-dsl-ai 自动配置：Agent 工厂 + LlmResolvers / Template / Skill / 模型解析默认 Bean。
 */
@AutoConfiguration
public class AceGraphDslAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslAiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(GenericAgentNodeFactory.class)
    public GenericAgentNodeFactory genericAgentNodeFactory(ApplicationContext applicationContext) {
        log.info("注册 DefaultGenericAgentNodeFactory（ace-graph-dsl-ai）");
        return new DefaultGenericAgentNodeFactory(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean(ChatModelFactory.class)
    public ChatModelFactory chatModelFactory() {
        log.info("注册 CachingChatModelFactory(StubChatModelFactory) 作为默认 ChatModel 工厂");
        return new CachingChatModelFactory(new StubChatModelFactory());
    }

    @Bean
    @ConditionalOnMissingBean(ModelEndpointResolver.class)
    public ModelEndpointResolver modelEndpointResolver(
            ObjectProvider<ModelMountResolver> mountResolver,
            ObjectProvider<SecretResolver> secretResolver) {
        log.info("注册 ModelEndpointResolver");
        return new ModelEndpointResolver(mountResolver.getIfAvailable(), secretResolver.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(PromptRenderer.class)
    public PromptRenderer promptRenderer() {
        log.info("注册 PromptRenderer（ace-graph-dsl-ai 默认）");
        return new PromptRenderer();
    }

    @Bean
    @ConditionalOnMissingBean(PromptContentResolver.class)
    public PromptContentResolver promptContentResolver(ObjectProvider<PromptRepository> repositories) {
        PromptRepository repo = repositories.getIfAvailable(InMemoryPromptRepository::new);
        log.info("注册 PromptContentResolver ← PromptRepositoryAdapters({})",
                repo.getClass().getSimpleName());
        return PromptRepositoryAdapters.from(repo);
    }

    @Bean
    @ConditionalOnMissingBean(InMemorySkillStore.class)
    public InMemorySkillStore inMemorySkillStore() {
        log.info("注册 InMemorySkillStore（Skill L1/L2/L3 默认空仓）");
        return new InMemorySkillStore();
    }

    @Bean
    @ConditionalOnMissingBean(SkillCatalogResolver.class)
    public SkillCatalogResolver skillCatalogResolver(InMemorySkillStore store,
                                                     ObjectProvider<SkillRegistry> legacyRegistry) {
        SkillRegistry legacy = legacyRegistry.getIfAvailable();
        if (legacy != null) {
            log.info("SkillCatalogResolver 兜底：SkillRegistryAdapters + InMemorySkillStore 委派");
            SkillCatalogResolver fromLegacy = SkillRegistryAdapters.catalog(legacy);
            return (ctx, keys) -> {
                var fromStore = store.resolve(ctx, keys);
                if (!fromStore.isEmpty()) {
                    return fromStore;
                }
                return fromLegacy.resolve(ctx, keys);
            };
        }
        log.info("注册 SkillCatalogResolver → InMemorySkillStore");
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(SkillContentLoader.class)
    public SkillContentLoader skillContentLoader(InMemorySkillStore store,
                                                 ObjectProvider<SkillRegistry> legacyRegistry) {
        SkillRegistry legacy = legacyRegistry.getIfAvailable();
        if (legacy != null) {
            SkillContentLoader fromLegacy = SkillRegistryAdapters.content(legacy);
            return (ctx, key) -> store.loadBody(ctx, key).or(() -> fromLegacy.loadBody(ctx, key));
        }
        log.info("注册 SkillContentLoader → InMemorySkillStore");
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(SkillResourceLoader.class)
    public SkillResourceLoader skillResourceLoader(InMemorySkillStore store) {
        log.info("注册 SkillResourceLoader → InMemorySkillStore");
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(MediaRefResolver.class)
    public MediaRefResolver mediaRefResolver() {
        log.info("注册 DefaultMediaRefResolver（显式 mime + 扩展名）");
        return new DefaultMediaRefResolver();
    }

    @Bean
    @ConditionalOnMissingBean(LocalToolResolver.class)
    public LocalToolResolver localToolResolver() {
        log.info("注册 EmptyLocalToolResolver（未提供业务本地工具时返回空列表）");
        return EmptyLocalToolResolver.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(McpToolResolver.class)
    public McpToolResolver mcpToolResolver() {
        log.info("注册 EmptyMcpToolResolver（未提供业务 MCP 时返回空列表）");
        return EmptyMcpToolResolver.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(McpToolCache.class)
    public McpToolCache mcpToolCache(McpToolResolver mcpToolResolver) {
        if (mcpToolResolver instanceof McpToolCache cache) {
            log.info("暴露 McpToolCache ← {}", mcpToolResolver.getClass().getSimpleName());
            return cache;
        }
        log.info("McpToolResolver 未实现 McpToolCache，注册 NOOP（业务可自行提供 Bean）");
        return McpToolCache.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean(LlmResolvers.class)
    public LlmResolvers llmResolvers(PromptContentResolver prompts,
                                     PromptRenderer promptRenderer,
                                     ModelEndpointResolver modelEndpoints,
                                     ChatModelFactory chatModels,
                                     LocalToolResolver localTools,
                                     McpToolResolver mcpTools,
                                     SkillCatalogResolver skillCatalog,
                                     SkillContentLoader skillContent,
                                     SkillResourceLoader skillResources,
                                     MediaRefResolver media,
                                     ObjectProvider<StreamResponseKindResolver> kinds) {
        log.info("注册 LlmResolvers（P3.5 单例依赖集合）");
        return new LlmResolvers(
                prompts, promptRenderer, modelEndpoints, chatModels,
                localTools, mcpTools, skillCatalog, skillContent, skillResources,
                media, kinds.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(StreamingLlmTemplate.class)
    public StreamingLlmTemplate streamingLlmTemplate(LlmResolvers llmResolvers,
                                                     ObjectProvider<GraphStreamBridge> streamBridge) {
        log.info("注册 StreamingLlmTemplate ← LlmResolvers");
        return new StreamingLlmTemplate(llmResolvers, streamBridge.getIfAvailable());
    }
}
