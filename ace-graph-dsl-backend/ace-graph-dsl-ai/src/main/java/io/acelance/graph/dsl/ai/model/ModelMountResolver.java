package io.acelance.graph.dsl.ai.model;

import io.acelance.graph.dsl.llm.LlmRequestContext;

/**
 * 业务 SPI：仅负责按 modelConfigKey 从注册中心取模型配置（§4.4.1）。
 */
@FunctionalInterface
public interface ModelMountResolver {

    /**
     * @param ctx            请求上下文
     * @param modelConfigKey 配置 key
     * @return 端点；失败应抛异常（框架不回落内联）
     */
    ModelEndpoint resolve(LlmRequestContext ctx, String modelConfigKey);
}
