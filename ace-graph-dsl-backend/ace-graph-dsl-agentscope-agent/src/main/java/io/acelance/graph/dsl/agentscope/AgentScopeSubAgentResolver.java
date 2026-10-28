package io.acelance.graph.dsl.agentscope;

import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.agent.SubAgentBinding;
import io.acelance.graph.dsl.agent.SubAgentResolveRequest;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;

/**
 * 路径 B / G-MCP / G-STREAM-INNER：{@code impl=AGENTSCOPE}，{@code ref=agentscope:{id}}
 * → {@link AceAgentscopeReActBaseAgentAdapter}。
 *
 * <p>id 仍指向 ACE 注册 GenericAgent；工具在执行期按 Spec SPI 装配（Skill/MCP/localTools）。
 * 流式经 {@link GraphStreamBridge}，TokenChunk 挂父 SAA。子记忆强制 {@link MemoryMode#NONE}（Q5）。
 * 多模态经可选 {@link MediaRefResolver}（宿主 Lesso 覆盖）对齐经典图。</p>
 */
public class AgentScopeSubAgentResolver implements SubAgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSubAgentResolver.class);

    private final GraphNodeRegistry nodeRegistry;
    private final AgentScopeModelFactory modelFactory;
    private final AgentScopeToolAssembly toolAssembly;
    private final GraphStreamBridge streamBridge;
    private final AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer;
    /** 可空；空则 Adapter 内 DefaultMediaRefResolver */
    private final MediaRefResolver mediaResolver;

    public AgentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry, AgentScopeModelFactory modelFactory) {
        this(nodeRegistry, modelFactory, AgentScopeToolAssembly.noop(), GraphStreamBridge.NOOP, null, null);
    }

    public AgentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry,
                                      AgentScopeModelFactory modelFactory,
                                      AgentScopeToolAssembly toolAssembly) {
        this(nodeRegistry, modelFactory, toolAssembly, GraphStreamBridge.NOOP, null, null);
    }

    /**
     * @param streamBridge 可空；空则 {@link GraphStreamBridge#NOOP}（降级整段写回）
     */
    public AgentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry,
                                      AgentScopeModelFactory modelFactory,
                                      AgentScopeToolAssembly toolAssembly,
                                      GraphStreamBridge streamBridge) {
        this(nodeRegistry, modelFactory, toolAssembly, streamBridge, null, null);
    }

    /**
     * @param streamBridge               可空；空则 NOOP
     * @param generateOptionsCustomizer  可空；模型侧深思（与 bizParam attrs 正交）
     */
    public AgentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry,
                                      AgentScopeModelFactory modelFactory,
                                      AgentScopeToolAssembly toolAssembly,
                                      GraphStreamBridge streamBridge,
                                      AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer) {
        this(nodeRegistry, modelFactory, toolAssembly, streamBridge, generateOptionsCustomizer, null);
    }

    /**
     * @param streamBridge               可空；空则 NOOP
     * @param generateOptionsCustomizer  可空；模型侧深思
     * @param mediaResolver              可空；宿主注入 LessoMediaRefResolver 以支持内网附件
     */
    public AgentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry,
                                      AgentScopeModelFactory modelFactory,
                                      AgentScopeToolAssembly toolAssembly,
                                      GraphStreamBridge streamBridge,
                                      AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer,
                                      MediaRefResolver mediaResolver) {
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        this.toolAssembly = toolAssembly != null ? toolAssembly : AgentScopeToolAssembly.noop();
        this.streamBridge = streamBridge != null ? streamBridge : GraphStreamBridge.NOOP;
        this.generateOptionsCustomizer = generateOptionsCustomizer;
        this.mediaResolver = mediaResolver;
    }

    @Override
    public boolean supports(String impl) {
        return SaaSubAgentRefs.IMPL_AGENTSCOPE.equalsIgnoreCase(
                impl == null ? "" : impl.trim());
    }

    @Override
    public SubAgentBinding resolve(SubAgentResolveRequest request) {
        Objects.requireNonNull(request, "request");
        SaaSubAgentRef ref = Objects.requireNonNull(request.ref(), "ref");
        String impl = ref.effectiveImpl();
        if (!supports(impl)) {
            throw new IllegalArgumentException("AgentScopeSubAgentResolver 不支持 impl=" + impl);
        }
        String agentId = SaaSubAgentRefs.parseAgentscopeId(ref.ref());
        if (agentId == null) {
            throw new IllegalArgumentException("非法 agentscope ref: " + ref.ref());
        }
        if (!nodeRegistry.contains(agentId)) {
            throw new IllegalArgumentException("agentscope 引用未注册: " + ref.ref()
                    + ", graphId=" + request.graphId() + ", parentNodeId=" + request.parentNodeId());
        }
        RegisteredGraphNode registered = nodeRegistry.get(agentId);
        if (!(registered instanceof GraphBoundAgentNode agentNode)) {
            throw new IllegalArgumentException("agentscope 引用不是 GraphBoundAgentNode: " + ref.ref()
                    + ", actual=" + registered.getClass().getName());
        }
        GenericAgentSpec spec = agentNode.agentSpec();
        if (spec == null) {
            throw new IllegalArgumentException("agentscope 引用缺少 GenericAgentSpec: " + ref.ref());
        }
        // Q5：子 Agent 禁止 READ_WRITE
        if (spec.effectiveMemoryMode() == MemoryMode.READ_WRITE) {
            throw new IllegalArgumentException("AGENTSCOPE 子 Agent memoryMode=READ_WRITE 禁止（Q5）: "
                    + ref.ref() + ", graphId=" + request.graphId()
                    + ", parentNodeId=" + request.parentNodeId());
        }

        String name = StringUtils.hasText(ref.name()) ? ref.name().trim() : agentId;
        String outputKey = StringUtils.hasText(ref.outputKey())
                ? ref.outputKey().trim()
                : agentId + "_result";
        String instruction = StringUtils.hasText(ref.instruction())
                ? ref.instruction().trim()
                : "{input}";
        String description = StringUtils.hasText(registered.descriptor().displayName())
                ? registered.descriptor().displayName()
                : "ACE AgentScope subAgent ref=" + ref.ref();
        String sysPrompt = StringUtils.hasText(spec.prompt())
                ? spec.prompt().trim()
                : "You are a helpful assistant.";

        // modelConfigKey 依赖执行入口 agentCode：发布期不挂载，运行时由 Adapter 解析（与 GENERIC 一致）。
        // 仅内联模型可在编译期 create，避免「图定义无 agentCode」却走 Spec bind。
        boolean deferModel = needsRuntimeModelMount(spec);
        Model model = null;
        if (!deferModel) {
            model = modelFactory.create(request.graphId(), request.parentNodeId(), agentId, spec);
        }
        AgentScopeParentStreamContext parentStream =
                AgentScopeParentStreamContext.fromParent(request.parent());

        AceAgentscopeReActBaseAgentAdapter executable = new AceAgentscopeReActBaseAgentAdapter(
                name, description, outputKey, instruction,
                model, sysPrompt, spec, toolAssembly,
                request.graphId(), request.parentNodeId(), agentId,
                streamBridge, parentStream, generateOptionsCustomizer,
                deferModel ? modelFactory : null,
                mediaResolver);

        log.info("解析 AGENTSCOPE 子 Agent 成功, bridge={}, graphId={}, parentNodeId={}, name={}, ref={}, "
                        + "outputKey={}, memoryMode=NONE, gMcp=true, deferModelMount={}, streamBridgeNoop={}, "
                        + "generateOptionsCustomizer={}, mediaResolver={}, parentKind={}, parentAttrsKeys={}, "
                        + "parentApplyDeepThinking={}, skillKeys={}, mcpKeys={}, mediaInputKey={}",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT,
                request.graphId(), request.parentNodeId(), name, ref.ref(), outputKey,
                deferModel,
                streamBridge == GraphStreamBridge.NOOP,
                generateOptionsCustomizer != null,
                mediaResolver != null,
                parentStream.streamResponseKind(), parentStream.streamAttrs().keySet(),
                parentStream.applyDeepThinking(),
                spec.skillKeys() != null ? spec.skillKeys().size() : 0,
                spec.mcpKeys() != null ? spec.mcpKeys().size() : 0,
                spec.mediaInputKey());
        return new SubAgentBinding(name, SaaSubAgentRefs.IMPL_AGENTSCOPE.toUpperCase(Locale.ROOT),
                ref.ref(), outputKey, executable);
    }

    /** enableModel + modelConfigKey → 须等执行入口写入 agentCode 后再 ModelMount。 */
    private static boolean needsRuntimeModelMount(GenericAgentSpec spec) {
        ResourceBinding binding = ResourceBindings.fromSpec(spec);
        return binding.enableModel() && StringUtils.hasText(binding.modelConfigKey());
    }
}
