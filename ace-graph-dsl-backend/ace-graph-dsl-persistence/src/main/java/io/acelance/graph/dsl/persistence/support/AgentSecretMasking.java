package io.acelance.graph.dsl.persistence.support;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.NodeRef;

import java.util.List;

/**
 * 通用 agent 节点 api-key 脱敏：落库前将节点中的明文 api-key 掩码（仅留后 4 位）。
 * 运行时由 core 的 {@code SecretResolver} 经环境变量/配置还原真实值。
 */
public final class AgentSecretMasking {

    private AgentSecretMasking() {
    }

    public static GraphDefinition mask(GraphDefinition def) {
        if (def == null || def.nodes() == null) {
            return def;
        }
        boolean changed = false;
        List<NodeRef> maskedNodes = new java.util.ArrayList<>();
        for (NodeRef ref : def.nodes()) {
            if (ref.hasAgentSpec() && ref.agentSpec() != null && !ref.agentSpec().apiKeyMasked()) {
                GenericAgentSpec m = ref.agentSpec().masked();
                maskedNodes.add(new NodeRef(
                        ref.nodeId(), ref.category(), ref.config(), ref.x(), ref.y(),
                        ref.subgraph(), ref.subgraphRef(), ref.agent(), m));
                changed = true;
            } else {
                maskedNodes.add(ref);
            }
        }
        if (!changed) {
            return def;
        }
        return new GraphDefinition(
                def.graphId(), def.displayName(), def.version(), def.description(),
                def.keyStrategies(), maskedNodes, def.edges(), def.compile(), def.bootstrap());
    }
}
