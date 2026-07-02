package io.acelance.graph.dsl.script;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于持久化脚本定义的 RegisteredGraphNode 实现。
 */
public class ScriptRegisteredGraphNode implements RegisteredGraphNode {

    private final DynamicNodeDefinition definition;
    private final ScriptEngineRegistry engineRegistry;
    private final Object compiledScript;

    public ScriptRegisteredGraphNode(DynamicNodeDefinition definition, ScriptEngineRegistry engineRegistry) {
        this.definition = definition;
        this.engineRegistry = engineRegistry;
        ScriptEngine engine = engineRegistry.require(definition.engine());
        engine.validate(definition.scriptBody());
        this.compiledScript = engine.compile(definition.scriptBody());
    }

    @Override
    public GraphNodeDescriptor descriptor() {
        return definition.toDescriptor();
    }

    @Override
    public NodeAction toAction(NodeRuntimeContext ctx) {
        ScriptEngine engine = engineRegistry.require(definition.engine());
        Map<String, Object> config = ctx.nodeConfig() != null ? ctx.nodeConfig() : Map.of();
        Set<String> inputKeys = definition.inputKeys();
        Set<String> outputKeys = definition.outputKeys();

        return state -> {
            Map<String, Object> stateSnapshot = extractState(state, inputKeys);
            ScriptExecutionContext scriptCtx = new ScriptExecutionContext(stateSnapshot, config);
            Object result = engine.execute(compiledScript, scriptCtx);
            return ScriptOutputNormalizer.normalize(result, outputKeys);
        };
    }

    public DynamicNodeDefinition definition() {
        return definition;
    }

    private static Map<String, Object> extractState(OverAllState state, Set<String> inputKeys) {
        Map<String, Object> map = new HashMap<>();
        if (inputKeys == null) {
            return map;
        }
        for (String key : inputKeys) {
            map.put(key, state.value(key).orElse(null));
        }
        return map;
    }
}
