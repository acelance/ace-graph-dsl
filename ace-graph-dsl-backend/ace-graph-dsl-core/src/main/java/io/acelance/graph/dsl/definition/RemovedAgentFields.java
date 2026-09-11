package io.acelance.graph.dsl.definition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D3 已删除字段守卫：保存/校验入口若仍携带旧键则 fail fast。
 */
public final class RemovedAgentFields {

    /** 旧字段 → 替代说明 */
    public static final Map<String, String> REMOVED = Map.of(
            "promptKey", "改用 enablePrompt + promptKeys",
            "skillKey", "改用 enableSkill + skillKeys",
            "mcpKey", "改用 enableMcp + mcpKeys",
            "tools", "改用 mcpToolWhitelist（serverKey → 工具名）",
            "skill", "改用 enableSkill + skillKeys（内联 skill 已删除）",
            "mcp", "改用 enableMcp + mcpKeys（内联 mcp 已删除）"
    );

    private RemovedAgentFields() {
    }

    /**
     * @param raw 反序列化前的 Map（可为 null）
     * @throws IllegalArgumentException 含已删除字段时
     */
    public static void assertAbsent(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        List<String> hits = REMOVED.keySet().stream()
                .filter(raw::containsKey)
                .filter(k -> raw.get(k) != null)
                .toList();
        if (hits.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("agent 元数据含已删除字段：");
        for (String k : hits) {
            sb.append(k).append("（").append(REMOVED.get(k)).append("）；");
        }
        throw new IllegalArgumentException(sb.toString());
    }

    /** 从嵌套请求体中检查顶层与 {@code spec} 子对象 */
    @SuppressWarnings("unchecked")
    public static void assertAbsentInRequest(Map<String, ?> body) {
        if (body == null) {
            return;
        }
        assertAbsent(body);
        Object spec = body.get("spec");
        if (spec instanceof Map<?, ?> m) {
            assertAbsent((Map<String, ?>) m);
        }
    }

    public static Set<String> names() {
        return REMOVED.keySet();
    }

    /** 便于日志 */
    public static Map<String, String> asDisplayMap() {
        return new LinkedHashMap<>(REMOVED);
    }
}
