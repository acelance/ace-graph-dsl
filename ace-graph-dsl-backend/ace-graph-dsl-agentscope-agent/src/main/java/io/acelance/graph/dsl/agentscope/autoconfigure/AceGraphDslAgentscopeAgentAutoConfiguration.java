package io.acelance.graph.dsl.agentscope.autoconfigure;

import io.acelance.graph.dsl.agent.SecretResolver;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.agentscope.AgentScopeGenerateOptionsCustomizer;
import io.acelance.graph.dsl.agentscope.AgentScopeModelFactory;
import io.acelance.graph.dsl.agentscope.AgentScopeSubAgentResolver;
import io.acelance.graph.dsl.agentscope.AgentScopeToolAssembly;
import io.acelance.graph.dsl.agentscope.AgentscopeBridgeArtifacts;
import io.acelance.graph.dsl.agentscope.DefaultAgentScopeModelFactory;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.ai.tool.LocalToolResolver;
import io.acelance.graph.dsl.ai.tool.McpToolResolver;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.skill.SkillCatalogResolver;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillResourceLoader;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * ace-graph-dsl-agentscope-agent 自动配置（路径 B + G-MCP 工具桥）。
 *
 * <p>宿主显式依赖本模块后生效；未引入时 {@code impl=AGENTSCOPE} 由校验报「无匹配 Resolver」。</p>
 */
@AutoConfiguration
public class AceGraphDslAgentscopeAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslAgentscopeAgentAutoConfiguration.class);

    public AceGraphDslAgentscopeAgentAutoConfiguration() {
        log.info("启用 ace-graph-dsl-agentscope-agent 模块（路径 B + G-MCP / AGENTSCOPE 子 Agent / bridge={}）",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT);
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
     * G-MCP：ACE SPI → Toolkit 装配器。
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeToolAssembly.class)
    public AgentScopeToolAssembly agentScopeToolAssembly(
            ObjectProvider<McpToolResolver> mcpToolResolver,
            ObjectProvider<LocalToolResolver> localToolResolver,
            ObjectProvider<SkillCatalogResolver> skillCatalog,
            ObjectProvider<SkillContentLoader> skillContent,
            ObjectProvider<SkillResourceLoader> skillResources) {
        log.info("注册 AgentScopeToolAssembly（mcp={}, local={}, skillCatalog={}, skillContent={}）",
                mcpToolResolver.getIfAvailable() != null,
                localToolResolver.getIfAvailable() != null,
                skillCatalog.getIfAvailable() != null,
                skillContent.getIfAvailable() != null);
        return new AgentScopeToolAssembly(
                mcpToolResolver.getIfAvailable(),
                localToolResolver.getIfAvailable(),
                skillCatalog.getIfAvailable(),
                skillContent.getIfAvailable(),
                skillResources.getIfAvailable());
    }

    /**
     * AGENTSCOPE 子 Agent 解析器（G-MCP + G-STREAM-INNER bridge + 模型 GenerateOptions + 多模态 SPI）。
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeSubAgentResolver.class)
    public SubAgentResolver agentScopeSubAgentResolver(
            GraphNodeRegistry nodeRegistry,
            AgentScopeModelFactory modelFactory,
            AgentScopeToolAssembly toolAssembly,
            ObjectProvider<GraphStreamBridge> streamBridge,
            ObjectProvider<AgentScopeGenerateOptionsCustomizer> generateOptionsCustomizer,
            ObjectProvider<MediaRefResolver> mediaRefResolver) {
        GraphStreamBridge bridge = streamBridge.getIfAvailable();
        AgentScopeGenerateOptionsCustomizer options = generateOptionsCustomizer.getIfAvailable();
        MediaRefResolver media = mediaRefResolver.getIfAvailable();
        log.info("注册 AgentScopeSubAgentResolver（G-MCP ToolAssembly, streamBridge={}, "
                        + "generateOptionsCustomizer={}, mediaResolver={}）",
                bridge != null && bridge != GraphStreamBridge.NOOP,
                options != null,
                media != null);
        return new AgentScopeSubAgentResolver(nodeRegistry, modelFactory, toolAssembly, bridge, options, media);
    }
}
