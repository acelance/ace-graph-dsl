package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;

/**
 * 保存草稿请求：definition 为待保存内容，baseVersion 为编辑对比基准版本号。
 */
public record SaveDraftRequest(GraphDefinition definition, String baseVersion) {
}
