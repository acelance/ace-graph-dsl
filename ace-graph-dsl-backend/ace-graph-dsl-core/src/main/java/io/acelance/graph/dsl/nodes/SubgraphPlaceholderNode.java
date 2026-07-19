package io.acelance.graph.dsl.nodes;

import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 子图占位节点（结构性节点，非业务动作）。
 *
 * <p>在画布上表现为一个"可点击跳转到另一张图"的图节点；实际编译时由
 * {@code DynamicGraphBuilder} 特殊处理：把内嵌/引用的 {@code GraphDefinition}
 * 作为子 StateGraph 通过 {@code StateGraph.addNode(id, StateGraph)} 挂载。</p>
 *
 * <p>因此 {@link #toAction(NodeRuntimeContext)} 永远不会被调用（若被调用属异常）。</p>
 */
@Component
public class SubgraphPlaceholderNode implements RegisteredGraphNode {

    public static final String NODE_ID = "subgraph";

    @Override
    public GraphNodeDescriptor descriptor() {
        return new GraphNodeDescriptor(
                NODE_ID,
                "子图",
                GraphNodeDescriptor.CATEGORY_SUBGRAPH,
                "嵌套图容器：点击进入可编辑另一张图（graph-in-graph）。支持内嵌或引用目录中的图。",
                Set.of(),
                Set.of(),
                false,
                "1.0.0",
                Map.of()
        );
    }

    @Override
    public com.alibaba.cloud.ai.graph.action.NodeAction toAction(NodeRuntimeContext ctx) {
        throw new UnsupportedOperationException(
                "subgraph 节点由构建器特殊处理，不应以普通节点动作调用: " + NODE_ID);
    }
}
