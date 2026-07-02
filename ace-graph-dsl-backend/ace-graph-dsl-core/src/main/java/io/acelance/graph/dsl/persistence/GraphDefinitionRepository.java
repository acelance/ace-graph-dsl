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
}
