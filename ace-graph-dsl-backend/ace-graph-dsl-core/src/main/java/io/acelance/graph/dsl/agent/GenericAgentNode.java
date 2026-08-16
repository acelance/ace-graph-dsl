package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.observability.TraceLLMEvent;
import io.acelance.graph.dsl.observability.TraceRecorder;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.acelance.graph.dsl.runtime.ModelOverride;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 agent 模板节点（GENERIC_AGENT）。
 *
 * <p>完全由 {@link GenericAgentSpec} 元数据驱动：据模型/prompt/skill/mcp/tools 动态装配
 * {@link AgentChatClient} 并执行，结果写回 {@code spec.outputKey()}（默认 agent_result）。
 * 一般模型调用无需再写内嵌 Java 节点。</p>
 *
 * <p>两条使用通道：</p>
 * <ul>
 *   <li><b>内联</b>：图里的 {@code NodeRef.agentSpec} 直接携带元数据，编译期即时构造；</li>
 *   <li><b>注册式</b>：先在设计器创建 agent 节点定义并入库，注册进 {@code GraphNodeRegistry}
 *       后由多个图按 {@code nodeId} 复用。此时注册实例的 {@code graphId} 为空，
 *       编译期通过 {@link #withGraphId(String)} 绑定当前图，避免跨图 secret 命名空间串用。</li>
 * </ul>
 *
 * <p>所有外部依赖（ChatClientFactory / SecretResolver / PromptRepository / SkillRegistry /
 * McpServerRegistry / McpToolProvider）均经 Spring 容器可选解析，缺失时回落到 core 内置
 * 默认实现，保证开箱可用。</p>
 */
public class GenericAgentNode implements RegisteredGraphNode {

    private final String nodeId;
    private final String graphId;
    private final GenericAgentSpec spec;
    private final ApplicationContext spring;
    private final String displayName;
    private final String description;
    private final String version;
    private final Set<String> permissionTags;

    public GenericAgentNode(String nodeId, String graphId, GenericAgentSpec spec, ApplicationContext spring) {
        this(nodeId, graphId, spec, spring, null, null, "1.0.0", Set.of());
    }

    public GenericAgentNode(String nodeId,
                            String graphId,
                            GenericAgentSpec spec,
                            ApplicationContext spring,
                            String displayName,
                            String description,
                            String version,
                            Set<String> permissionTags) {
        this.nodeId = nodeId;
        this.graphId = graphId;
        this.spec = spec;
        this.spring = spring;
        this.displayName = displayName;
        this.description = description;
        this.version = (version == null || version.isBlank()) ? "1.0.0" : version;
        this.permissionTags = permissionTags != null ? permissionTags : Set.of();
    }

    /**
     * 绑定所属图 ID 的副本（注册式节点在图编译期调用）。
     *
     * <p>注册中心里的实例是「无图归属」的共享定义，直接复用会让 {@code SecretResolver}
     * 拿到错误的图命名空间。构建 StateGraph 时按当前图克隆一份即可，注册实例保持不变。</p>
     */
    public GenericAgentNode withGraphId(String targetGraphId) {
        if (java.util.Objects.equals(this.graphId, targetGraphId)) {
            return this;
        }
        return new GenericAgentNode(nodeId, targetGraphId, spec, spring,
                displayName, description, version, permissionTags);
    }

    /** 所属图 ID（注册式节点为 null） */
    public String graphId() {
        return graphId;
    }

    /** 节点元数据 */
    public GenericAgentSpec spec() {
        return spec;
    }

    @Override
    public GraphNodeDescriptor descriptor() {
        Set<String> inputKeys = spec.inputKeySet();
        Set<String> outputKeys = Set.of(spec.effectiveOutputKey());
        Map<String, GraphNodeDescriptor.PropertySchema> props = new LinkedHashMap<>();
        props.put("modelId", new GraphNodeDescriptor.PropertySchema("string", "模型", spec.modelId(), Map.of()));
        props.put("modelBaseUrl", new GraphNodeDescriptor.PropertySchema("string", "模型端点", spec.modelBaseUrl(), Map.of()));
        props.put("prompt", new GraphNodeDescriptor.PropertySchema("string", "prompt 模板", spec.prompt(), Map.of()));
        props.put("promptKey", new GraphNodeDescriptor.PropertySchema("string", "prompt key", spec.promptKey(), Map.of()));
        props.put("outputKey", new GraphNodeDescriptor.PropertySchema("string", "输出 key", spec.effectiveOutputKey(), Map.of()));
        return new GraphNodeDescriptor(
                nodeId,
                effectiveDisplayName(),
                GraphNodeDescriptor.CATEGORY_GENERIC_AGENT,
                description != null ? description : "元数据驱动的通用 agent 节点（模型/prompt/skill/mcp/tools）",
                inputKeys, outputKeys, true, version, props,
                NodeOrigin.GENERIC_AGENT, permissionTags);
    }

    private String effectiveDisplayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return "通用 Agent: " + (spec.modelId() != null ? spec.modelId() : nodeId);
    }

    @Override
    public NodeAction toAction(NodeRuntimeContext ctx) {
        return (OverAllState state) -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            for (String key : spec.inputKeySet()) {
                variables.put(key, state.value(key).orElse(null));
            }
            // 请求级运行上下文来自 state 中的保留键：runId（与 Langfuse trace 对齐）+ 模型覆盖
            String runId = readRunId(state);
            ModelOverrideSpec overrides = readOverrides(state);
            return execute(variables, runId, overrides);
        };
    }

    /**
     * 以给定输入变量执行一次（图内运行路径，可带请求级 runId / 模型覆盖）。
     *
     * <p>抽出这层是为了让「节点定义试跑」与「图内运行」共用同一条装配链路，
     * 避免试跑逻辑与真实执行逻辑漂移。</p>
     *
     * @param variables prompt 变量（通常来自 {@code spec.inputKeySet()} 对应的 state 值）
     * @param runId     本次执行 runId（与 Langfuse trace 对齐；可空 → 不观测）
     * @param overrides 请求级模型覆盖（可空 → 用图定义静态模型）
     * @return {@code { outputKey: 模型回复 }}
     */
    public Map<String, Object> execute(Map<String, Object> variables, String runId, ModelOverrideSpec overrides) {
        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        Throwable error = null;
        String modelId = spec.modelId();
        String modelBaseUrl = spec.modelBaseUrl();
        String promptTemplate = null;
        String response = null;
        try {
            // 1. 还原 api-key
            SecretResolver secretResolver = optionalBean(SecretResolver.class, new EnvSecretResolver());
            GenericAgentSpec base = spec.withResolvedApiKey(
                    secretResolver.resolveApiKey(graphId, nodeId, spec));

            // 2. 请求级动态模型覆盖（node 级 > global 级）
            GenericAgentSpec resolved = base;
            ModelOverride ov = (overrides != null) ? overrides.effectiveFor(nodeId) : null;
            if (ov != null) {
                resolved = base.withOverride(ov);
            }
            modelId = resolved.modelId();
            modelBaseUrl = resolved.modelBaseUrl();

            // 3. 解析 prompt（内联优先，否则 promptKey）
            promptTemplate = resolvePrompt(resolved);

            // 4. 解析 mcp server 与工具（内联 mcp 文本优先，否则 mcpKey 经 McpServerRegistry）
            List<AgentTool> tools = resolveTools(resolved);

            // 5. 装配客户端并执行（支持逐 token 流式透传）
            ChatClientFactory factory = optionalBean(ChatClientFactory.class, new StubChatClientFactory());
            AgentChatClient client = factory.create(resolved, graphId, nodeId);
            GraphStreamBridge bridge = optionalBean(GraphStreamBridge.class, GraphStreamBridge.NOOP);
            boolean streaming = (bridge != GraphStreamBridge.NOOP) && runId != null;
            if (streaming) {
                // 订阅客户端逐 token 流：边累积完整响应，边经桥接器推给前端 SSE；
                // 末尾追加 FINISHED 结束片段（isEnd=true）。节点仍同步阻塞返回完整 Map，保证图状态正确。
                StringBuilder sb = new StringBuilder();
                try {
                    client.stream(promptTemplate, variables != null ? variables : Map.of(), resolved, tools)
                            .doOnNext(tok -> {
                                if (tok != null && !tok.isEmpty()) {
                                    sb.append(tok);
                                    bridge.emit(runId, new TokenChunk(nodeId, tok,
                                            OutputType.AGENT_MODEL_STREAMING, false));
                                }
                            })
                            .blockLast();
                } finally {
                    bridge.emit(runId, new TokenChunk(nodeId, "",
                            OutputType.AGENT_MODEL_FINISHED, true));
                }
                response = sb.toString();
            } else {
                response = client.call(promptTemplate, variables != null ? variables : Map.of(), resolved, tools);
            }

            // 6. 写回输出 key
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(resolved.effectiveOutputKey(), response);
            return result;
        } catch (Throwable t) {
            error = t;
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(t);
        } finally {
            // 观测：每次 LLM 调用边界推送实际模型 / prompt / 响应 / 错误 / 耗时
            if (runId != null) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                TraceRecorder recorder = optionalBean(TraceRecorder.class, TraceRecorder.NOOP);
                recorder.recordLLM(new TraceLLMEvent(graphId, nodeId, runId, modelId, modelBaseUrl,
                        promptTemplate, response, durationMs, startedAt, error));
            }
        }
    }

    /** 图内运行便捷包装：无请求级上下文（试跑 / 单节点执行）。 */
    public Map<String, Object> execute(Map<String, Object> variables) {
        return execute(variables, null, null);
    }

    /** 从 state 读取 runId（保留键，与 Langfuse trace 对齐）；无则返回 null（不观测）。 */
    private String readRunId(OverAllState state) {
        try {
            Object v = state.value(ModelOverrideSpec.ACE_RUN_ID_KEY).orElse(null);
            return v instanceof String s ? s : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 从 state 读取请求级模型覆盖（保留键）；无则返回 null。 */
    private ModelOverrideSpec readOverrides(OverAllState state) {
        try {
            Object v = state.value(ModelOverrideSpec.ACE_MODEL_OVERRIDES_KEY).orElse(null);
            return v instanceof ModelOverrideSpec spec ? spec : null;
        } catch (RuntimeException ignored) {
            return null;
        }
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

    /**
     * 解析工具集合。
     *
     * <p>mcp 与 prompt/skill 对齐同一套「内联优先、否则按 key」约定：
     * {@code spec.mcp()} 为内联 server 描述文本，{@code spec.mcpKey()} 交由
     * {@link McpServerRegistry} 解析成 {@link McpServerConfig}。</p>
     */
    private List<AgentTool> resolveTools(GenericAgentSpec resolved) {
        McpServerConfig serverConfig = null;
        String inlineMcp = resolved.mcp();
        if (inlineMcp != null && !inlineMcp.isBlank()) {
            serverConfig = McpServerConfig.ofRaw(null, inlineMcp);
        } else if (resolved.mcpKey() != null && !resolved.mcpKey().isBlank()) {
            McpServerRegistry registry = optionalBean(McpServerRegistry.class, new InMemoryMcpServerRegistry());
            serverConfig = registry.load(resolved.mcpKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "mcpKey 未找到: " + resolved.mcpKey() + " (节点 " + nodeId + ")"));
        }
        boolean noTools = (resolved.tools() == null || resolved.tools().isEmpty());
        if (serverConfig == null && noTools) {
            return List.of();
        }
        McpToolProvider provider = optionalBean(McpToolProvider.class, new InMemoryMcpToolProvider());
        return provider.resolve(resolved.tools(), resolved, serverConfig);
    }

    /**
     * 可选 Bean 解析：容器中无则回落默认实现；多实现时优先「非内置默认」的接入方实现。
     */
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
                if (!isBuiltinDefault(b)) {
                    return b;
                }
            }
            return beans.values().iterator().next();
        } catch (Exception e) {
            return fallback;
        }
    }

    /** core 内置的开箱默认实现，在存在接入方实现时应让位 */
    private static boolean isBuiltinDefault(Object bean) {
        return bean instanceof StubChatClientFactory
                || bean instanceof InMemoryPromptRepository
                || bean instanceof InMemorySkillRegistry
                || bean instanceof InMemoryMcpToolProvider
                || bean instanceof InMemoryMcpServerRegistry
                || bean instanceof EnvSecretResolver;
    }
}
