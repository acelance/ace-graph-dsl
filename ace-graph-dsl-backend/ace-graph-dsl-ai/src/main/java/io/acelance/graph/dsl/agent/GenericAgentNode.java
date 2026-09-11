package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.ai.model.CachingChatModelFactory;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.model.ModelMountResolver;
import io.acelance.graph.dsl.ai.model.StubChatModelFactory;
import io.acelance.graph.dsl.ai.template.LlmCallRequest;
import io.acelance.graph.dsl.ai.template.StreamingLlmTemplate;
import io.acelance.graph.dsl.ai.tool.EmptyLocalToolResolver;
import io.acelance.graph.dsl.ai.tool.EmptyMcpToolResolver;
import io.acelance.graph.dsl.ai.tool.LocalToolResolver;
import io.acelance.graph.dsl.ai.tool.McpToolFilter;
import io.acelance.graph.dsl.ai.tool.McpToolResolver;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.observability.TraceLLMEvent;
import io.acelance.graph.dsl.observability.TraceRecorder;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.acelance.graph.dsl.resource.ResourceLoadDiagnostics;
import io.acelance.graph.dsl.runtime.ModelOverride;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 agent 模板节点（GENERIC_AGENT）。
 *
 * <p>统一走 {@link StreamingLlmTemplate}：模型由 {@link ChatModelFactory} 提供，
 * 工具由 {@link LocalToolResolver} / {@link McpToolResolver} 解析后挂载。</p>
 */
