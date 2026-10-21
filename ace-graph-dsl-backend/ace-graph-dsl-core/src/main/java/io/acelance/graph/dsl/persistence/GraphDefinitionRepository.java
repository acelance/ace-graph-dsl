package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;

import java.util.List;

/**
 * Graph DSL 持久化抽象。由 sqlite / redis / jdbc 等实现提供。
 */
public interface GraphDefinitionRepository {

    /**
     * 保存草稿。
     *
     * @param def         待保存定义
     * @param baseVersion 对比基准版本号（编辑起点）；为空时默认与 {@code def.version()} 相同
     */
    SaveDraftResult saveDraft(GraphDefinition def, String baseVersion);

    default SaveDraftResult saveDraft(GraphDefinition def) {
        return saveDraft(def, def.version());
    }

    GraphDefinition loadVersion(String graphId, String version);

    GraphDefinition loadLatest(String graphId);

    List<GraphDefinition> listVersions(String graphId);

    List<GraphDefinition> listAll();

    void markEnabled(String graphId, String version);

    GraphDefinition getEnabled(String graphId);

    List<GraphDefinition> listEnabled();

    void disableCurrentEnabled(String graphId);

    /** 列出所有 graphId（用于多业务 DSL 管理） */
    List<String> listGraphIds();

    /**
     * 将正式图移入回收箱：写入快照后删除该 {@code graphId} 的全部定义行与启用行。
     *
     * <p>须在同一数据库事务内完成。调用方负责口令校验、内置图拒绝与缓存卸载。</p>
     *
     * @param graphId 图 ID
     * @return 新生成的 {@code recycle_version}
     * @throws IllegalArgumentException 正式库不存在该图
     */
    String moveToRecycle(String graphId);

    /** 回收箱列表（不含快照正文） */
    List<GraphRecycleEntry> listRecycle();

    /**
     * 按 graphId + recycleVersion 加载回收快照。
     *
     * @return 快照；不存在返回 {@code null}
     */
    GraphRecycleSnapshot loadRecycle(String graphId, String recycleVersion);

    /**
     * 从回收箱恢复到正式库，并删除该回收行。
     *
     * <p>须在同一数据库事务内：冲突检查 → 插入定义 → 写启用指针（若有）→ 删回收行。
     * 正式定义表或启用表已有同 {@code graphId} 时抛出固定文案冲突。</p>
     *
     * @param graphId        图 ID
     * @param recycleVersion 回收区分键
     * @return 恢复后的快照（含 enabledVersion，供调用方刷新/卸载缓存）
     * @throws IllegalArgumentException 回收行不存在或快照不合法
     * @throws IllegalStateException    正式库已有同名图（文案见 {@link GraphRecycleMessages#RESTORE_CONFLICT}）
     */
    GraphRecycleSnapshot restoreFromRecycle(String graphId, String recycleVersion);

    /**
     * 物理删除一条回收行（不可恢复）。
     *
     * @param graphId        图 ID
     * @param recycleVersion 回收区分键
     * @return 是否删除到行
     */
    boolean purgeRecycle(String graphId, String recycleVersion);

    /**
     * 正式定义表或启用表是否已存在该 {@code graphId}。
     */
    boolean existsInFormalStore(String graphId);
}
