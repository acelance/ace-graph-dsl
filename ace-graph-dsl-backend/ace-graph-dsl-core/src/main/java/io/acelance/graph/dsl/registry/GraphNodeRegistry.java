package io.acelance.graph.dsl.registry;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点注册中心：静态 Spring Bean + 动态脚本节点双层注册。
 */
@Component
public class GraphNodeRegistry {

    private final Map<String, RegisteredGraphNode> nodesById = new ConcurrentHashMap<>();

    public GraphNodeRegistry(List<RegisteredGraphNode> staticNodes) {
        for (RegisteredGraphNode node : staticNodes) {
            registerStatic(node);
        }
    }

    private void registerStatic(RegisteredGraphNode node) {
        String nodeId = node.descriptor().nodeId();
        RegisteredGraphNode prev = nodesById.putIfAbsent(nodeId, node);
        if (prev != null) {
            throw new IllegalStateException(
                    "节点 ID 冲突: " + nodeId
                            + " (已注册: " + prev.getClass().getName()
                            + ", 新增: " + node.getClass().getName() + ")");
        }
    }

    /** 注册动态脚本节点（运行时） */
    public void registerDynamic(RegisteredGraphNode node) {
        if (node.descriptor().origin() != NodeOrigin.SCRIPT) {
            throw new IllegalArgumentException("registerDynamic 仅支持 SCRIPT 来源节点: " + node.descriptor().nodeId());
        }
        String nodeId = node.descriptor().nodeId();
        RegisteredGraphNode existing = nodesById.get(nodeId);
        if (existing != null && existing.descriptor().origin() == NodeOrigin.BUILTIN) {
            throw new IllegalStateException("动态节点 ID 与内置节点冲突: " + nodeId);
        }
        nodesById.put(nodeId, node);
    }

    /** 注销动态脚本节点 */
    public void unregisterDynamic(String nodeId) {
        RegisteredGraphNode node = nodesById.get(nodeId);
        if (node != null && node.descriptor().origin() == NodeOrigin.SCRIPT) {
            nodesById.remove(nodeId);
        }
    }

    /** 列出所有已注册节点的元数据描述，按 category、nodeId 排序 */
    public List<GraphNodeDescriptor> listDescriptors() {
        return listDescriptors(NodeOrigin.ALL);
    }

    /** 按来源过滤节点描述 */
    public List<GraphNodeDescriptor> listDescriptors(NodeOrigin origin) {
        return nodesById.values().stream()
                .map(RegisteredGraphNode::descriptor)
                .filter(d -> origin == null || origin == NodeOrigin.ALL || d.origin() == origin)
                .sorted(Comparator.comparing(GraphNodeDescriptor::category)
                        .thenComparing(GraphNodeDescriptor::nodeId))
                .toList();
    }

    /** 根据 nodeId 获取已注册节点 */
    public RegisteredGraphNode get(String nodeId) {
        RegisteredGraphNode node = nodesById.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("未注册节点: " + nodeId);
        }
        return node;
    }

    /** 判断 nodeId 是否已注册 */
    public boolean contains(String nodeId) {
        return nodesById.containsKey(nodeId);
    }
}
