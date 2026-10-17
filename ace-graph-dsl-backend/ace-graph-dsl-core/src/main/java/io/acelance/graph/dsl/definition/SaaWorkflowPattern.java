package io.acelance.graph.dsl.definition;

/**
 * SAA 高阶工作流模式（节点管内协作，非图级 FanOut/条件边）。
 */
public enum SaaWorkflowPattern {

    /** 顺序：子 Agent 按数组顺序执行，后者可读前者 outputKey */
    SEQUENTIAL,
    /** 并行：子 Agent 并发（M2） */
    PARALLEL,
    /** 路由：Framework 自带路由类选子 Agent（M2） */
    ROUTING,
    /** 循环：带退出条件与迭代上限（M2） */
    LOOP;

    /**
     * 解析 pattern 字符串；空白或未知返回 null（由校验器报错）。
     *
     * @param raw JSON / 配置中的 pattern
     * @return 枚举；无法识别时 null
     */
    public static SaaWorkflowPattern fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SaaWorkflowPattern.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
