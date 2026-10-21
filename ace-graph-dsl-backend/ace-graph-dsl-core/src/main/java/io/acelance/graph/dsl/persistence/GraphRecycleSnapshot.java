package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;

import java.util.List;

/**
 * 回收箱快照：删除前该 {@code graphId} 的全部定义版本 + 启用指针。
 *
 * @param graphId         图 ID
 * @param displayName     显示名
 * @param enabledVersion  删除前启用表中的 DSL 版本；未发布则为 {@code null}
 * @param definitions     删除前全部 {@link GraphDefinition}
 */
public record GraphRecycleSnapshot(
        String graphId,
        String displayName,
        String enabledVersion,
        List<GraphDefinition> definitions
) {
}
