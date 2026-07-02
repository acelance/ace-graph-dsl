package io.acelance.graph.dsl.definition;

import java.util.Map;

/**
 * 节点引用：DSL 中对已注册节点的引用，可携带配置属性。
 *
 * @param nodeId 已注册节点 ID
 * @param config 节点配置属性
 */
public record NodeRef(
        String nodeId,
        Map<String, Object> config
) {}
