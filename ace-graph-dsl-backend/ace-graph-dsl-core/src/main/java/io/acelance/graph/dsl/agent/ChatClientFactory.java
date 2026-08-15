package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;

/**
 * 据节点元数据构建 {@link AgentChatClient} 的工厂 SPI。
 *
 * <p>core 内置 {@link StubChatClientFactory}（无真实 LLM 时返回固定结构）；
 * 引入可选模块 {@code ace-graph-dsl-agent} 后由 {@code DefaultChatClientFactory}
 * （基于 spring-ai OpenAiChatModel）覆盖生效。</p>
 */
public interface ChatClientFactory {

    /**
     * 为指定节点构建客户端。
     *
     * @param spec    通用 agent 节点元数据
     * @param graphId 所属图 ID（用于 secret 解析命名空间）
     * @param nodeId  节点 ID（用于 secret 解析命名空间）
     * @return 可执行客户端
     */
    AgentChatClient create(GenericAgentSpec spec, String graphId, String nodeId);
}
