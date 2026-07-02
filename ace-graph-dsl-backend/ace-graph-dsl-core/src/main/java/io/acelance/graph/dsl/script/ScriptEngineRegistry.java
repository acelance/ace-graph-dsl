package io.acelance.graph.dsl.script;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本引擎注册中心。
 */
@Component
public class ScriptEngineRegistry {

    private final Map<String, ScriptEngine> engines = new ConcurrentHashMap<>();

    public ScriptEngineRegistry(List<ScriptEngine> all) {
        for (ScriptEngine engine : all) {
            engines.put(engine.engineId(), engine);
        }
    }

    public ScriptEngine require(String engineId) {
        ScriptEngine engine = engines.get(engineId);
        if (engine == null) {
            throw new IllegalArgumentException("未知脚本引擎: " + engineId);
        }
        return engine;
    }

    public boolean supports(String engineId) {
        return engines.containsKey(engineId);
    }
}
