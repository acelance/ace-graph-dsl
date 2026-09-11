package io.acelance.graph.dsl.ai.model;

import io.acelance.graph.dsl.agent.SecretResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.runtime.ModelOverride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模型端点解析（框架内部，§4.4.1 / A5）。
 *
 * <p>静态层：{@code modelConfigKey} 与内联二选一（整路），不齐则报错，禁止拼盘。
 * 请求级 Override：仅在此底座上逐字段补丁。</p>
 */
public final class ModelEndpointResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelEndpointResolver.class);

    private enum BaseSource { CONFIG_KEY, INLINE }

    private final ModelMountResolver mountResolver;
    private final SecretResolver secretResolver;

    /**
     * @param mountResolver  可空；走 key 路时必须有
     * @param secretResolver 可空；掩码内联 key 时用于还原
     */
    public ModelEndpointResolver(ModelMountResolver mountResolver, SecretResolver secretResolver) {
        this.mountResolver = mountResolver;
        this.secretResolver = secretResolver;
    }

    /**
     * 解析最终端点。
     *
     * @param ctx      请求上下文（含 ResourceBinding）
     * @param inline   节点内联模型字段
     * @param override 请求级覆盖（可空）
     */
    public ModelEndpoint resolve(LlmRequestContext ctx, InlineModel inline, ModelOverride override) {
        Objects.requireNonNull(ctx, "ctx");
        ResourceBinding b = ctx.binding();
        InlineModel in = inline != null ? inline : new InlineModel(null, null, false, null);

        ModelEndpoint base;
        BaseSource baseSource;
        if (b.enableModel() && isNotBlank(b.modelConfigKey())) {
            base = requireComplete(ctx, resolveByKeyOrFail(ctx, b.modelConfigKey()),
                    "modelConfigKey=" + b.modelConfigKey());
            baseSource = BaseSource.CONFIG_KEY;
        } else {
            base = requireComplete(ctx, fromInline(in), "节点内联模型字段");
            baseSource = BaseSource.INLINE;
        }

        String baseUrl = firstNonBlank(override == null ? null : override.modelBaseUrl(), base.baseUrl());
        String modelId = firstNonBlank(override == null ? null : override.modelId(), base.modelId());
        String apiKey;
        String apiKeySource;
        if (override != null && isNotBlank(override.modelApiKey())) {
            apiKey = override.modelApiKey();
            apiKeySource = "OVERRIDE";
        } else if (baseSource == BaseSource.INLINE && in.apiKeyMasked()) {
            apiKey = resolveMaskedInlineKey(ctx, base.apiKey());
            apiKeySource = "INLINE(masked→resolved)";
        } else {
            apiKey = base.apiKey();
            apiKeySource = baseSource.name();
        }

        log.info("节点 {} 模型解析完成: 静态底座={}, modelId={}, baseUrl={}, apiKey来源={}, 是否有Override补丁={}",
                ctx.nodeId(), baseSource, modelId, baseUrl, apiKeySource,
                override != null && !override.isEmpty());
        return new ModelEndpoint(baseUrl, apiKey, modelId);
    }

    private String resolveMaskedInlineKey(LlmRequestContext ctx, String maskedKey) {
        if (secretResolver == null) {
            log.warn("节点 {} 内联 apiKey 已掩码但无 SecretResolver，原样返回", ctx.nodeId());
            return maskedKey;
        }
        // SecretResolver 以 Spec 为入参：构造仅含掩码 key 的临时 Spec
        GenericAgentSpec tmp = new GenericAgentSpec(
                null, maskedKey, true, null,
                null, null, "tmp", null, null,
                false, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of());
        String real = secretResolver.resolveApiKey(ctx.graphId(), ctx.nodeId(), tmp);
        log.info("节点 {} 内联掩码 apiKey 已还原", ctx.nodeId());
        return real;
    }

    private ModelEndpoint resolveByKeyOrFail(LlmRequestContext ctx, String modelConfigKey) {
        if (mountResolver == null) {
            log.error("节点 {} 未配置 ModelMountResolver，无法解析 modelConfigKey={}",
                    ctx.nodeId(), modelConfigKey);
            throw new IllegalStateException(
                    "未配置 ModelMountResolver，无法解析 modelConfigKey=" + modelConfigKey);
        }
        try {
            return mountResolver.resolve(ctx, modelConfigKey);
        } catch (RuntimeException e) {
            log.error("节点 {} 按 modelConfigKey={} 解析失败，不回落内联",
                    ctx.nodeId(), modelConfigKey, e);
            throw new IllegalStateException(String.format(
                    "节点 %s 的 modelConfigKey=%s 解析失败：%s。请检查 key 与注册中心；临时改用内联请取消勾选 Model",
                    ctx.nodeId(), modelConfigKey, e.getMessage()), e);
        }
    }

    private static ModelEndpoint fromInline(InlineModel inline) {
        if (inline == null) {
            return new ModelEndpoint(null, null, null);
        }
        return new ModelEndpoint(inline.baseUrl(), inline.apiKey(), inline.modelId());
    }

    private static ModelEndpoint requireComplete(LlmRequestContext ctx, ModelEndpoint ep, String which) {
        if (ep != null && isNotBlank(ep.baseUrl()) && isNotBlank(ep.modelId()) && isNotBlank(ep.apiKey())) {
            return ep;
        }
        log.error("节点 {} 的 {} 模型配置不完整: baseUrl={}, modelId={}；不会用其它来源字段拼盘",
                ctx.nodeId(), which,
                ep == null ? null : ep.baseUrl(),
                ep == null ? null : ep.modelId());
        throw new IllegalStateException(String.format(
                "节点 %s 的 %s 不完整（需要 baseUrl + apiKey + modelId）。不会自动用另一路字段补齐。",
                ctx.nodeId(), which));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return isNotBlank(a) ? a : b;
    }
}
