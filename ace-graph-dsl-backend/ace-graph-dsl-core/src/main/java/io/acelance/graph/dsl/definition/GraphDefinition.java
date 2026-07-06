package io.acelance.graph.dsl.definition;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.internal.edge.Edge;
import com.alibaba.cloud.ai.graph.internal.edge.EdgeCondition;
import com.alibaba.cloud.ai.graph.internal.edge.EdgeValue;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图定义 DSL 根模型，对应前端保存的 JSON。
 *
 * @param graphId        图定义业务标识，如 "cs-reply-m2"
 * @param displayName    显示名
 * @param version        版本号（semver）
 * @param description    描述
 * @param keyStrategies  state key → 策略名（REPLACE / APPEND）
 * @param nodes          节点引用列表
 * @param edges          边列表
 * @param compile        编译配置
 * @param bootstrap      是否为内置图（前端只读）
 */
public record GraphDefinition(
        String graphId,
        String displayName,
        String version,
        String description,
        Map<String, String> keyStrategies,
        List<NodeRef> nodes,
        List<GraphEdge> edges,
        CompileConfigDto compile,
        Boolean bootstrap
) {

    private static final Logger log = LoggerFactory.getLogger(GraphDefinition.class);

    /** 保留字：起点 */
    public static final String START = "__START__";
    /** 保留字：终点 */
    public static final String END = "__END__";

    /**
     * 从 spring-ai-alibaba-graph 的 {@link StateGraph} 提取拓扑，
     * 构建一个标记为内置（bootstrap=true）的 GraphDefinition。
     *
     * <p>使用反射访问 StateGraph 内部 nodes/edges 字段，无需修改原有
     * {@code @Bean StateGraph} 代码，零迁移成本。</p>
     *
     * @param stateGraph  已构建的 StateGraph
     * @param graphId     图 ID
     * @param displayName 显示名
     * @param version     版本号
     * @param description 描述
     * @return bootstrap=true 的 GraphDefinition
     */
    public static GraphDefinition fromStateGraph(StateGraph stateGraph,
                                                  String graphId, String displayName,
                                                  String version, String description) {
        return fromStateGraph(stateGraph, graphId, displayName, version, description, null, null);
    }

    /**
     * 完整版，附带编译配置。
     */
    public static GraphDefinition fromStateGraph(StateGraph stateGraph,
                                                  String graphId, String displayName,
                                                  String version, String description,
                                                  List<String> interruptBefore,
                                                  String saver) {
        List<NodeRef> nodeRefs = new ArrayList<>();
        List<GraphEdge> graphEdges = new ArrayList<>();

        // 1. 提取节点
        Set<Node> internalNodes = getNodes(stateGraph);
        if (internalNodes != null) {
            for (Node n : internalNodes) {
                nodeRefs.add(new NodeRef(n.id(), Map.of()));
            }
        }

        // 2. 提取边
        List<Edge> internalEdges = getEdges(stateGraph);
        if (internalEdges != null) {
            for (Edge e : internalEdges) {
                String from = e.sourceId();
                List<EdgeValue> targets = e.targets();
                if (targets == null || targets.isEmpty()) continue;

                if (targets.stream().allMatch(tv -> tv.value() == null)) {
                    // 普通边（单条或多条并行）
                    for (EdgeValue tv : targets) {
                        graphEdges.add(new GraphEdge(from, tv.id(),
                                GraphEdge.TYPE_NORMAL, null, null, null, null));
                    }
                } else {
                    // 条件边: 从 EdgeCondition.mappings() 提取路由映射
                    Map<String, String> mapping = new LinkedHashMap<>();
                    for (EdgeValue tv : targets) {
                        EdgeCondition cond = tv.value();
                        if (cond != null && cond.mappings() != null) {
                            mapping.putAll(cond.mappings());
                        }
                    }
                    graphEdges.add(new GraphEdge(from, "",
                            GraphEdge.TYPE_CONDITIONAL, null, mapping, null, null));
                }
            }
        }

        return new GraphDefinition(graphId, displayName, version, description,
                Map.of(), // keyStrategies 暂不传递（反射无法获取 KeyStrategyFactory 内容）
                List.copyOf(nodeRefs), List.copyOf(graphEdges),
                new CompileConfigDto(interruptBefore, saver), true);
    }

    @SuppressWarnings("unchecked")
    private static Set<Node> getNodes(StateGraph sg) {
        try {
            Field nodesField = StateGraph.class.getDeclaredField("nodes");
            nodesField.setAccessible(true);
            Object nodesObj = nodesField.get(sg);
            Field elementsField = nodesObj.getClass().getDeclaredField("elements");
            elementsField.setAccessible(true);
            return (Set<Node>) elementsField.get(nodesObj);
        } catch (Exception e) {
            log.debug("反射提取 StateGraph 节点失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Edge> getEdges(StateGraph sg) {
        try {
            Field edgesField = StateGraph.class.getDeclaredField("edges");
            edgesField.setAccessible(true);
            Object edgesObj = edgesField.get(sg);
            Field elementsField = edgesObj.getClass().getDeclaredField("elements");
            elementsField.setAccessible(true);
            return (List<Edge>) elementsField.get(edgesObj);
        } catch (Exception e) {
            log.debug("反射提取 StateGraph 边失败: {}", e.getMessage());
            return null;
        }
    }
}
