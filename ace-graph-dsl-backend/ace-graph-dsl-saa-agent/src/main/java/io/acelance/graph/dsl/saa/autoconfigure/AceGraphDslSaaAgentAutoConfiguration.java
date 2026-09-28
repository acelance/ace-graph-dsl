package io.acelance.graph.dsl.saa.autoconfigure;

import io.acelance.graph.dsl.agent.SaaWorkflowNodeFactory;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.ModelEndpoint;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.saa.GenericAgentSubAgentResolver;
import io.acelance.graph.dsl.saa.SaaWorkflowNodeFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * ace-graph-dsl-saa-agent 自动配置：注册 Factory / GenericAgent SubAgentResolver。
 *
 * <p>宿主显式依赖本模块后生效；starter 不强制传递。</p>
 *
 * <p>M2：ROUTING 路由器模型优先按 {@code modelConfigKey} → {@link ModelMountResolver}
 * + {@link ChatModelFactory}；否则回落可选 {@link ChatModel} Bean / 桩端点。</p>
 */
@AutoConfiguration
public class AceGraphDslSaaAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslSaaAgentAutoConfiguration.class);

    /** ROUTING 无真实模型时的桩端点（仅兜底，生产应配 modelConfigKey） */
    private static final ModelEndpoint ROUTER_STUB_ENDPOINT =
            new ModelEndpoint("http://127.0.0.1/saa-router-stub", "stub", "saa-router-stub");

    public AceGraphDslSaaAgentAutoConfiguration() {
        log.info("启用 ace-graph-dsl-saa-agent 模块（SAA 高阶模式节点 / 方式 A，M2 四 pattern）");
    }

    /**
     * 默认 GENERIC_AGENT 子 Agent 解析器。
     */
    @Bean
    @ConditionalOnMissingBean(GenericAgentSubAgentResolver.class)
    public GenericAgentSubAgentResolver genericAgentSubAgentResolver(GraphNodeRegistry nodeRegistry) {
        log.info("注册 GenericAgentSubAgentResolver");
        return new GenericAgentSubAgentResolver(nodeRegistry);
    }

    /**
     * SAA 高阶节点工厂（SEQUENTIAL / PARALLEL / ROUTING / LOOP）。
     */
    @Bean
    @ConditionalOnMissingBean(SaaWorkflowNodeFactory.class)
    public SaaWorkflowNodeFactory saaWorkflowNodeFactory(
            List<SubAgentResolver> resolvers,
            ObjectProvider<ChatModelFactory> chatModelFactoryProvider,
            ObjectProvider<ModelMountResolver> mountResolverProvider,
            ObjectProvider<ChatModel> chatModelProvider) {
        Function<String, ChatModel> routerResolver = key ->
                resolveRouterByKey(key, chatModelFactoryProvider, mountResolverProvider);
        ChatModel fallback = resolveRouterFallback(chatModelFactoryProvider, chatModelProvider);
        log.info("注册 SaaWorkflowNodeFactoryImpl, resolverCount={}, routerFallback={}",
                resolvers == null ? 0 : resolvers.size(),
                fallback == null ? "null" : fallback.getClass().getSimpleName());
        return new SaaWorkflowNodeFactoryImpl(resolvers, routerResolver, fallback);
    }

    /**
     * 按 modelConfigKey 解析 ROUTING 路由器 ChatModel。
     */
    private static ChatModel resolveRouterByKey(String modelConfigKey,
                                               ObjectProvider<ChatModelFactory> factoryProvider,
                                               ObjectProvider<ModelMountResolver> mountProvider) {
        if (!StringUtils.hasText(modelConfigKey)) {
            return null;
        }
        ChatModelFactory factory = factoryProvider.getIfAvailable();
        ModelMountResolver mount = mountProvider.getIfAvailable();
        if (factory == null || mount == null) {
            log.warn("ROUTING modelConfigKey 无法解析：缺少 ChatModelFactory 或 ModelMountResolver, key={}",
                    modelConfigKey);
            return null;
        }
        String key = modelConfigKey.trim();
        ResourceBinding binding = new ResourceBinding(
                false, List.of(),
                true, key,
                false, List.of(),
                false, List.of(), java.util.Map.of(),
                false, List.of());
        LlmRequestContext ctx = new LlmRequestContext(
                "", "", "saa-routing-router", "", null, binding);
        ModelEndpoint endpoint = mount.resolve(ctx, key);
        ChatModel model = factory.create(endpoint);
        log.info("ROUTING 路由器模型已按 modelConfigKey 创建, key={}, modelId={}",
                key, endpoint == null ? null : endpoint.modelId());
        return model;
    }

    /**
     * ROUTING 兜底模型：优先容器内 ChatModel Bean，否则用 ChatModelFactory 桩端点。
     */
    private static ChatModel resolveRouterFallback(ObjectProvider<ChatModelFactory> factoryProvider,
                                                  ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel bean = chatModelProvider.getIfAvailable();
        if (bean != null) {
            log.info("ROUTING 使用容器 ChatModel 作为 fallback, type={}", bean.getClass().getName());
            return bean;
        }
        ChatModelFactory factory = factoryProvider.getIfAvailable();
        if (factory != null) {
            log.info("ROUTING 使用 ChatModelFactory 桩端点作为 fallback");
            return factory.create(ROUTER_STUB_ENDPOINT);
        }
        log.warn("ROUTING 无 ChatModel fallback：ROUTING 图须提供可解析的 modelConfigKey");
        return null;
    }
}
