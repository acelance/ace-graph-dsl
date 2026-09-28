package io.acelance.graph.dsl.resource;

/**
 * 资源类型（设计期 Catalog / 运行期诊断共用，§7.2）。
 */
public enum ResourceType {
    PROMPT,
    MODEL,
    LOCAL_TOOL,
    MCP,
    SKILL
}
