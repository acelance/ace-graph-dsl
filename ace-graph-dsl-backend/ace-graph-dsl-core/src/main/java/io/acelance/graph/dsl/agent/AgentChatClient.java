package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;

import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;

/**
 * 抽象 LLM 调用客户端（core 本地接口，隔离 spring-ai ChatClient 类型）。
 *
 * <p>真实实现（如基于 spring-ai OpenAiChatModel 的适配）放在可选模块，
 * core 内置 {@link StubChatClientFactory} 便于无真实 LLM 时端到端验证。</p>
 */
public interface AgentChatClient {

    /**
     * 执行一次模型调用。
     *
     * @param promptTemplate 渲染后的 prompt（内联或 promptKey 解析得到）
     * @param variables      prompt 中的 {{key}} 占位对应的变量值
     * @param spec           节点元数据（含模型/baseUrl/key 等，已由 SecretResolver 还原 api-key）
     * @return 模型回复文本
     */
    String call(String promptTemplate, Map<String, Object> variables, GenericAgentSpec spec);

    /**
     * 带工具的模型调用（tool-calling）。
     *
     * <p>默认降级为无工具调用，保证既有实现不受影响；支持 function calling 的适配器
     * 应覆写此方法，把 {@link AgentTool} 注册为模型可调用的工具。</p>
     *
     * @param tools 由 {@link McpToolProvider} 解析出的工具集合（可能为空）
     */
    default String call(String promptTemplate, Map<String, Object> variables,
                        GenericAgentSpec spec, List<AgentTool> tools) {
        return call(promptTemplate, variables, spec);
    }

    /**
     * 流式模型调用（逐 token 返回）。
     *
     * <p>默认实现退化为单次 {@link #call} 的单个片段，保证未覆写时仍可工作；
     * 引入真实 LLM 适配器（如 {@code ace-graph-dsl-agent}）后应覆写此方法以返回真正的
     * 逐 token {@link Flux}。通用 agent 节点会订阅该 Flux 并经 {@link io.acelance.graph.dsl.streaming.GraphStreamBridge}
     * 把每个片段透传给前端 SSE。</p>
     *
     * @param promptTemplate 渲染后的 prompt
     * @param variables      prompt 变量
     * @param spec           节点元数据
     * @return 逐 token 文本片段流
     */
    default Flux<String> stream(String promptTemplate, Map<String, Object> variables, GenericAgentSpec spec) {
        return Flux.just(call(promptTemplate, variables, spec));
    }

    /**
     * 带工具的流式模型调用。
     *
     * @see #stream(String, Map, GenericAgentSpec)
     */
    default Flux<String> stream(String promptTemplate, Map<String, Object> variables,
                                GenericAgentSpec spec, List<AgentTool> tools) {
        return Flux.just(call(promptTemplate, variables, spec, tools));
    }
}
