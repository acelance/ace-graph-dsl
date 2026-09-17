package io.acelance.graph.dsl.definition;

/**
 * 按 graphId 解析图定义（enabled / latest / builtin 由接入方组合）。
 */
@FunctionalInterface
public interface GraphDefinitionSource {

    /** @return 找不到时返回 null */
    GraphDefinition get(String graphId);
}
