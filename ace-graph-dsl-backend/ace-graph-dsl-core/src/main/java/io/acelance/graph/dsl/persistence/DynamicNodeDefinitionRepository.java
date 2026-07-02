package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.DynamicNodeDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 动态（脚本）节点定义持久化接口。
 */
public interface DynamicNodeDefinitionRepository {

    DynamicNodeDefinition save(DynamicNodeDefinition def);

    Optional<DynamicNodeDefinition> findById(String nodeId);

    List<DynamicNodeDefinition> findAllEnabled();

    List<DynamicNodeDefinition> findAll();

    void delete(String nodeId);
}
