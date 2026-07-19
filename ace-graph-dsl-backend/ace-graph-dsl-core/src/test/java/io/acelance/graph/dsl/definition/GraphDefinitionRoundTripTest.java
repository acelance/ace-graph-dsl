package io.acelance.graph.dsl.definition;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.MergeStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向提取 + JSON 序列化往返回归测试，覆盖 G1/G2/G3/G5/G6 落实项：
 * <ul>
 *   <li>G1 子图（graph-in-graph）嵌套提取与序列化保真</li>
 *   <li>G2 并行边 parallel/aggregation 字段序列化保真</li>
 *   <li>G3 Agent 节点（自环 best-effort 标记）</li>
 *   <li>G5 KeyStrategy（REPLACE/APPEND/MERGE）提取保真</li>
 *   <li>G6 异常边（__ERROR__ 保留字）</li>
 * </ul>
 */
class GraphDefinitionRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** 构造一个不做事的节点动作，仅用于让 StateGraph 接受节点注册 */
    private static AsyncNodeAction noop() {
        return (OverAllState state) -> CompletableFuture.completedFuture(
                Collections.<String, Object>emptyMap());
    }

    @Test
    void fromStateGraphExtractsSubgraphErrorAgentAndKeyStrategies() throws GraphStateException {
        // 子图内部：一个普通节点（需要入口边，否则 addNode(subgraph) 校验失败）
        StateGraph inner = new StateGraph();
        inner.addNode("inner1", noop());
        inner.addEdge(StateGraph.START, "inner1");

        // 根图：带 KeyStrategy 工厂
        StateGraph sg = new StateGraph(() -> Map.of(
                "k1", new ReplaceStrategy(),
                "k2", new AppendStrategy(),
                "k3", new MergeStrategy()));
        sg.addNode("n1", noop());
        sg.addNode("n2", noop());
        sg.addNode("n3", noop());
        sg.addNode("agentX", noop());       // 自环 → 应标记为 AGENT
        sg.addNode("subA", inner);          // 子图节点

        sg.addEdge("n1", "n2");
        sg.addEdge("n1", "n3");             // n1 扇出到 n2/n3（并行语义）
        sg.addEdge("n1", StateGraph.ERROR); // 异常边
        sg.addEdge("agentX", "agentX");     // 自环

        GraphDefinition def = GraphDefinition.fromStateGraph(
                sg, "g1", "G1", "1.0.0", "round-trip fixture");

        // G5：KeyStrategy 完整提取
        assertEquals("REPLACE", def.keyStrategies().get("k1"));
        assertEquals("APPEND", def.keyStrategies().get("k2"));
        assertEquals("MERGE", def.keyStrategies().get("k3"));

        // G1：子图节点 + 内嵌拓扑递归提取
        NodeRef subA = def.nodes().stream()
                .filter(n -> "subA".equals(n.nodeId())).findFirst().orElseThrow();
        assertTrue(subA.hasSubgraph(), "subA 应被识别为子图节点");
        assertNotNull(subA.subgraph(), "子图应内嵌 GraphDefinition");
        assertTrue(subA.subgraph().nodes().stream()
                .anyMatch(n -> "inner1".equals(n.nodeId())), "子图内部节点 inner1 应被提取");

        // G6：异常边保留为 __ERROR__
        boolean hasErrorEdge = def.edges().stream()
                .anyMatch(e -> GraphDefinition.ERROR.equals(e.to()) && "normal".equals(e.type()));
        assertTrue(hasErrorEdge, "应存在指向 __ERROR__ 的异常边");

        // G3：自环节点被标记为 AGENT
        NodeRef agent = def.nodes().stream()
                .filter(n -> "agentX".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals("AGENT", agent.category(), "自环节点应被标记为 AGENT");
    }

    @Test
    void graphDefinitionJsonRoundTripPreservesParallelAndSubgraph() throws Exception {
        // 内联子图
        GraphDefinition inner = new GraphDefinition(
                "sub1", "sub", "1.0.0", "",
                Map.of(),
                List.of(new NodeRef("s1", Map.of(), null, null)),
                List.of(),
                new CompileConfigDto(List.of(), "memory"), null);

        NodeRef subRef = new NodeRef("subA", "SUBGRAPH", Map.of(), null, null, inner, null, null);
        NodeRef agentRef = new NodeRef("agentX", "AGENT", Map.of(), null, null, null, null, null);
        NodeRef normalRef = new NodeRef("n1", Map.of(), null, null);

        // 并行扇出：n1 → n2 / n3，标记 parallel + 聚合
        GraphEdge parallel1 = new GraphEdge("n1", "n2", "normal", null, null, null, null, true, GraphEdge.AGG_ALL_OF);
        GraphEdge parallel2 = new GraphEdge("n1", "n3", "normal", null, null, null, null, true, GraphEdge.AGG_ALL_OF);
        GraphEdge errorEdge = new GraphEdge("n1", GraphDefinition.ERROR, "normal", null, null, null, null, false, null);

        GraphDefinition def = new GraphDefinition(
                "g1", "G1", "1.0.0", "",
                Map.of("k1", "REPLACE"),
                List.of(normalRef, subRef, agentRef),
                List.of(parallel1, parallel2, errorEdge),
                new CompileConfigDto(List.of(), "memory"), null);

        String json = objectMapper.writeValueAsString(def);
        GraphDefinition restored = objectMapper.readValue(json, GraphDefinition.class);

        // G2：并行标记与聚合策略序列化保真
        long parallelCount = restored.edges().stream().filter(e -> Boolean.TRUE.equals(e.parallel())).count();
        assertEquals(2, parallelCount, "两条并行边应保留 parallel=true");
        assertTrue(restored.edges().stream()
                .allMatch(e -> e.aggregation() == null || GraphEdge.AGG_ALL_OF.equals(e.aggregation())),
                "聚合策略 ALL_OF 应保留");

        // G6：异常边保留
        assertTrue(restored.edges().stream()
                .anyMatch(e -> GraphDefinition.ERROR.equals(e.to())), "异常边 __ERROR__ 应保留");

        // G1：子图内嵌定义序列化保真
        Optional<NodeRef> restoredSub = restored.nodes().stream()
                .filter(n -> "subA".equals(n.nodeId())).findFirst();
        assertTrue(restoredSub.isPresent());
        assertTrue(restoredSub.get().hasSubgraph());
        assertNotNull(restoredSub.get().subgraph());
        assertEquals(1, restoredSub.get().subgraph().nodes().size());

        // G3 + G5 结构性节点/配置保真
        assertTrue(restored.nodes().stream().anyMatch(n -> "AGENT".equals(n.category())));
        assertEquals("REPLACE", restored.keyStrategies().get("k1"));

        // 任何衍生布尔字段不应泄漏进 JSON
        assertFalse(objectMapper.readTree(json).get("edges").get(0).has("conditional"));
        assertFalse(objectMapper.readTree(json).get("edges").get(0).has("scriptRouting"));
    }
}