public class GenericAgentNode implements GraphBoundAgentNode {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentNode.class);

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

    @Override
    public GraphBoundAgentNode withGraphId(String targetGraphId) {
        if (java.util.Objects.equals(this.graphId, targetGraphId)) {
            return this;
        }
        return new GenericAgentNode(nodeId, targetGraphId, spec, spring,
                displayName, description, version, permissionTags);
    }

    public String graphId() {
        return graphId;
    }

    public GenericAgentSpec spec() {
        return spec;
    }

    @Override
    public GenericAgentSpec agentSpec() {
        return spec;
    }

    @Override
    public GraphNodeDescriptor descriptor() {
        Set<String> inputKeys = spec.inputKeySet();
        Set<String> outputKeys = Set.of(spec.effectiveOutputKey());
        Map<String, GraphNodeDescriptor.PropertySchema> props = new LinkedHashMap<>();
        props.put("modelId", new GraphNodeDescriptor.PropertySchema("string", "模型", spec.modelId(), Map.of()));
        props.put("modelBaseUrl", new GraphNodeDescriptor.PropertySchema("string", "模型端点", spec.modelBaseUrl(), Map.of()));
        props.put("prompt", new GraphNodeDescriptor.PropertySchema("string", "prompt 追加", spec.prompt(), Map.of()));
        props.put("promptKeys", new GraphNodeDescriptor.PropertySchema("string", "prompt keys",
                String.join(",", spec.promptKeys()), Map.of()));
        props.put("outputKey", new GraphNodeDescriptor.PropertySchema("string", "输出 key", spec.effectiveOutputKey(), Map.of()));
        props.put("streamResponseKind", new GraphNodeDescriptor.PropertySchema(
                "string", "流式类型", spec.streamResponseKind(), Map.of()));
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
            String runId = readRunId(state);
            ModelOverrideSpec overrides = readOverrides(state);
            String agentCode = readAgentCode(state);
            return execute(variables, runId, overrides, agentCode, state);
        };
    }

    /**
     * 以给定输入变量执行一次（图内运行路径，可带请求级 runId / 模型覆盖）。
     */
    public Map<String, Object> execute(Map<String, Object> variables, String runId, ModelOverrideSpec overrides) {
        return execute(variables, runId, overrides, null, null);
    }

    /**
     * 完整执行入口（含 agentCode / state，供 Template 路径使用）。
     */
    public Map<String, Object> execute(Map<String, Object> variables,
                                       String runId,
                                       ModelOverrideSpec overrides,
                                       String agentCode,
                                       OverAllState state) {
        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        log.info("节点 {} 执行路径=StreamingLlmTemplate, runId={}", nodeId, runId);
        return executeViaTemplate(variables, runId, overrides, agentCode, state, startedAt, startNanos);
    }

    /** 图内运行便捷包装：无请求级上下文（试跑 / 单节点执行）。 */
    public Map<String, Object> execute(Map<String, Object> variables) {
        return execute(variables, null, null);
    }

    private Map<String, Object> executeViaTemplate(Map<String, Object> variables,
                                                   String runId,
                                                   ModelOverrideSpec overrides,
                                                   String agentCode,
                                                   OverAllState state,
                                                   long startedAt,
                                                   long startNanos) {
        Throwable error = null;
        String modelId = spec.modelId();
        String modelBaseUrl = spec.modelBaseUrl();
        String promptTemplate = null;
        String response = null;
        try {
            ResourceLoadDiagnostics diag = new ResourceLoadDiagnostics(nodeId);
            // P3.5：promptKeys 改由 Template / PromptContentResolver 加载；此处仅传内联追加
            String inlinePrompt = nonBlank(spec.prompt()) ? spec.prompt() : "";
            if (spec.enableSkill() || !spec.skillKeys().isEmpty()) {
                log.info("节点 {} enableSkill/skillKeys 已配置，L1/forceSkills 由 Template 处理", nodeId);
            }
            if ((spec.enablePrompt() || !spec.promptKeys().isEmpty()) && !spec.promptKeys().isEmpty()) {
                log.info("节点 {} promptKeys={} 将由 StreamingLlmTemplate/PromptContentResolver 加载",
                        nodeId, spec.promptKeys());
            }

            ModelOverride ov = (overrides != null) ? overrides.effectiveFor(nodeId) : null;
            if (ov != null && ov.modelId() != null) {
                modelId = ov.modelId();
            }
            if (ov != null && ov.modelBaseUrl() != null) {
                modelBaseUrl = ov.modelBaseUrl();
            }

            ResourceBinding binding = ResourceBindings.fromSpec(spec);
            LlmRequestContext ctx = new LlmRequestContext(
                    agentCode, graphId, nodeId, runId, state, binding);
            List<NamedToolCallback> namedTools = resolveNamedTools(ctx, binding, diag);
            emitResourceMiss(runId, diag);

            GraphStreamBridge bridge = optionalBean(GraphStreamBridge.class, GraphStreamBridge.NOOP);
            boolean streaming = (bridge != GraphStreamBridge.NOOP) && runId != null && !runId.isBlank();

            StreamingLlmTemplate template = resolveTemplate(bridge);
            log.info("节点 {} Template 挂载工具数={}", nodeId, namedTools.size());
            Map<String, Object> result = template.execute(LlmCallRequest.builder()
                    .context(ctx)
                    .systemTemplate(inlinePrompt)
                    .userMessage("")
                    .variables(variables != null ? variables : Map.of())
                    .outputKey(spec.effectiveOutputKey())
                    .streaming(streaming)
                    .streamResponseKind(spec.streamResponseKind())
                    .inlineModel(InlineModel.fromSpec(spec))
                    .modelOverride(ov)
                    .tools(namedTools)
                    .mediaInputKey(spec.mediaInputKey())
                    .build());
            response = result.get(spec.effectiveOutputKey()) instanceof String s ? s : String.valueOf(
                    result.get(spec.effectiveOutputKey()));
            promptTemplate = inlinePrompt; // trace 仅记内联；完整 system 在 Template 日志
            log.info("节点 {} Template 执行完成: outputKey={}, chars={}",
                    nodeId, spec.effectiveOutputKey(), response == null ? 0 : response.length());
            return result;
        } catch (Throwable t) {
            error = t;
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(t);
        } finally {
            if (runId != null) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                TraceRecorder recorder = optionalBean(TraceRecorder.class, TraceRecorder.NOOP);
                recorder.recordLLM(new TraceLLMEvent(graphId, nodeId, runId, modelId, modelBaseUrl,
                        promptTemplate, response, durationMs, startedAt, error));
            }
        }
    }

    private StreamingLlmTemplate resolveTemplate(GraphStreamBridge bridge) {
        StreamingLlmTemplate bean = optionalBean(StreamingLlmTemplate.class, null);
        if (bean != null) {
            return bean;
        }
        PromptRenderer renderer = optionalBean(PromptRenderer.class, new PromptRenderer());
        ModelMountResolver mount = optionalBean(ModelMountResolver.class, null);
        SecretResolver secrets = optionalBean(SecretResolver.class, new EnvSecretResolver());
        ModelEndpointResolver endpointResolver = optionalBean(ModelEndpointResolver.class,
                new ModelEndpointResolver(mount, secrets));
        ChatModelFactory cmf = optionalBean(ChatModelFactory.class, null);
        if (cmf == null) {
            cmf = new CachingChatModelFactory(new StubChatModelFactory());
        }
        io.acelance.graph.dsl.skill.SkillCatalogResolver catalog =
                optionalBean(io.acelance.graph.dsl.skill.SkillCatalogResolver.class, null);
        io.acelance.graph.dsl.skill.SkillContentLoader content =
                optionalBean(io.acelance.graph.dsl.skill.SkillContentLoader.class, null);
        io.acelance.graph.dsl.skill.SkillResourceLoader resources =
                optionalBean(io.acelance.graph.dsl.skill.SkillResourceLoader.class, null);
        if (catalog == null || content == null) {
            SkillRegistry registry = optionalBean(SkillRegistry.class, new InMemorySkillRegistry());
            if (catalog == null) {
                catalog = io.acelance.graph.dsl.skill.SkillRegistryAdapters.catalog(registry);
            }
            if (content == null) {
                content = io.acelance.graph.dsl.skill.SkillRegistryAdapters.content(registry);
            }
            if (resources == null) {
                resources = io.acelance.graph.dsl.skill.SkillRegistryAdapters.emptyResources();
            }
        }
        io.acelance.graph.dsl.ai.media.MediaRefResolver media =
                optionalBean(io.acelance.graph.dsl.ai.media.MediaRefResolver.class, null);
        log.info("节点 {} 本地装配 StreamingLlmTemplate（含 Skill/Media SPI 兜底）", nodeId);
        return new StreamingLlmTemplate(
                renderer, endpointResolver, cmf, bridge, catalog, content, resources, media);
    }

    private String readRunId(OverAllState state) {
        try {
            Object v = state.value(ModelOverrideSpec.ACE_RUN_ID_KEY).orElse(null);
            return v instanceof String s ? s : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ModelOverrideSpec readOverrides(OverAllState state) {
        try {
            Object v = state.value(ModelOverrideSpec.ACE_MODEL_OVERRIDES_KEY).orElse(null);
            return v instanceof ModelOverrideSpec spec ? spec : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String readAgentCode(OverAllState state) {
        try {
            Object v = state.value(LlmRequestContext.ACE_AGENT_CODE_KEY).orElse(null);
            return v instanceof String s ? s : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Template 主路径：解析本地工具 + MCP，再由框架施加 MCP 节点白名单。
     *
     * <p>§7.4：单个 key 失败仅诊断跳过，不使整节点失败。</p>
     */
    private List<NamedToolCallback> resolveNamedTools(LlmRequestContext ctx,
                                                      ResourceBinding binding,
                                                      ResourceLoadDiagnostics diag) {
        if (binding == null) {
            return List.of();
        }
        java.util.ArrayList<NamedToolCallback> all = new java.util.ArrayList<>();
        all.addAll(resolveLocalTools(ctx, binding, diag));
        all.addAll(resolveMcpTools(ctx, binding, diag));
        log.info("节点 {} 工具解析合计: local+mcp={}", nodeId, all.size());
        return List.copyOf(all);
    }

    /**
     * 解析本地工具（P3.1）：经 {@link LocalToolResolver}，默认空实现。
     */
    private List<NamedToolCallback> resolveLocalTools(LlmRequestContext ctx,
                                                      ResourceBinding binding,
                                                      ResourceLoadDiagnostics diag) {
        if (!binding.enableLocalTools() || binding.localToolKeys().isEmpty()) {
            return List.of();
        }
        LocalToolResolver resolver = optionalBean(LocalToolResolver.class, EmptyLocalToolResolver.INSTANCE);
        List<NamedToolCallback> named;
        try {
            named = resolver.resolve(ctx, binding.localToolKeys());
        } catch (Exception e) {
            log.error("节点 {} LocalToolResolver 解析失败，已跳过全部本地工具: {}",
                    nodeId, e.getMessage(), e);
            for (String key : binding.localToolKeys()) {
                if (key != null && !key.isBlank()) {
                    diag.miss("localTool", key.trim(), "LocalToolResolver 异常: " + e.getMessage());
                }
            }
            return List.of();
        }
        if (named == null) {
            named = List.of();
        }
        // 诊断：勾选了 key 但结果中无对应 uniqueName/logical 标记 → miss
        java.util.HashSet<String> seenKeys = new java.util.HashSet<>();
        for (NamedToolCallback t : named) {
            // 约定：本地工具 serverKey 可空；用 originalName 或 uniqueName 尾段难对齐 key
            // 优先用 serverKey 存 localToolKey（业务 Resolver 应把 key 写入 serverKey/namespace）
            if (t.serverKey() != null && !t.serverKey().isBlank()) {
                seenKeys.add(t.serverKey());
            }
            if (t.originalName() != null && !t.originalName().isBlank()) {
                seenKeys.add(t.originalName());
            }
        }
        for (String toolKey : binding.localToolKeys()) {
            if (toolKey == null || toolKey.isBlank()) {
                continue;
            }
            String k = toolKey.trim();
            if (!seenKeys.contains(k)) {
                diag.miss("localTool", k, "未解析到本地工具（Resolver 未返回或 key 不匹配）");
            }
        }
        log.info("节点 {} 本地工具解析完成: requested={}, resolved={}",
                nodeId, binding.localToolKeys().size(), named.size());
        return named;
    }

    /**
     * 解析 MCP 工具：经 {@link McpToolResolver}，再由 {@link McpToolFilter} 施加节点白名单。
     */
    private List<NamedToolCallback> resolveMcpTools(LlmRequestContext ctx,
                                                    ResourceBinding binding,
                                                    ResourceLoadDiagnostics diag) {
        if (!binding.enableMcp() || binding.mcpKeys().isEmpty()) {
            return List.of();
        }
        McpToolResolver resolver = optionalBean(McpToolResolver.class, EmptyMcpToolResolver.INSTANCE);
        if (resolver == EmptyMcpToolResolver.INSTANCE) {
            log.info("节点 {} 使用 EmptyMcpToolResolver（无业务 McpToolResolver Bean）", nodeId);
        }
        List<NamedToolCallback> named = resolver.resolve(ctx, binding.mcpKeys());
        if (named == null) {
            named = List.of();
        }
        // 诊断：勾选了 key 但结果中无该 server 的工具 → miss（空 server 也会标，便于调试）
        java.util.HashSet<String> seenServers = new java.util.HashSet<>();
        for (NamedToolCallback t : named) {
            if (t.serverKey() != null && !t.serverKey().isBlank()) {
                seenServers.add(t.serverKey());
            }
        }
        for (String mcpKey : binding.mcpKeys()) {
            if (mcpKey == null || mcpKey.isBlank()) {
                continue;
            }
            if (!seenServers.contains(mcpKey.trim())) {
                diag.miss("mcp", mcpKey.trim(), "未解析到工具（server 未注册、加载失败或未暴露工具）");
            }
        }
        List<NamedToolCallback> filtered = McpToolFilter.apply(named, binding, nodeId);
        log.info("节点 {} MCP 工具解析完成: raw={}, filtered={}", nodeId, named.size(), filtered.size());
        return filtered;
    }

    private void emitResourceMiss(String runId, ResourceLoadDiagnostics diag) {
        if (runId == null || runId.isBlank() || diag == null || !diag.hasIssues()) {
            return;
        }
        GraphStreamBridge bridge = optionalBean(GraphStreamBridge.class, GraphStreamBridge.NOOP);
        if (bridge == GraphStreamBridge.NOOP) {
            return;
        }
        for (ResourceLoadDiagnostics.Entry e : diag.entries()) {
            String msg = e.resourceType() + ":" + e.key() + " — " + e.message();
            bridge.emit(runId, new TokenChunk(nodeId, msg, null, "resource_miss", false));
            log.info("节点 {} 已推送 debug resource_miss: {}", nodeId, msg);
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

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
                if (!isBuiltinDefault(b)) {
                    return b;
                }
            }
            return beans.values().iterator().next();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isBuiltinDefault(Object bean) {
        return bean instanceof StubChatModelFactory
                || bean instanceof InMemoryPromptRepository
                || bean instanceof InMemorySkillRegistry
                || bean instanceof InMemoryMcpServerRegistry
                || bean instanceof EmptyLocalToolResolver
                || bean instanceof EmptyMcpToolResolver
                || bean instanceof EnvSecretResolver;
    }
}
