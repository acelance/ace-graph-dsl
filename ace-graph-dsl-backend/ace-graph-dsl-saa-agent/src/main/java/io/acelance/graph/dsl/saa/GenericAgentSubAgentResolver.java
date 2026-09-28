package io.acelance.graph.dsl.saa;

import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.agent.SubAgentBinding;
import io.acelance.graph.dsl.agent.SubAgentResolveRequest;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;

/**
 * M1 默认子 Agent 解析器：{@code impl=GENERIC_AGENT}，{@code ref=generic:{id}}。
 */
public class GenericAgentSubAgentResolver implements SubAgentResolver {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentSubAgentResolver.class);

    private final GraphNodeRegistry nodeRegistry;

    public GenericAgentSubAgentResolver(GraphNodeRegistry nodeRegistry) {
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
    }

    @Override
    public boolean supports(String impl) {
        if (impl == null || impl.isBlank()) {
            return true;
        }
        return SaaSubAgentRefs.IMPL_GENERIC_AGENT.equalsIgnoreCase(impl.trim());
    }

    @Override
    public SubAgentBinding resolve(SubAgentResolveRequest request) {
        Objects.requireNonNull(request, "request");
        SaaSubAgentRef ref = Objects.requireNonNull(request.ref(), "ref");
        String impl = ref.effectiveImpl();
        if (!supports(impl)) {
            throw new IllegalArgumentException("GenericAgentSubAgentResolver 不支持 impl=" + impl);
        }
        String registeredId = SaaSubAgentRefs.parseGenericNodeId(ref.ref());
        if (registeredId == null) {
            throw new IllegalArgumentException("非法 generic ref: " + ref.ref());
        }
        if (!nodeRegistry.contains(registeredId)) {
            throw new IllegalArgumentException("generic 引用未注册: " + ref.ref()
                    + ", graphId=" + request.graphId() + ", parentNodeId=" + request.parentNodeId());
        }
        RegisteredGraphNode registered = nodeRegistry.get(registeredId);
        if (!(registered instanceof GraphBoundAgentNode agentNode)) {
            throw new IllegalArgumentException("generic 引用不是 GraphBoundAgentNode: " + ref.ref()
                    + ", actual=" + registered.getClass().getName());
        }
        GraphBoundAgentNode bound = agentNode.withGraphId(request.graphId());
        String name = StringUtils.hasText(ref.name()) ? ref.name().trim() : registeredId;
        String outputKey = StringUtils.hasText(ref.outputKey())
                ? ref.outputKey().trim()
                : registeredId + "_result";
        String description = "ACE GenericAgent subAgent ref=" + ref.ref();

        GenericAgentBaseAgentAdapter executable = new GenericAgentBaseAgentAdapter(
                name,
                description,
                outputKey,
                ref.instruction(),
                bound,
                request.graphId(),
                request.parentNodeId());

        log.info("解析 GENERIC_AGENT 子 Agent 成功, graphId={}, parentNodeId={}, name={}, ref={}, outputKey={}",
                request.graphId(), request.parentNodeId(), name, ref.ref(), outputKey);
        return new SubAgentBinding(name, impl.toUpperCase(Locale.ROOT), ref.ref(), outputKey, executable);
    }
}
