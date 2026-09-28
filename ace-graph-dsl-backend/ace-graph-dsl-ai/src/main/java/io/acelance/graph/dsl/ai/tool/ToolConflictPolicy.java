package io.acelance.graph.dsl.ai.tool;

/**
 * 本地工具与 MCP 工具在同 {@code originalName} 冲突时的策略（§5.2 / P3.4）。
 *
 * <p>{@link #BUILTIN} 永远优先，不在本枚举覆盖范围内。</p>
 * <p>默认 {@link #LOCAL_FIRST}。</p>
 */
public enum ToolConflictPolicy {

    /** 同 originalName：保留 LOCAL，丢弃 MCP（warn） */
    LOCAL_FIRST,

    /** 同 originalName：保留 MCP，丢弃 LOCAL（warn） */
    MCP_FIRST,

    /** 同 originalName 同时存在 LOCAL 与 MCP 时 fail fast */
    FAIL
}
