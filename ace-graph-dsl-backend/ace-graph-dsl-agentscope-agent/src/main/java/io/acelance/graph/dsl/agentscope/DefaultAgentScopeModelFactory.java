package io.acelance.graph.dsl.agentscope;

import io.acelance.graph.dsl.agent.SecretResolver;
import io.acelance.graph.dsl.ai.model.ModelEndpoint;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 默认 {@link AgentScopeModelFactory}：enableModel+modelConfigKey 走 {@link ModelMountResolver}
 *（须带运行时 {@code agentCode}），否则用 Spec 内联构造 AS 2.x {@link OpenAIChatModel}。
 *
 * <p>G-STREAM-INNER：{@code stream(true)}，由 Adapter 经 {@code ReActAgent.stream} 消费增量。</p>
 */
public final class DefaultAgentScopeModelFactory implements AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentScopeModelFactory.class);

    /** @deprecated 请用 {@link AgentscopeBridgeArtifacts#BRIDGE_ARTIFACT} */
    @Deprecated
    public static final String BRIDGE_ARTIFACT = AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT;

    private final ModelMountResolver mountResolver;
    private final SecretResolver secretResolver;

    /**
     * @param mountResolver  可空；走 key 路时必须有
     * @param secretResolver 可空；掩码 apiKey 还原
     */
    public DefaultAgentScopeModelFactory(ModelMountResolver mountResolver, SecretResolver secretResolver) {
        this.mountResolver = mountResolver;
        this.secretResolver = secretResolver;
    }

    @Override
    public Model create(String graphId, String nodeId, String agentId, GenericAgentSpec spec) {
        return create(graphId, nodeId, agentId, spec, "");
    }

    @Override
    public Model create(String graphId, String nodeId, String agentId, GenericAgentSpec spec,
                        String agentCode) {
        Objects.requireNonNull(spec, "spec");
        ModelEndpoint endpoint = resolveEndpoint(graphId, nodeId, agentId, spec, agentCode);
        if (!StringUtils.hasText(endpoint.modelId())
                || !StringUtils.hasText(endpoint.baseUrl())
                || !StringUtils.hasText(endpoint.apiKey())) {
            throw new IllegalStateException("AGENTSCOPE 模型端点不完整: graphId=" + graphId
                    + ", nodeId=" + nodeId + ", agentId=" + agentId
                    + ", modelId=" + endpoint.modelId() + ", baseUrl=" + endpoint.baseUrl());
        }
        log.info("AGENTSCOPE 模型就绪, bridge={}, graphId={}, nodeId={}, agentId={}, agentCode={}, "
                        + "modelId={}, baseUrl={}, stream=true",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, graphId, nodeId, agentId, agentCode,
                endpoint.modelId(), endpoint.baseUrl());
        return OpenAIChatModel.builder()
                .apiKey(endpoint.apiKey())
                .baseUrl(endpoint.baseUrl())
                .modelName(endpoint.modelId())
                .stream(true)
                .build();
    }

    private ModelEndpoint resolveEndpoint(String graphId, String nodeId, String agentId,
                                          GenericAgentSpec spec, String agentCode) {
        ResourceBinding binding = ResourceBindings.fromSpec(spec);
        if (binding.enableModel() && StringUtils.hasText(binding.modelConfigKey())) {
            if (mountResolver == null) {
                throw new IllegalStateException("AGENTSCOPE 需要 ModelMountResolver 解析 modelConfigKey="
                        + binding.modelConfigKey() + ", agentId=" + agentId);
            }
            if (!StringUtils.hasText(agentCode)) {
                throw new IllegalStateException(
                        "AGENTSCOPE modelConfigKey 须在运行时用执行入口 agentCode 解析，"
                                + "禁止发布/编译期空 agentCode 挂载: key=" + binding.modelConfigKey()
                                + ", agentId=" + agentId + ", graphId=" + graphId);
            }
            LlmRequestContext ctx = new LlmRequestContext(
                    agentCode.trim(), graphId, nodeId, "", null, binding);
            log.info("AGENTSCOPE 按 modelConfigKey 解析端点（运行时）, graphId={}, nodeId={}, agentId={}, "
                            + "agentCode={}, key={}",
                    graphId, nodeId, agentId, agentCode, binding.modelConfigKey());
            return mountResolver.resolve(ctx, binding.modelConfigKey().trim());
        }
        String apiKey = spec.modelApiKey();
        if (spec.apiKeyMasked() && secretResolver != null) {
            apiKey = secretResolver.resolveApiKey(graphId, agentId, spec);
        }
        return new ModelEndpoint(spec.modelBaseUrl(), apiKey, spec.modelId());
    }
}
