package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;

/**
 * 保存草稿结果。
 *
 * @param definition 基准或保存后的定义
 * @param changed    是否新增了版本记录
 * @param action     SKIP（相对 baseVersion 无内容变更）/ INSERT（新版本）
 */
public record SaveDraftResult(GraphDefinition definition, boolean changed, String action) {

    public static final String ACTION_SKIP = "SKIP";
    public static final String ACTION_INSERT = "INSERT";

    public static SaveDraftResult skip(GraphDefinition definition) {
        return new SaveDraftResult(definition, false, ACTION_SKIP);
    }

    public static SaveDraftResult insert(GraphDefinition definition) {
        return new SaveDraftResult(definition, true, ACTION_INSERT);
    }
}
