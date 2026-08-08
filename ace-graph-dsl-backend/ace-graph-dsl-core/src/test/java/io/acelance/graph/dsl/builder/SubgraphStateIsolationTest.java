package io.acelance.graph.dsl.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 集成验证：子图（CompiledGraph 挂载）与父图的 OverAllState 共享策略。
 *
 * <p>通过字节码分析 spring-ai-alibaba-graph 1.1.2.2 的 {@code SubCompiledGraphNodeAction}，
 * 子图执行时的状态流为：
 * <ol>
 *   <li><b>输入</b>：子图接收父图的 {@code OverAllState.data()}（完整 state map）作为初始状态
 *       （{@code CompiledGraph.updateState(config, parentStateData)}）</li>
 *   <li><b>执行</b>：父图的 OverAllState 对象直接传给 {@code graphResponseStream(parentState, config)}
 *       （共享引用，非拷贝）</li>
 *   <li><b>输出</b>：子图节点返回 {@code Map.of("subgraph_{nodeId}_compiled_graph", flux)}
 *       （单 key，值为响应流）</li>
 * </ol>
 *
 * <p>本测试用 spring-ai-alibaba-graph 原生 API 直接构建父子图，验证以下场景：
 * <ul>
 *   <li>状态共享：子图能否读取父图 state key</li>
 *   <li>新 key 传播：子图产出的 key 能否到达父图</li>
 *   <li>APPEND 冲突：父子图都用 APPEND 同一 key 时的实际行为</li>
 *   <li>REPLACE 覆盖：子图 REPLACE 一个 key 后父图看到什么</li>
 * </ul>
 */
class SubgraphStateIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(SubgraphStateIsolationTest.class);

    /**
     * 场景 1：子图能否读取父图设置的 state key（状态共享验证）。
     *
     * <p>父图节点设置 {@code parent_msg = "hello"}，子图节点读取它并回显到 {@code subgraph_echo}。
     * 若子图能读到，说明状态是共享的（子图继承父图 state）。
     */
    @Test
    void subgraphCanReadParentState() throws Exception {
        // 子图：读 parent_msg → 输出 subgraph_echo
        StateGraph child = new StateGraph(factory(Map.of(
                "parent_msg", "REPLACE",
                "subgraph_echo", "REPLACE")));
        child.addNode("child_echo", node_async((NodeAction) state -> {
            Object parentMsg = state.data().get("parent_msg");
            log.info("[child] received parent_msg={}", parentMsg);
            return Map.of("subgraph_echo", "echo: " + parentMsg);
        }));
        child.addEdge(StateGraph.START, "child_echo");
        child.addEdge("child_echo", StateGraph.END);
        CompiledGraph childCompiled = child.compile();

        // 父图：set_msg → sub（子图）
        StateGraph parent = new StateGraph(factory(Map.of(
                "parent_msg", "REPLACE",
                "subgraph_echo", "REPLACE")));
        parent.addNode("set_msg", node_async((NodeAction) state ->
                Map.of("parent_msg", "hello_from_parent")));
        parent.addNode("sub", childCompiled);
        parent.addEdge(StateGraph.START, "set_msg");
        parent.addEdge("set_msg", "sub");
        parent.addEdge("sub", StateGraph.END);
        CompiledGraph parentCompiled = parent.compile();

        OverAllState result = parentCompiled
                .invoke(Map.of(), RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));

        Object echo = result.data().get("subgraph_echo");
        log.info("[result] subgraph_echo={}", echo);
        assertNotNull(echo, "子图应能读到父图 state 并回显");
        assertTrue(echo.toString().contains("hello_from_parent"),
                () -> "子图回显应包含父图消息，实际: " + echo);
    }

    /**
     * 场景 2：子图产出的新 key 能否传播到父图最终 state。
     *
     * <p>子图设置 {@code subgraph_result = "computed"}，父图无此 key 的 KeyStrategy。
     * 验证最终 state 是否包含此 key。
     */
    @Test
    void subgraphNewKeyPropagatesToParent() throws Exception {
        StateGraph child = new StateGraph(factory(Map.of(
                "subgraph_result", "REPLACE")));
        child.addNode("child_compute", node_async((NodeAction) state ->
                Map.of("subgraph_result", "computed_by_child")));
        child.addEdge(StateGraph.START, "child_compute");
        child.addEdge("child_compute", StateGraph.END);
        CompiledGraph childCompiled = child.compile();

        // 父图不声明 subgraph_result 的 KeyStrategy
        StateGraph parent = new StateGraph(factory(Map.of(
                "parent_value", "REPLACE")));
        parent.addNode("parent_init", node_async((NodeAction) state ->
                Map.of("parent_value", "parent_data")));
        parent.addNode("sub", childCompiled);
        parent.addEdge(StateGraph.START, "parent_init");
        parent.addEdge("parent_init", "sub");
        parent.addEdge("sub", StateGraph.END);
        CompiledGraph parentCompiled = parent.compile();

        OverAllState result = parentCompiled
                .invoke(Map.of(), RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));

        Object subResult = result.data().get("subgraph_result");
        log.info("[result] subgraph_result={}", subResult);
        // 记录实际行为（可能传播也可能不传播，取决于框架对未声明 key 的处理）
        assertNotNull(subResult, "子图产出的新 key 应传播到父图 state");
    }

    /**
     * 场景 3：APPEND 策略冲突 — 父子图都用 APPEND 同一 key。
     *
     * <p><b>实测发现（P3 核心结论）</b>：APPEND 在父子图边界会产生<b>数据重复</b>。
     * <p>根因：子图继承父图的 state（共享引用），子图节点 APPEND 时基于已有列表追加，
     * 产生的新列表（含父图原始数据）再被父图 APPEND 策略二次追加，导致父图原始数据被重复。
     * <p>实测结果：{@code messages=[parent_msg, parent_msg, child_msg]}（parent_msg 出现 2 次）。
     * <p><b>建议</b>：父子图共享的 key 不宜使用 APPEND；子图内部新增的 key 可安全使用 APPEND。
     * 跨边界共享数据推荐用 REPLACE，或子图只产出新 key。
     */
    @Test
    void appendStrategyConflictBehavior() throws Exception {
        StateGraph child = new StateGraph(factory(Map.of(
                "messages", "APPEND")));
        child.addNode("child_append", node_async((NodeAction) state ->
                Map.of("messages", "child_msg")));
        child.addEdge(StateGraph.START, "child_append");
        child.addEdge("child_append", StateGraph.END);
        CompiledGraph childCompiled = child.compile();

        StateGraph parent = new StateGraph(factory(Map.of(
                "messages", "APPEND")));
        parent.addNode("parent_append", node_async((NodeAction) state ->
                Map.of("messages", "parent_msg")));
        parent.addNode("sub", childCompiled);
        parent.addEdge(StateGraph.START, "parent_append");
        parent.addEdge("parent_append", "sub");
        parent.addEdge("sub", StateGraph.END);
        CompiledGraph parentCompiled = parent.compile();

        OverAllState result = parentCompiled
                .invoke(Map.of(), RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));

        Object messages = result.data().get("messages");
        log.info("[result] APPEND conflict messages={}", messages);
        assertNotNull(messages, "messages 不应为 null");

        // 文档化实测行为：parent_msg 被重复（APPEND 跨边界数据重复）
        @SuppressWarnings("unchecked")
        java.util.List<Object> msgList = (java.util.List<Object>) messages;
        assertEquals(3, msgList.size(),
                () -> "APPEND 跨边界产生数据重复：预期 3 项（parent_msg x2 + child_msg），实际: " + messages);
        long parentMsgCount = msgList.stream().filter(m -> "parent_msg".equals(m)).count();
        assertEquals(2, parentMsgCount,
                () -> "parent_msg 应被重复 2 次（APPEND 冲突），实际: " + messages);
        assertTrue(msgList.contains("child_msg"),
                () -> "messages 应包含 child_msg，实际: " + messages);
    }

    /**
     * 场景 4：REPLACE 覆盖 — 子图 REPLACE 一个父图已有的 key。
     *
     * <p>父图设置 {@code shared_value = "parent_value"}，子图 REPLACE 为 "child_overwritten"。
     * 验证最终 state 中 shared_value 是子图的值（覆盖）还是父图的值（隔离）。
     */
    @Test
    void replaceStrategyOverwriteBehavior() throws Exception {
        StateGraph child = new StateGraph(factory(Map.of(
                "shared_value", "REPLACE")));
        child.addNode("child_overwrite", node_async((NodeAction) state ->
                Map.of("shared_value", "child_overwritten")));
        child.addEdge(StateGraph.START, "child_overwrite");
        child.addEdge("child_overwrite", StateGraph.END);
        CompiledGraph childCompiled = child.compile();

        StateGraph parent = new StateGraph(factory(Map.of(
                "shared_value", "REPLACE")));
        parent.addNode("parent_set", node_async((NodeAction) state ->
                Map.of("shared_value", "parent_value")));
        parent.addNode("sub", childCompiled);
        parent.addEdge(StateGraph.START, "parent_set");
        parent.addEdge("parent_set", "sub");
        parent.addEdge("sub", StateGraph.END);
        CompiledGraph parentCompiled = parent.compile();

        OverAllState result = parentCompiled
                .invoke(Map.of(), RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));

        Object sharedValue = result.data().get("shared_value");
        log.info("[result] REPLACE overwrite shared_value={}", sharedValue);
        // 记录实际行为：预期子图覆盖父图的值
        assertEquals("child_overwritten", sharedValue,
                () -> "子图 REPLACE 应覆盖父图的值，实际: " + sharedValue);
    }

    // ── 工具方法 ──

    private static KeyStrategyFactory factory(Map<String, String> strategies) {
        return () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            strategies.forEach((k, v) -> map.put(k, toStrategy(v)));
            return map;
        };
    }

    private static KeyStrategy toStrategy(String name) {
        return switch (name) {
            case "APPEND" -> new AppendStrategy();
            case "REPLACE" -> new ReplaceStrategy();
            default -> new ReplaceStrategy();
        };
    }
}
