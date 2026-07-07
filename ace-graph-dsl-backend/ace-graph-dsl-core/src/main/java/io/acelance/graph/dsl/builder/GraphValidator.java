package io.acelance.graph.dsl.builder;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphEdge;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.script.ScriptEdgeActionFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图定义校验器：保存/发布前必跑，返回结构化错误列表。
 */
@Component
public class GraphValidator {

    private final GraphNodeRegistry nodeRegistry;
    private final EdgeDispatcherRegistry dispatcherRegistry;
    private final ScriptEdgeActionFactory scriptEdgeActionFactory;
    private final EdgeParamReachabilityValidator reachabilityValidator;

    public GraphValidator(GraphNodeRegistry nodeRegistry,
                          EdgeDispatcherRegistry dispatcherRegistry,
                          ScriptEdgeActionFactory scriptEdgeActionFactory,
                          EdgeParamReachabilityValidator reachabilityValidator) {
        this.nodeRegistry = nodeRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.scriptEdgeActionFactory = scriptEdgeActionFactory;
        this.reachabilityValidator = reachabilityValidator;
    }

    /**
     * 校验图定义的合法性。
     *
     * @param def 图定义
     * @return 校验结果
     */
    public ValidationResult validate(GraphDefinition def) {
        List<String> errors = new ArrayList<>();

        if (def == null) {
            return ValidationResult.fail(List.of("图定义为空"));
        }
        if (def.graphId() == null || def.graphId().isBlank()) {
            errors.add("graphId 不能为空");
        }
        if (def.nodes() == null || def.nodes().isEmpty()) {
            errors.add("节点列表不能为空");
            return ValidationResult.fail(errors);
        }

        // 1. 节点存在性
        for (NodeRef ref : def.nodes()) {
            if (!nodeRegistry.contains(ref.nodeId())) {
                errors.add("节点未注册: " + ref.nodeId());
            }
        }

        // 2. 边引用合法性
        Set<String> nodeIds = def.nodes().stream().map(NodeRef::nodeId).collect(Collectors.toSet());
        nodeIds.add(GraphDefinition.START);
        nodeIds.add(GraphDefinition.END);

        List<GraphEdge> edges = def.edges() != null ? def.edges() : List.of();
        for (GraphEdge edge : edges) {
            if (edge.from() == null || !nodeIds.contains(edge.from())) {
                errors.add("边 from 不存在: " + edge.from());
            }
            if (edge.isConditional()) {
                validateConditionalEdge(edge, nodeIds, errors);
            } else {
                if (edge.to() == null || !nodeIds.contains(edge.to())) {
                    errors.add("边 to 不存在: " + edge.to());
                }
            }
        }

        // 3. KeyStrategy 覆盖性：所有节点 outputKeys 必须在 keyStrategies 声明
        Set<String> declaredKeys = def.keyStrategies() != null ? def.keyStrategies().keySet() : Set.of();
        Set<String> neededKeys = def.nodes().stream()
                .filter(ref -> nodeRegistry.contains(ref.nodeId()))
                .map(ref -> nodeRegistry.get(ref.nodeId()).descriptor().outputKeys())
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        for (String key : neededKeys) {
            if (!declaredKeys.contains(key)) {
                errors.add("KeyStrategy 缺失: " + key);
            }
        }

        // 4. interruptBefore 节点存在
        if (def.compile() != null && def.compile().interruptBefore() != null) {
            for (String n : def.compile().interruptBefore()) {
                if (!nodeIds.contains(n)) {
                    errors.add("interruptBefore 节点不存在: " + n);
                }
            }
        }

        // 5. 环检测（HITL resume 外不允许成环）
        if (hasCycle(def)) {
            errors.add("检测到环（HITL resume 外不允许回环）");
        }

        // 6. 连线参数可达性（发布前置校验；保存草稿不经过此校验器）
        errors.addAll(reachabilityValidator.validate(def));

        return errors.isEmpty() ? ValidationResult.pass() : ValidationResult.fail(errors);
    }

