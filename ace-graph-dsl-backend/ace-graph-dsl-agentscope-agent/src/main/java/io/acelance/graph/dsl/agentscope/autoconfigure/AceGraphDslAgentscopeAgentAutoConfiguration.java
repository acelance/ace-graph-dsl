package io.acelance.graph.dsl.agentscope.autoconfigure;

import io.acelance.graph.dsl.agent.SecretResolver;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.agentscope.AgentScopeModelFactory;
import io.acelance.graph.dsl.agentscope.AgentScopeSubAgentResolver;
import io.acelance.graph.dsl.agentscope.DefaultAgentScopeModelFactory;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * ace-graph-dsl-agentscope-agent 自动配置（M3 / Q4）。
 *
 * <p>宿主显式依赖本模块后生效；未引入时 {@code impl=AGENTSCOPE} 由校验报「无匹配 Resolver」。</p>
 */
@AutoConfiguration
public class AceGraphDslAgentscopeAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslAgentscopeAgentAutoConfiguration.class);

    public AceGraphDslAgentscopeAgentAutoConfiguration() {
        log.info("启用 ace-graph-dsl-agentscope-agent 模块（AGENTSCOPE 子 Agent / bridge={}）",
                DefaultAgentScopeModelFactory.BRIDGE_ARTIFACT);
    }

    /**
     * 默认模型工厂：ACE Spec → AgentScope OpenAIChatModel。
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeModelFactory.class)
    public AgentScopeModelFactory agentScopeModelFactory(
            ObjectProvider<ModelMountResolver> mountResolver,
            ObjectProvider<SecretResolver> secretResolver) {
        log.info("注册 DefaultAgentScopeModelFactory");
        return new DefaultAgentScopeModelFactory(
                mountResolver.getIfAvailable(),
                secretResolver.getIfAvailable());
    }

    /**
     * AGENTSCOPE 子 Agent 解析器。
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeSubAgentResolver.class)
    public SubAgentResolver agentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry,
                                                       AgentScopeModelFactory modelFactory) {
        log.info("注册 AgentScopeSubAgentResolver");
        return new AgentScopeSubAgentResolver(nodeRegistry, modelFactory);
    }
}
