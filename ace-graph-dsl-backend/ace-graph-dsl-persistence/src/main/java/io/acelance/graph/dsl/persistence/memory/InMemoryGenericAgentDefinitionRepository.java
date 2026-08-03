package io.acelance.graph.dsl.persistence.memory;

import io.acelance.graph.dsl.definition.GenericAgentDefinition;
import io.acelance.graph.dsl.persistence.GenericAgentDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存通用 agent 节点定义持久化（测试 / 降级 fallback）。
 */
public class InMemoryGenericAgentDefinitionRepository implements GenericAgentDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryGenericAgentDefinitionRepository.class);

    private final Map<String, GenericAgentDefinition> byId = new ConcurrentHashMap<>();

    @Override
    public GenericAgentDefinition save(GenericAgentDefinition def) {
        byId.put(def.nodeId(), def);
        log.info("保存通用 agent 节点定义(内存), nodeId={}", def.nodeId());
        return def;
    }

    @Override
    public Optional<GenericAgentDefinition> findById(String nodeId) {
        return Optional.ofNullable(byId.get(nodeId));
    }

    @Override
    public List<GenericAgentDefinition> findAllEnabled() {
        return byId.values().stream()
                .filter(GenericAgentDefinition::enabled)
                .sorted(Comparator.comparing(GenericAgentDefinition::nodeId))
                .toList();
    }

    @Override
    public List<GenericAgentDefinition> findAll() {
        List<GenericAgentDefinition> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(GenericAgentDefinition::nodeId));
        return all;
    }

    @Override
    public void delete(String nodeId) {
        byId.remove(nodeId);
    }
}
