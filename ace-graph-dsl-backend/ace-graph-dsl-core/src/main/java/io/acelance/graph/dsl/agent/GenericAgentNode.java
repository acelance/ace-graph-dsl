package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 通用 agent 模板节点（GENERIC_AGENT）。
 *
 * <p>完全由 {@link GenericAgentSpec} 元数据驱动：据模型/prompt/skill/mcp/tools 动态装配
 * {@link AgentChatClient} 并执行，结果写回 {@code spec.outputKey()}（默认 agent_result）。
 * 一般模型调用无需再写内嵌 Java 节点。</p>
 *
 * <p>所有外部依赖（ChatClientFactory / SecretResolver / PromptRepository / SkillRegistry /
 * McpToolProvider）均经 Spring 容器可选解析，缺失时回落到 core 内置默认实现，保证开箱可用。</p>
 */
public class GenericAgentNode implements RegisteredGraphNode {

    private final String nodeId;
    private final String graphId;
    private final GenericAgentSpec spec;
    private final ApplicationContext spring;

    public GenericAgentNode(String nodeId, String graphId, GenericAgentSpec spec, ApplicationContext spring) {
        this.nodeId = nodeId;
        this.graphId = graphId;
        this.spec = spec;
        this.spring = spring;
    }

    @Override
    public GraphNodeDescriptor descriptor() {
        Set<String> inputKeys = spec.inputKeySet();
        Set<String> outputKeys = Set.of(spec.effectiveOutputKey());
        Map<String, GraphNodeDescriptor.PropertySchema> props = new LinkedHashMap<>();
        props.put("modelId", new GraphNodeDescriptor.PropertySchema("string", "模型", spec.modelId(), Map.of()));
        props.put("modelBaseUrl", new GraphNodeDescriptor.PropertySchema("string", "模型端点", spec.modelBaseUrl(), Map.of()));
        props.put("prompt", new GraphNodeDescriptor.PropertySchema("string", "prompt 模板", spec.prompt(), Map.of()));
        props.put("outputKey", new GraphNodeDescriptor.PropertySchema("string", "输出 key", spec.effectiveOutputKey(), Map.of()));
        return new GraphNodeDescriptor(
                nodeId,
                "通用 Agent: " + (spec.modelId() != null ? spec.modelId() : nodeId),
                GraphNodeDescriptor.CATEGORY_GENERIC_AGENT,
                "元数据驱动的通用 agent 节点（模型/prompt/skill/mcp/tools）",
                inputKeys, outputKeys, true, "1.0.0", props,
                NodeOrigin.GENERIC_AGENT, Set.of());
    }

    @Override
    public NodeAction toAction(NodeRuntimeContext ctx) {
        return (OverAllState state) -> {
            // 1. 还原 api-key
            SecretResolver secretResolver = optionalBean(SecretResolver.class, new EnvSecretResolver());
            GenericAgentSpec resolved = spec.withResolvedApiKey(
                    secretResolver.resolveApiKey(graphId, nodeId, spec));

            // 2. 解析 prompt（内联优先，否则 promptKey）
            String promptTemplate = resolvePrompt(resolved);

            // 3. 构造变量（来自 inputKeys 对应 state）
            Map<String, Object> variables = new LinkedHashMap<>();
            for (String key : resolved.inputKeySet()) {
                variables.put(key, state.value(key).orElse(null));
            }

            // 4. 装配客户端并执行
            ChatClientFactory factory = optionalBean(ChatClientFactory.class, new StubChatClientFactory());
            AgentChatClient client = factory.create(resolved, graphId, nodeId);
            String reply = client.call(promptTemplate, variables, resolved);

            // 5. 写回输出 key
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(resolved.effectiveOutputKey(), reply);
            return result;
        };
    }

    private String resolvePrompt(GenericAgentSpec resolved) {
        StringBuilder sb = new StringBuilder();
        // skill：内联或 skillKey 作为系统指令前缀
        if (resolved.skill() != null && !resolved.skill().isBlank()) {
            sb.append(resolved.skill()).append("\n\n");
        } else if (resolved.skillKey() != null && !resolved.skillKey().isBlank()) {
            SkillRegistry skillRegistry = optionalBean(SkillRegistry.class, new InMemorySkillRegistry());
            skillRegistry.load(resolved.skillKey()).ifPresent(s -> sb.append(s).append("\n\n"));
        }
        // prompt：内联或 promptKey
        if (resolved.prompt() != null && !resolved.prompt().isBlank()) {
            sb.append(resolved.prompt());
        } else if (resolved.promptKey() != null && !resolved.promptKey().isBlank()) {
            PromptRepository promptRepository = optionalBean(PromptRepository.class, new InMemoryPromptRepository());
            String loaded = promptRepository.load(resolved.promptKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "promptKey 未找到: " + resolved.promptKey() + " (节点 " + nodeId + ")"));
            sb.append(loaded);
        } else {
            sb.append("（未配置 prompt）");
        }
        return sb.toString();
    }

    /** 可选 Bean 解析：容器中无则回落默认实现；多实现时优先非 Stub。 */
    @SuppressWarnings("unchecked")
    private <T> T optionalBean(Class<T> type, T fallback) {
        try {
            Map<String, T> beans = spring.getBeansOfType(type);
            if (beans.isEmpty()) {
                return fallback;
            }
            if (beans.size() == 1) {
                return beans.values().iterator().next();
            }
            for (T b : beans.values()) {
                if (!(b instanceof StubChatClientFactory)) {
                    return b;
                }
            }
            return beans.values().iterator().next();
        } catch (Exception e) {
            return fallback;
        }
    }
}
