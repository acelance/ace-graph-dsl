package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * HITL（人工审核）结构节点规格，挂在 {@link NodeRef#hitlSpec()}。
 *
 * <p>{@code injectKeys}：人工在 resume {@code updates} 中可写入、且对下游连线校验宣称产出的扁平 state 键。
 * 运行时节点仅回声已有键，不调用 LLM；缺键不抛错（见方案 §5.1.2）。</p>
 *
 * @param injectKeys 人工注入键列表（至少一项才可通过发布校验）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HitlNodeSpec(List<String> injectKeys) {

    public HitlNodeSpec {
        injectKeys = normalizeKeys(injectKeys);
    }

    /** 去空白、去重、保序。 */
    public static List<String> normalizeKeys(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String k : raw) {
            if (k == null) {
                continue;
            }
            String t = k.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return List.copyOf(new ArrayList<>(set));
    }

    /** 是否已配置至少一个注入键。 */
    public boolean hasInjectKeys() {
        return injectKeys != null && !injectKeys.isEmpty();
    }
}
