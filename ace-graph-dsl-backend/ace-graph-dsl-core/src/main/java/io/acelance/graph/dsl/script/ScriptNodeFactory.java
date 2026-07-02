package io.acelance.graph.dsl.script;

import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import org.springframework.stereotype.Component;

/**
 * 从 DynamicNodeDefinition 创建 ScriptRegisteredGraphNode。
 */
@Component
public class ScriptNodeFactory {

    private final ScriptEngineRegistry engineRegistry;

    public ScriptNodeFactory(ScriptEngineRegistry engineRegistry) {
        this.engineRegistry = engineRegistry;
    }

    public RegisteredGraphNode create(DynamicNodeDefinition definition) {
        return new ScriptRegisteredGraphNode(definition, engineRegistry);
    }
}