    /**
     * 仅校验连线参数可达性，供发布等场景单独调用。
     */
    public ValidationResult validateParamReachability(GraphDefinition def) {
        List<String> errors = reachabilityValidator.validate(def);
        return errors.isEmpty() ? ValidationResult.pass() : ValidationResult.fail(errors);
    }

    private void validateConditionalEdge(GraphEdge edge, Set<String> nodeIds, List<String> errors) {
        boolean hasDispatcher = edge.dispatcher() != null && !edge.dispatcher().isBlank();
        boolean scriptRouting = edge.isScriptRouting();
        if (!hasDispatcher && !scriptRouting) {
            errors.add("条件边需指定 dispatcher 或 condition 脚本: from=" + edge.from());
            return;
        }
        if (edge.mapping() == null || edge.mapping().isEmpty()) {
            errors.add("条件边 mapping 为空: from=" + edge.from());
            return;
        }
        for (Map.Entry<String, String> entry : edge.mapping().entrySet()) {
            if (!nodeIds.contains(entry.getValue())) {
                errors.add("条件边 mapping 目标不存在: " + entry.getValue());
            }
        }
        if (scriptRouting) {
            validateScriptRouting(edge, errors);
        } else {
            validateDispatcherRouting(edge, errors);
        }
    }

    private void validateScriptRouting(GraphEdge edge, List<String> errors) {
        String engine = edge.resolvedConditionEngine();
        if (!scriptEdgeActionFactory.supports(engine)) {
            errors.add("条件边脚本引擎不可用: " + engine + "（from=" + edge.from() + "）");
            return;
        }
        try {
            scriptEdgeActionFactory.validate(engine, edge.condition());
        } catch (RuntimeException e) {
            errors.add("条件边脚本表达式非法: from=" + edge.from() + " - " + e.getMessage());
        }
        // 脚本路由的返回值在运行时才确定，无法静态推断 possibleTargets，跳过覆盖性校验
    }

    private void validateDispatcherRouting(GraphEdge edge, List<String> errors) {
        if (!dispatcherRegistry.contains(edge.dispatcher())) {
            errors.add("dispatcher 未注册: " + edge.dispatcher());
            return;
        }
        Set<String> possible = dispatcherRegistry.get(edge.dispatcher()).possibleTargets();
        Set<String> mappedTargets = new HashSet<>(edge.mapping().values());
        for (String target : possible) {
            if (!mappedTargets.contains(target)) {
                errors.add("条件边 mapping 未覆盖 possibleTargets: 缺少 " + target);
            }
        }
    }

    /**
     * 简单环检测：DFS 判断是否存在非 START/END 的回环。
     */
    private boolean hasCycle(GraphDefinition def) {
        List<GraphEdge> edges = def.edges() != null ? def.edges() : List.of();
        Map<String, List<String>> adj = edges.stream()
                .filter(e -> !e.isConditional())
                .collect(Collectors.groupingBy(
                        GraphEdge::from,
                        Collectors.mapping(GraphEdge::to, Collectors.toList())));
        // 条件边也加入邻接表
        for (GraphEdge e : edges) {
            if (e.isConditional() && e.mapping() != null) {
                adj.computeIfAbsent(e.from(), k -> new ArrayList<>())
                        .addAll(e.mapping().values());
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> recursion = new HashSet<>();
        for (String node : adj.keySet()) {
            if (GraphDefinition.START.equals(node) || GraphDefinition.END.equals(node)) {
                continue;
            }
            if (dfsCycle(node, adj, visited, recursion)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(String node, Map<String, List<String>> adj, Set<String> visited, Set<String> recursion) {
        if (recursion.contains(node)) {
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }
        visited.add(node);
        recursion.add(node);
        List<String> neighbors = adj.getOrDefault(node, List.of());
        for (String next : neighbors) {
            if (GraphDefinition.END.equals(next)) {
                continue;
            }
            if (dfsCycle(next, adj, visited, recursion)) {
                return true;
            }
        }
        recursion.remove(node);
        return false;
    }
}
