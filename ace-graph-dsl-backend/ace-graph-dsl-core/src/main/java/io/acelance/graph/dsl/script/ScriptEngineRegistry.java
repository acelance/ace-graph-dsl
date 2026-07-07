package io.acelance.graph.dsl.script;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 返回所有已注册引擎的 ID 集合，供前端引擎下拉选择 */
    public Set<String> listEngineIds() {
        return Set.copyOf(engines.keySet());
    }

    /** 返回所有已注册引擎的元数据，按 id 字典序排列，供前端引擎下拉与编辑器行为决策 */
    public List<ScriptEngineDescriptor> listDescriptors() {
        return engines.values().stream()
                .map(ScriptEngine::descriptor)
                .sorted(Comparator.comparing(ScriptEngineDescriptor::id))
                .toList();
    }
}
