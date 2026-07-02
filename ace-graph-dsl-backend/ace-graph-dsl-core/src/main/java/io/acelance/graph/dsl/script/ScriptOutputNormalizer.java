package io.acelance.graph.dsl.script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 脚本返回值规范化：转为 Graph 节点 output map。
 */
public final class ScriptOutputNormalizer {

    private ScriptOutputNormalizer() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalize(Object result, Set<String> outputKeys) {
        if (result == null) {
            throw new IllegalArgumentException("脚本返回值为 null");
        }
        if (result instanceof Map<?, ?> rawMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    out.put(key, entry.getValue());
                }
            }
            if (outputKeys != null && !outputKeys.isEmpty()) {
                Map<String, Object> filtered = new LinkedHashMap<>();
                for (String key : outputKeys) {
                    if (out.containsKey(key)) {
                        filtered.put(key, out.get(key));
                    }
                }
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        if (outputKeys != null && outputKeys.size() == 1) {
            String key = outputKeys.iterator().next();
            return Map.of(key, result);
        }
        throw new IllegalArgumentException("脚本返回值必须是 Map，或与单个 outputKey 匹配");
    }
}
