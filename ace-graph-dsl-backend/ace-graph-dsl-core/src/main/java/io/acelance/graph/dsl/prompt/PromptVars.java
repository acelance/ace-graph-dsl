package io.acelance.graph.dsl.prompt;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 从 OverAllState 按 inputKeys 拍变量快照（system/user 共用同一份）。
 */
public final class PromptVars {

    private static final Logger log = LoggerFactory.getLogger(PromptVars.class);

    private PromptVars() {
    }

    /**
     * @param state     当前图状态，可空
     * @param inputKeys 允许读取的 key；空则返回空快照
     * @return 有序快照（缺失 key 不放入，由渲染器按严格模式处理）
     */
    public static Map<String, Object> snapshot(OverAllState state, Set<String> inputKeys) {
        Map<String, Object> snap = new LinkedHashMap<>();
        if (state == null || inputKeys == null || inputKeys.isEmpty()) {
            return snap;
        }
        for (String key : inputKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object v = state.value(key).orElse(null);
            if (v != null) {
                snap.put(key, v);
            } else {
                log.debug("PromptVars 快照: key={} 在 state 中无值", key);
            }
        }
        return snap;
    }

    /** 从已有 Map（试跑 mock）按 keys 抽取 */
    public static Map<String, Object> snapshot(Map<String, Object> source, Set<String> inputKeys) {
        Map<String, Object> snap = new LinkedHashMap<>();
        if (source == null || inputKeys == null) {
            return snap;
        }
        for (String key : inputKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (source.containsKey(key)) {
                snap.put(key, source.get(key));
            }
        }
        return snap;
    }
}
