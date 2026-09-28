package io.acelance.graph.dsl.agentscope;

import io.acelance.graph.dsl.agent.SecretResolver;
import io.acelance.graph.dsl.ai.model.ModelEndpoint;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 默认 {@link AgentScopeModelFactory}：enableModel+modelConfigKey 走 {@link ModelMountResolver}，
 * 否则用 Spec 内联 baseUrl/apiKey/modelId 构造 {@link OpenAIChatModel}。
 */
public final class DefaultAgentScopeModelFactory implements AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentScopeModelFactory.class);

    /** 桥接坐标（附录 A / 排障） */
    public static final String BRIDGE_ARTIFACT = "spring-ai-alibaba-starter-agentscope";

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
        Objects.requireNonNull(spec, "spec");
        ModelEndpoint endpoint = resolveEndpoint(graphId, nodeId, agentId, spec);
        if (!StringUtils.hasText(endpoint.modelId())
                || !StringUtils.hasText(endpoint.baseUrl())
                || !StringUtils.hasText(endpoint.apiKey())) {
            throw new IllegalStateException("AGENTSCOPE 模型端点不完整: graphId=" + graphId
                    + ", nodeId=" + nodeId + ", agentId=" + agentId
                    + ", modelId=" + endpoint.modelId() + ", baseUrl=" + endpoint.baseUrl());
        }
        log.info("AGENTSCOPE 模型就绪, bridge={}, graphId={}, nodeId={}, agentId={}, modelId={}, baseUrl={}",
                BRIDGE_ARTIFACT, graphId, nodeId, agentId, endpoint.modelId(), endpoint.baseUrl());
        return OpenAIChatModel.builder()
                .apiKey(endpoint.apiKey())
                .baseUrl(endpoint.baseUrl())
                .modelName(endpoint.modelId())
                .stream(false)
                .build();
    }

    private ModelEndpoint resolveEndpoint(String graphId, String nodeId, String agentId, GenericAgentSpec spec) {
        ResourceBinding binding = ResourceBindings.fromSpec(spec);
        if (binding.enableModel() && StringUtils.hasText(binding.modelConfigKey())) {
            if (mountResolver == null) {
                throw new IllegalStateException("AGENTSCOPE 需要 ModelMountResolver 解析 modelConfigKey="
                        + binding.modelConfigKey() + ", agentId=" + agentId);
            }
            LlmRequestContext ctx = new LlmRequestContext("", graphId, nodeId, "", null, binding);
            ModelEndpoint ep = mountResolver.resolve(ctx, binding.modelConfigKey().trim());
            log.info("AGENTSCOPE 按 modelConfigKey 解析端点, agentId={}, key={}", agentId, binding.modelConfigKey());
            return ep;
        }
        String apiKey = spec.modelApiKey();
        if (spec.apiKeyMasked() && secretResolver != null) {
            apiKey = secretResolver.resolveApiKey(graphId, agentId, spec);
        }
        return new ModelEndpoint(spec.modelBaseUrl(), apiKey, spec.modelId());
    }
}
