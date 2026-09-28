package io.acelance.graph.dsl.resource;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.NodeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 保存期资源 key 校验门面（P3.6）：有 {@link ResourceKeyValidator} Bean 才执行。
 */
public final class ResourceKeyValidationGate {

    private static final Logger log = LoggerFactory.getLogger(ResourceKeyValidationGate.class);

    private ResourceKeyValidationGate() {
    }

    /**
     * 校验单个节点 Binding；无 validator 或结果 ok 则通过，否则抛 {@link IllegalArgumentException}。
     */
    public static void assertValid(ResourceKeyValidator validator,
                                   String agentCode,
                                   String graphId,
                                   String nodeId,
                                   ResourceBinding binding) {
        if (validator == null || binding == null) {
            return;
        }
        ResourceKeyValidator.ValidationResult result = validator.validate(agentCode, graphId, binding);
        if (result == null || result.ok()) {
            log.info("ResourceKeyValidator 通过: graphId={}, nodeId={}", graphId, nodeId);
            return;
        }
        StringBuilder msg = new StringBuilder("资源 key 校验失败");
        if (nodeId != null && !nodeId.isBlank()) {
            msg.append("（节点 ").append(nodeId).append('）');
        }
        msg.append('：');
        if (result.items() != null) {
            result.items().forEach(i -> msg.append('[')
                    .append(i.type()).append(':').append(i.key())
                    .append(' ').append(i.status())
                    .append(i.message() == null ? "" : (" " + i.message()))
                    .append("] "));
        }
        log.error("ResourceKeyValidator 拒绝保存: graphId={}, nodeId={}, detail={}",
                graphId, nodeId, msg);
        throw new IllegalArgumentException(msg.toString().trim());
    }

    /** 校验图内所有 GENERIC_AGENT 的 agentSpec */
    public static void assertValidGraph(ResourceKeyValidator validator,
                                        String agentCode,
                                        GraphDefinition def) {
        if (validator == null || def == null || def.nodes() == null) {
            return;
        }
        for (NodeRef n : def.nodes()) {
            if (n == null || !n.hasAgentSpec() || n.agentSpec() == null) {
                continue;
            }
            GenericAgentSpec spec = n.agentSpec();
            ResourceBinding binding = ResourceBindings.fromSpec(spec);
            assertValid(validator, agentCode, def.graphId(), n.nodeId(), binding);
        }
    }
}
