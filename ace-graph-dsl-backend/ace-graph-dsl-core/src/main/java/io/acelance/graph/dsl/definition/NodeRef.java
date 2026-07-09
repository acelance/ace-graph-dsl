package io.acelance.graph.dsl.definition;

import java.util.Map;

/**
 * 节点引用：DSL 中对已注册节点的引用，可携带配置属性与画布坐标。
 *
 * @param nodeId 已注册节点 ID
 * @param config 节点配置属性
 * @param x      画布横坐标（可选，仅用于前端布局还原；不参与编译）
 * @param y      画布纵坐标（可选，仅用于前端布局还原；不参与编译）
 */
public record NodeRef(
        String nodeId,
        Map<String, Object> config,
        Double x,
        Double y
) {}
