package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GenericAgentDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 通用 agent 节点定义持久化接口（与 {@link DynamicNodeDefinitionRepository} 对称）。
 */
public interface GenericAgentDefinitionRepository {

    GenericAgentDefinition save(GenericAgentDefinition def);

    Optional<GenericAgentDefinition> findById(String nodeId);

    List<GenericAgentDefinition> findAllEnabled();

    List<GenericAgentDefinition> findAll();

    void delete(String nodeId);
}
