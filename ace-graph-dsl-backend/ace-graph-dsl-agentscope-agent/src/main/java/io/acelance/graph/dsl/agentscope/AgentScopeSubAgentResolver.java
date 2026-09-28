package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.agent.SubAgentBinding;
import io.acelance.graph.dsl.agent.SubAgentResolveRequest;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;

/**
 * M3 / Q4：{@code impl=AGENTSCOPE}，{@code ref=agentscope:{id}} → 官方 {@link AgentScopeAgent}。
 *
 * <p>id 仍指向 ACE 注册 GenericAgent；执行引擎切换为 AgentScope（非第二套资源目录）。
 * 子记忆强制 {@link MemoryMode#NONE}（Q5）。</p>
 */
public class AgentScopeSubAgentResolver implements SubAgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSubAgentResolver.class);

    private final GraphNodeRegistry nodeRegistry;
    private final AgentScopeModelFactory modelFactory;

    public AgentScopeSubAgentResolver(GraphNodeRegistry nodeRegistry, AgentScopeModelFactory modelFactory) {
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
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

        Model model = modelFactory.create(request.graphId(), request.parentNodeId(), agentId, spec);

        // Q5：仅进程内 InMemoryMemory，不写 remote READ_WRITE
        ReActAgent.Builder reactBuilder = ReActAgent.builder()
                .name(name)
                .description(description)
                .sysPrompt(sysPrompt)
                .model(model)
                .memory(new InMemoryMemory());

        AgentScopeAgent executable = AgentScopeAgent.fromBuilder(reactBuilder)
                .name(name)
                .description(description)
                .instruction(instruction)
                .includeContents(false)
                .outputKey(outputKey)
                .build();

        log.info("解析 AGENTSCOPE 子 Agent 成功, bridge={}, graphId={}, parentNodeId={}, name={}, ref={}, "
                        + "outputKey={}, memoryMode=NONE",
                DefaultAgentScopeModelFactory.BRIDGE_ARTIFACT,
                request.graphId(), request.parentNodeId(), name, ref.ref(), outputKey);
        return new SubAgentBinding(name, SaaSubAgentRefs.IMPL_AGENTSCOPE.toUpperCase(Locale.ROOT),
                ref.ref(), outputKey, executable);
    }
}
