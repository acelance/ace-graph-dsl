package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置桩实现：无真实 LLM 时也可端到端验证通用 agent 节点。
 *
 * <p>同时实现 {@link ChatClientFactory} 与 {@link AgentChatClient}：作为
 * {@code ChatClientFactory} 注册为 Spring Bean，使 core 在缺少真实适配器模块时
 * 仍能构建出可用节点；{@link #call} 返回包含模型/变量信息的确定性 JSON，
 * 便于校验「元数据驱动模板节点」整条链路是否打通。</p>
 *
 * <p>引入可选模块 {@code ace-graph-dsl-agent} 的 {@code DefaultChatClientFactory}
 * 后（建议标记 {@code @Primary}），将自动覆盖本桩。</p>
 */
@Component
public class StubChatClientFactory implements ChatClientFactory, AgentChatClient {

    @Override
    public AgentChatClient create(GenericAgentSpec spec, String graphId, String nodeId) {
        return this;
    }

    @Override
    public String call(String promptTemplate, Map<String, Object> variables, GenericAgentSpec spec) {
        return call(promptTemplate, variables, spec, java.util.List.of());
    }

    @Override
    public String call(String promptTemplate, Map<String, Object> variables,
                       GenericAgentSpec spec, java.util.List<AgentTool> tools) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", spec.modelId());
        result.put("baseUrl", spec.modelBaseUrl());
        result.put("prompt", promptTemplate);
        result.put("variables", variables);
        result.put("reply", "【STUB】通用 agent 节点已按元数据装配并执行（未接入真实 LLM）。");
        result.put("toolCount", tools != null ? tools.size() : 0);
        result.put("toolNames", tools != null ? tools.stream().map(AgentTool::name).toList() : java.util.List.of());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        } catch (Exception e) {
            return result.toString();
        }
    }
}
