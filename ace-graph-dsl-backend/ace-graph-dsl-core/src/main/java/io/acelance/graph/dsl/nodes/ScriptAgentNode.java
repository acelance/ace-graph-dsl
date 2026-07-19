package io.acelance.graph.dsl.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.Command;
import com.alibaba.cloud.ai.graph.action.CommandAction;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredAgentNode;
import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.ScriptExecutionContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于脚本的 Agent 循环节点（subagent 内核，代码岛式实现）。
 *
 * <p>节点配置（来自设计器 NodeRef.config）：
 * <ul>
 *   <li>{@code script}：循环体脚本，需返回 {@code {goto, update}} 的 Map。
 *       {@code goto} 为目标节点 ID 或 "__SELF__"（回到自身续轮）；{@code update} 为写回 state 的键值。</li>
 *   <li>{@code engine}：脚本引擎，默认 aviator。</li>
 *   <li>{@code maxIterations}：最大迭代次数，超过即强制退出（防死循环护栏）。</li>
 *   <li>{@code loopTo}：循环回边目标，"__SELF__" 或某个节点 ID。</li>
 * </ul>
 *
 * <p>失真说明：DSL 无法可视化 agent 循环体内部（"智能"是脚本逻辑而非图结构），
 * 反向提取时仅能 best-effort 标记节点类别为 AGENT，循环逻辑需用户在 DSL 中重建。</p>
 */
@Component
public class ScriptAgentNode implements RegisteredAgentNode {

    public static final String NODE_ID = "agent:script";

    /** 迭代计数在 state 中的键（护栏用） */
    private static final String ITER_KEY = "__agent_iter__";

    private final ScriptEngineRegistry engineRegistry;

    public ScriptAgentNode(ScriptEngineRegistry engineRegistry) {
        this.engineRegistry = engineRegistry;
    }

    @Override
    public GraphNodeDescriptor descriptor() {
        return new GraphNodeDescriptor(
                NODE_ID,
                "脚本 Agent",
                GraphNodeDescriptor.CATEGORY_AGENT,
                "Agent 循环节点：脚本返回 {goto, update} 控制续轮/退出；内置最大迭代护栏。",
                Set.of(),
                Set.of(),
                false,
                "1.0.0",
                Map.of(
                        "script", new GraphNodeDescriptor.PropertySchema("string",
                                "循环脚本（返回 {goto, update}）", "", Map.of("multiline", true)),
                        "engine", new GraphNodeDescriptor.PropertySchema("string", "脚本引擎", "aviator", Map.of()),
                        "maxIterations", new GraphNodeDescriptor.PropertySchema("number", "最大迭代次数", 25, Map.of()),
                        "loopTo", new GraphNodeDescriptor.PropertySchema("string",
                                "循环目标（__SELF__/节点ID）", "__SELF__", Map.of())
                )
        );
    }

    @Override
    public com.alibaba.cloud.ai.graph.action.NodeAction toAction(NodeRuntimeContext ctx) {
        throw new UnsupportedOperationException(
                "agent:script 仅作为 AGENT 节点使用，不应以普通节点动作调用: " + NODE_ID);
    }

    @Override
    public CommandAction toCommandAction(NodeRuntimeContext ctx) {
        Map<String, Object> config = ctx.nodeConfig() != null ? ctx.nodeConfig() : Map.of();
        String script = (String) config.get("script");
        String engine = (String) (config.get("engine") != null ? config.get("engine") : "aviator");
        int maxIterations = config.get("maxIterations") instanceof Number n ? n.intValue() : 25;
        String loopTo = (String) (config.get("loopTo") != null ? config.get("loopTo") : "__SELF__");

        if (script == null || script.isBlank()) {
            throw new IllegalStateException("agent:script 缺少 script 配置: " + NODE_ID);
        }

        ScriptEngine se = engineRegistry.require(engine);
        se.validate(script);
        Object compiled = se.compile(script);

        return (state, rc) -> {
            // 1. 迭代护栏：超过最大次数强制退出
            Object raw = state.value(ITER_KEY).orElse(0);
            int iter = (raw instanceof Number n) ? n.intValue() : 0;
            iter += 1;
            if (iter > maxIterations) {
                return new Command(StateGraph.END, Map.of(ITER_KEY, iter));
            }

            // 2. 执行脚本（传入当前 state 与节点 config）
            ScriptExecutionContext scriptCtx = new ScriptExecutionContext(state.data(), config);
            Object result = se.execute(compiled, scriptCtx);
            Map<String, Object> resultMap = toMap(result);

            // 3. 解析 goto / update
            String gotoNode = (String) resultMap.getOrDefault("goto", loopTo);
            if (gotoNode == null || gotoNode.isBlank()) {
                gotoNode = loopTo;
            }
            Map<String, Object> update = new LinkedHashMap<>();
            Object rawUpdate = resultMap.get("update");
            if (rawUpdate instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    update.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            update.put(ITER_KEY, iter);
            return new Command(gotoNode, update);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object result) {
        if (result instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }
}
