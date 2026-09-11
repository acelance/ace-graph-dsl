package io.acelance.graph.dsl.skill;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取 state 保留键 {@link LlmRequestContext#ACE_FORCE_SKILLS_KEY}（§6.3.1）。
 */
public final class ForceSkills {

    private static final Logger log = LoggerFactory.getLogger(ForceSkills.class);

    private ForceSkills() {
    }

    /**
     * 从 OverAllState / Map 兼容读取有序 skill key 列表。
     */
    @SuppressWarnings("unchecked")
    public static List<String> read(OverAllState state, String nodeId) {
        if (state == null) {
            return List.of();
        }
        Object raw;
        try {
            raw = state.value(LlmRequestContext.ACE_FORCE_SKILLS_KEY).orElse(null);
        } catch (RuntimeException e) {
            log.warn("节点 {} 读取 forceSkills 失败: {}", nodeId, e.getMessage());
            return List.of();
        }
        return normalize(raw, nodeId);
    }

    /**
     * 规范化任意写入形态为 List&lt;String&gt;。
     */
    public static List<String> normalize(Object raw, String nodeId) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        }
        if (raw instanceof String s && !s.isBlank()) {
            // 容错：单字符串当作单元素
            log.warn("节点 {} 的 forceSkills 为单字符串，已视为单元素列表", nodeId);
            return List.of(s.trim());
        }
        log.warn("节点 {} 的 forceSkills 类型不是 List: {}", nodeId, raw.getClass().getName());
        return List.of();
    }
}
