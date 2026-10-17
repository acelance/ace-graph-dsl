package io.acelance.graph.dsl.persistence;

import java.time.Instant;

/**
 * 回收箱列表项（不携带完整快照）。
 *
 * @param graphId        图 ID
 * @param displayName    显示名
 * @param recycleVersion 回收区分键（非 DSL version）
 * @param deletedAt      删除进回收箱的时间
 */
public record GraphRecycleEntry(
        String graphId,
        String displayName,
        String recycleVersion,
        Instant deletedAt
) {
}
