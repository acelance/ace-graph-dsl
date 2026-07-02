package io.acelance.graph.dsl.audit;

/**
 * 标准审计操作类型常量。
 */
public final class GraphAuditActions {

    private GraphAuditActions() {
    }

    public static final String PUBLISH = "PUBLISH";
    public static final String ROLLBACK = "ROLLBACK";
    public static final String SCRIPT_NODE_CREATE = "SCRIPT_NODE_CREATE";
    public static final String SCRIPT_NODE_UPDATE = "SCRIPT_NODE_UPDATE";
    public static final String SCRIPT_NODE_DELETE = "SCRIPT_NODE_DELETE";
}
