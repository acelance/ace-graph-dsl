package io.acelance.graph.dsl.saa.autoconfigure;

import io.acelance.graph.dsl.agent.SaaWorkflowNodeFactory;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.ai.memory.GraphNodeMemoryPersister;
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
import java.util.function.BiFunction;

/**
 * ace-graph-dsl-saa-agent 自动配置：注册 Factory / GenericAgent SubAgentResolver。
 *
 * <p>宿主显式依赖本模块后生效；starter 不强制传递。</p>
 *
 * <p>M2：ROUTING 有 {@code modelConfigKey} 时编译期只挂延迟代理，运行时用执行入口
 * {@code agentCode} → {@link ModelMountResolver} + {@link ChatModelFactory}；
 * 无 key 时才回落可选 {@link ChatModel} Bean / 桩端点（集成测）。</p>
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
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<GraphNodeMemoryPersister> memoryPersisterProvider) {
        BiFunction<String, String, ChatModel> routerResolver = (key, agentCode) ->
                resolveRouterByKey(key, agentCode, chatModelFactoryProvider, mountResolverProvider);
        ChatModel fallback = resolveRouterFallback(chatModelFactoryProvider, chatModelProvider);
        GraphNodeMemoryPersister persister = memoryPersisterProvider == null
                ? null
                : memoryPersisterProvider.getIfAvailable();
        log.info("注册 SaaWorkflowNodeFactoryImpl（ROUTING 延迟挂载）, resolverCount={}, routerFallback={}, "
                        + "memoryPersister={}",
                resolvers == null ? 0 : resolvers.size(),
                fallback == null ? "null" : fallback.getClass().getSimpleName(),
                persister == null ? "null" : persister.getClass().getSimpleName());
        return new SaaWorkflowNodeFactoryImpl(resolvers, routerResolver, fallback, persister);
    }

    /**
     * 运行时按 modelConfigKey + agentCode 解析 ROUTING 路由器 ChatModel。
     */
    private static ChatModel resolveRouterByKey(String modelConfigKey,
                                               String agentCode,
                                               ObjectProvider<ChatModelFactory> factoryProvider,
                                               ObjectProvider<ModelMountResolver> mountProvider) {
        if (!StringUtils.hasText(modelConfigKey)) {
            return null;
        }
        if (!StringUtils.hasText(agentCode)) {
            log.error("ROUTING 运行时挂载缺 agentCode, key={}", modelConfigKey);
            throw new IllegalStateException(
                    "缺少 agentCode，无法按 Spec 解析 ROUTING 模型 key=" + modelConfigKey
                            + "（agentCode 仅执行入口提供）");
        }
        ChatModelFactory factory = factoryProvider.getIfAvailable();
        ModelMountResolver mount = mountProvider.getIfAvailable();
        if (factory == null || mount == null) {
            log.error("ROUTING modelConfigKey 无法解析：缺少 ChatModelFactory 或 ModelMountResolver, key={}, agentCode={}",
                    modelConfigKey, agentCode);
            throw new IllegalStateException(
                    "ROUTING 缺少 ChatModelFactory/ModelMountResolver, key=" + modelConfigKey);
        }
        String key = modelConfigKey.trim();
        String code = agentCode.trim();
        ResourceBinding binding = new ResourceBinding(
                false, List.of(),
                true, key,
                false, List.of(),
                false, List.of(), java.util.Map.of(),
                false, List.of());
        LlmRequestContext ctx = new LlmRequestContext(
                code, "", "saa-routing-router", "", null, binding);
        ModelEndpoint endpoint = mount.resolve(ctx, key);
        ChatModel model = factory.create(endpoint);
        log.info("ROUTING 路由器模型已按 modelConfigKey 创建, key={}, agentCode={}, modelId={}",
                key, code, endpoint == null ? null : endpoint.modelId());
        return model;
    }

    /**
     * ROUTING 无 modelConfigKey 时的兜底：优先容器 ChatModel Bean，否则桩端点。
     * <p>有 modelConfigKey 时<strong>不会</strong>走此路径（避免发布期挂载失败误用错模型）。</p>
     */
    private static ChatModel resolveRouterFallback(ObjectProvider<ChatModelFactory> factoryProvider,
                                                  ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel bean = chatModelProvider.getIfAvailable();
        if (bean != null) {
            log.info("ROUTING 无 key 时可用容器 ChatModel 作 fallback, type={}", bean.getClass().getName());
            return bean;
        }
        ChatModelFactory factory = factoryProvider.getIfAvailable();
        if (factory != null) {
            log.info("ROUTING 无 key 时可用 ChatModelFactory 桩端点作 fallback");
            return factory.create(ROUTER_STUB_ENDPOINT);
        }
        log.warn("ROUTING 无 ChatModel fallback：ROUTING 图须提供可解析的 modelConfigKey");
        return null;
    }
}
