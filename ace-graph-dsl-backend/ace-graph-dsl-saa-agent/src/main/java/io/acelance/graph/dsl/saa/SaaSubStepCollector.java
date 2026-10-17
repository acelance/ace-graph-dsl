package io.acelance.graph.dsl.saa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 线程内收集 SAA 子 Agent 步骤（M4；不改 SSE）。
 *
 * <p>由 {@link FlowAgentNodeAction} begin/end，{@link GenericAgentBaseAgentAdapter} 追加明细。</p>
 */
public final class SaaSubStepCollector {

    private static final Logger log = LoggerFactory.getLogger(SaaSubStepCollector.class);

    private static final ThreadLocal<List<Map<String, Object>>> HOLDER = new ThreadLocal<>();

    private SaaSubStepCollector() {
    }

    /** 父节点执行开始时调用 */
    public static void begin() {
        HOLDER.set(new CopyOnWriteArrayList<>());
    }

    /**
     * 记录一条子步骤。
     *
     * @param name      子 Agent 名
     * @param outputKey 输出键
     * @param status    OK / FAILED / SKIPPED
     * @param costMs    耗时
     * @param preview   结果预览（可空）
     * @param error     错误摘要（可空）
     */
    public static void record(String name,
                              String outputKey,
                              String status,
                              long costMs,
                              String preview,
                              String error) {
        List<Map<String, Object>> list = HOLDER.get();
        if (list == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name == null ? "" : name);
        row.put("outputKey", outputKey == null ? "" : outputKey);
        row.put("status", status == null ? "OK" : status);
        row.put("costMs", costMs);
        if (preview != null && !preview.isBlank()) {
            row.put("preview", preview);
        }
        if (error != null && !error.isBlank()) {
            row.put("error", error);
        }
        list.add(row);
        log.info("SAA 子步骤已记录, name={}, outputKey={}, status={}, costMs={}",
                name, outputKey, status, costMs);
    }

    /**
     * 结束收集并返回快照；清理 ThreadLocal。
     *
     * @return 不可变副本
     */
    public static List<Map<String, Object>> endAndSnapshot() {
        List<Map<String, Object>> list = HOLDER.get();
        HOLDER.remove();
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(list.size());
        for (Map<String, Object> row : list) {
            copy.add(Map.copyOf(row));
        }
        return List.copyOf(copy);
    }

    /** 异常路径清理 */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 截断预览文本。
     *
     * @param value 原始值
     * @param max   最大字符
     * @return 预览；null 输入返回 null
     */
    public static String preview(Object value, int max) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max)) + "…";
    }
}
