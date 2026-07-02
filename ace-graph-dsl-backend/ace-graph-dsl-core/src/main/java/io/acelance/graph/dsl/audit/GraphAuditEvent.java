package io.acelance.graph.dsl.audit;

import java.time.Instant;

/**
 * 图编排平台的一次可审计操作记录。
 *
 * @param action       操作类型，见 {@link GraphAuditActions}
 * @param resourceType 资源类型（如 {@code graph} / {@code script-node}）
 * @param resourceId   资源标识（graphId / nodeId）
 * @param version      关联版本（可空）
 * @param operator     操作人（可空）
 * @param success      操作是否成功
 * @param detail       变更摘要 / 失败原因（可空）
 * @param timestamp    发生时间
 */
public record GraphAuditEvent(
        String action,
        String resourceType,
        String resourceId,
        String version,
        String operator,
        boolean success,
        String detail,
        Instant timestamp) {

    public static final String RESOURCE_GRAPH = "graph";
    public static final String RESOURCE_SCRIPT_NODE = "script-node";

    /** 构造一条 graph 资源的审计事件（时间戳取当前时刻）。 */
    public static GraphAuditEvent graph(String action, String graphId, String version,
                                        String operator, boolean success, String detail) {
        return new GraphAuditEvent(action, RESOURCE_GRAPH, graphId, version, operator, success, detail, Instant.now());
    }

    /** 构造一条 script-node 资源的审计事件（时间戳取当前时刻）。 */
    public static GraphAuditEvent scriptNode(String action, String nodeId, String version,
                                             String operator, boolean success, String detail) {
        return new GraphAuditEvent(action, RESOURCE_SCRIPT_NODE, nodeId, version, operator, success, detail, Instant.now());
    }
}
