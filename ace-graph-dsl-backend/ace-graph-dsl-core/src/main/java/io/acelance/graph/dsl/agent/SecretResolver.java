package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;

/**
 * api-key 还原 SPI：将落库时的掩码/引用值还原为真实 key。
 * 默认实现 {@link EnvSecretResolver}（查环境变量 / 配置 Map）。
 */
public interface SecretResolver {

    /**
     * 还原 api-key。
     *
     * @param graphId 图 ID（命名空间）
     * @param nodeId  节点 ID（命名空间）
     * @param spec    节点元数据（可能 apiKeyMasked=true）
     * @return 可实际调用的真实 api-key
     */
    String resolveApiKey(String graphId, String nodeId, GenericAgentSpec spec);
}
