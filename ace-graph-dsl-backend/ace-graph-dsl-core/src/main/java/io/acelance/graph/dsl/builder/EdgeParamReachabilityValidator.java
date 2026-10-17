package io.acelance.graph.dsl.builder;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphEdge;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 校验连线参数可达性：目标节点所需入参须可由上游节点产出（沿图路径累积 state key）。
 *
 * <p>注册表节点用 descriptor 的 input/outputKeys；{@code SAA_WORKFLOW} 结构节点不在注册表，
 * 从其 {@link NodeRef#saaSpec()} 读取父 outputKey、子 outputKey 与 inputKeys。</p>
 */
@Component
public class EdgeParamReachabilityValidator {

    private final GraphNodeRegistry nodeRegistry;

    public EdgeParamReachabilityValidator(GraphNodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /**
     * 校验图中每条连线的参数可达性，返回结构化错误信息。
     */
    public List<String> validate(GraphDefinition def) {
        List<String> errors = new ArrayList<>();
        if (def == null || def.nodes() == null || def.nodes().isEmpty()) {
            return errors;
        }

        Map<String, NodeRef> nodeRefs = indexNodeRefs(def);
        Map<String, List<String>> predecessors = buildPredecessors(def);
        Map<String, Set<String>> entryKeys = computeEntryKeys(def, predecessors, nodeRefs);

        List<GraphEdge> edges = def.edges() != null ? def.edges() : List.of();
        Set<String> reported = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            if (edge.isConditional()) {
                if (edge.mapping() == null) {
                    continue;
                }
                for (String to : edge.mapping().values()) {
                    reportEdge(edge.from(), to, entryKeys, reported, errors, nodeRefs);
                }
            } else {
                reportEdge(edge.from(), edge.to(), entryKeys, reported, errors, nodeRefs);
            }
        }
        return errors;
    }

    private void reportEdge(String from, String to, Map<String, Set<String>> entryKeys,
                            Set<String> reported, List<String> errors,
                            Map<String, NodeRef> nodeRefs) {
        if (to == null || GraphDefinition.END.equals(to)) {
            return;
        }
        if (shouldSkipEdgeValidation(from, to)) {
            return;
        }
        Set<String> required = inputKeysOf(to, nodeRefs);
        if (required.isEmpty()) {
            return;
        }
        Set<String> available = entryKeys.getOrDefault(to, Set.of());
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(available);
        if (missing.isEmpty()) {
            return;
        }
        String key = from + "->" + to + ":" + String.join(",", missing.stream().sorted().toList());
        if (!reported.add(key)) {
            return;
        }
        errors.add("连线参数不可达: " + from + " -> " + to
                + "，目标节点 " + to + " 缺少入参 " + missing);
    }

    /**
     * 豁免规则：
     * 1. START 出边：目标入参来自图调用初始 state，非上游节点产出；
     * 2. 错误边（ERROR 出边）：目标为错误处理器，入参来自框架注入的错误状态，非前驱产出；
     * 3. 目标为 HITL：入参来自 interrupt/resume 注入，非直接前驱产出。
     */
    private boolean shouldSkipEdgeValidation(String from, String to) {
        if (GraphDefinition.START.equals(from)) {
            return true;
        }
        if (GraphDefinition.ERROR.equals(from)) {
            return true;
        }
        return isHitlNode(to);
    }

    private boolean isHitlNode(String nodeId) {
        if (!nodeRegistry.contains(nodeId)) {
            return false;
        }
        return GraphNodeDescriptor.CATEGORY_HITL.equals(nodeRegistry.get(nodeId).descriptor().category());
    }

    private Map<String, Set<String>> computeEntryKeys(GraphDefinition def,
                                                      Map<String, List<String>> predecessors,
                                                      Map<String, NodeRef> nodeRefs) {
        Map<String, Set<String>> entry = new HashMap<>();
        Map<String, Set<String>> exit = new HashMap<>();
        entry.put(GraphDefinition.START, Set.of());
        exit.put(GraphDefinition.START, Set.of());

        Set<String> allNodes = collectNodes(def);
        Map<String, Integer> indegree = new HashMap<>();
        for (String node : allNodes) {
            indegree.put(node, predecessors.getOrDefault(node, List.of()).size());
        }

        Queue<String> queue = new ArrayDeque<>();
        queue.add(GraphDefinition.START);
        Set<String> processed = new HashSet<>();
        processed.add(GraphDefinition.START);

        while (!queue.isEmpty()) {
            String node = queue.poll();
            Set<String> nodeEntry = entry.getOrDefault(node, Set.of());
            Set<String> nodeExit = new HashSet<>(nodeEntry);
            nodeExit.addAll(outputKeysOf(node, nodeRefs));
            exit.put(node, nodeExit);

            for (String succ : successorsOf(node, def)) {
                Set<String> succEntry = entry.computeIfAbsent(succ, k -> new HashSet<>());
                succEntry.addAll(nodeExit);
                indegree.merge(succ, -1, Integer::sum);
                if (indegree.getOrDefault(succ, 0) == 0 && processed.add(succ)) {
                    queue.add(succ);
                }
            }
        }
        return entry;
    }

    private static Map<String, NodeRef> indexNodeRefs(GraphDefinition def) {
        Map<String, NodeRef> map = new HashMap<>();
        if (def.nodes() == null) {
            return map;
        }
        for (NodeRef ref : def.nodes()) {
            if (ref != null && StringUtils.hasText(ref.nodeId())) {
                map.put(ref.nodeId(), ref);
            }
        }
        return map;
    }

    private Set<String> collectNodes(GraphDefinition def) {
        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(GraphDefinition.START);
        nodes.add(GraphDefinition.END);
        def.nodes().forEach(ref -> nodes.add(ref.nodeId()));
        List<GraphEdge> edges = def.edges() != null ? def.edges() : List.of();
        for (GraphEdge edge : edges) {
            if (edge.from() != null) {
                nodes.add(edge.from());
            }
            if (edge.isConditional() && edge.mapping() != null) {
                nodes.addAll(edge.mapping().values());
            } else if (edge.to() != null) {
                nodes.add(edge.to());
            }
        }
        return nodes;
    }

    private Map<String, List<String>> buildPredecessors(GraphDefinition def) {
        Map<String, List<String>> preds = new HashMap<>();
        List<GraphEdge> edges = def.edges() != null ? def.edges() : List.of();
        for (GraphEdge edge : edges) {
            if (edge.isConditional()) {
                if (edge.mapping() == null) {
                    continue;
                }
                for (String to : edge.mapping().values()) {
                    preds.computeIfAbsent(to, k -> new ArrayList<>()).add(edge.from());
                }
            } else if (edge.to() != null) {
                preds.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
            }
        }
        return preds;
    }

    private List<String> successorsOf(String from, GraphDefinition def) {
        List<String> succ = new ArrayList<>();
        List<GraphEdge> edges = def.edges() != null ? def.edges() : List.of();
        for (GraphEdge edge : edges) {
            if (!from.equals(edge.from())) {
                continue;
            }
            if (edge.isConditional()) {
                if (edge.mapping() != null) {
                    succ.addAll(edge.mapping().values());
                }
            } else if (edge.to() != null) {
                succ.add(edge.to());
            }
        }
        return succ;
    }

    private Set<String> inputKeysOf(String nodeId, Map<String, NodeRef> nodeRefs) {
        if (GraphDefinition.START.equals(nodeId) || GraphDefinition.END.equals(nodeId)) {
            return Set.of();
        }
        if (nodeRegistry.contains(nodeId)) {
            return nodeRegistry.get(nodeId).descriptor().inputKeys();
        }
        NodeRef ref = nodeRefs.get(nodeId);
        if (ref != null && ref.hasSaaSpec() && ref.saaSpec() != null) {
            return new LinkedHashSet<>(ref.saaSpec().inputKeyList());
        }
        if (ref != null && ref.hasAgentSpec() && ref.agentSpec() != null) {
            return new LinkedHashSet<>(ref.agentSpec().inputKeyList());
        }
        return Set.of();
    }

    private Set<String> outputKeysOf(String nodeId, Map<String, NodeRef> nodeRefs) {
        if (GraphDefinition.START.equals(nodeId) || GraphDefinition.END.equals(nodeId)) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        if (nodeRegistry.contains(nodeId)) {
            keys.addAll(nodeRegistry.get(nodeId).descriptor().outputKeys());
        }
        NodeRef ref = nodeRefs.get(nodeId);
        if (ref != null && ref.hasSaaSpec() && ref.saaSpec() != null) {
            keys.addAll(saaOutputKeys(ref.saaSpec()));
        } else if (ref != null && ref.hasAgentSpec() && ref.agentSpec() != null
                && StringUtils.hasText(ref.agentSpec().effectiveOutputKey())) {
            keys.add(ref.agentSpec().effectiveOutputKey());
        }
        return keys;
    }

    /** SAA 写回图 state 的键：父 outputKey + 各子 outputKey。 */
    static Set<String> saaOutputKeys(SaaWorkflowSpec spec) {
        Set<String> keys = new LinkedHashSet<>();
        if (spec == null) {
            return keys;
        }
        if (StringUtils.hasText(spec.outputKey())) {
            keys.add(spec.outputKey().trim());
        }
        keys.addAll(spec.subAgentOutputKeys());
        return keys;
    }
}
