package io.acelance.graph.dsl.persistence;

/**
 * 图回收箱对外固定文案。
 */
public final class GraphRecycleMessages {

    private GraphRecycleMessages() {
    }

    /** 恢复时正式定义表或启用表已有同 graph_id */
    public static final String RESTORE_CONFLICT = "请先删除正式列表中的同名图";
}
