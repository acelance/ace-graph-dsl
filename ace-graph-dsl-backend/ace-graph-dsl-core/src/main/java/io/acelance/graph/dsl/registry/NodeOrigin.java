package io.acelance.graph.dsl.registry;

/**
 * 节点来源类型。
 */
public enum NodeOrigin {
    /** Java Bean 静态注册 */
    BUILTIN,
    /** 持久化脚本节点 */
    SCRIPT,
    /** 通用 agent 节点（GENERIC_AGENT，元数据驱动模板节点） */
    GENERIC_AGENT,
    /** 查询时不按来源过滤 */
    ALL
}
