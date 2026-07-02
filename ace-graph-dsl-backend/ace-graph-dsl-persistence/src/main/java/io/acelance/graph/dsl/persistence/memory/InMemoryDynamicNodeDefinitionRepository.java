package io.acelance.graph.dsl.persistence.memory;

import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.persistence.DynamicNodeDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存动态节点定义持久化（测试 / 降级 fallback）。
 */
public class InMemoryDynamicNodeDefinitionRepository implements DynamicNodeDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDynamicNodeDefinitionRepository.class);

    private final Map<String, DynamicNodeDefinition> byId = new ConcurrentHashMap<>();

    @Override
    public DynamicNodeDefinition save(DynamicNodeDefinition def) {
        byId.put(def.nodeId(), def);
        log.info("保存脚本节点定义(内存), nodeId={}", def.nodeId());
        return def;
    }

    @Override
    public Optional<DynamicNodeDefinition> findById(String nodeId) {
        return Optional.ofNullable(byId.get(nodeId));
    }

    @Override
    public List<DynamicNodeDefinition> findAllEnabled() {
        return byId.values().stream().filter(DynamicNodeDefinition::enabled).toList();
    }

    @Override
    public List<DynamicNodeDefinition> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void delete(String nodeId) {
        byId.remove(nodeId);
    }
}
