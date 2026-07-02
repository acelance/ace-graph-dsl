package io.acelance.graph.dsl.persistence.memory;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.persistence.support.DraftSaveSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存持久化（测试 / 降级 fallback）。
 */
public class InMemoryGraphDefinitionRepository implements GraphDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryGraphDefinitionRepository.class);

    /** graphId -> version -> definition（同版本仅保留一条，不可覆盖） */
    private final Map<String, Map<String, GraphDefinition>> versionsByGraphId = new ConcurrentHashMap<>();
    private final Map<String, GraphDefinition> latestDraftByGraphId = new ConcurrentHashMap<>();
    private final Map<String, GraphDefinition> enabledByGraphId = new ConcurrentHashMap<>();

    @Override
    public SaveDraftResult saveDraft(GraphDefinition def, String baseVersion) {
        SaveDraftResult result = DraftSaveSupport.saveDraft(
                def,
                baseVersion,
                version -> loadVersion(def.graphId(), version),
                () -> listVersions(def.graphId()).stream().map(GraphDefinition::version).toList(),
                () -> {
                    Map<String, GraphDefinition> versions = versionsByGraphId.computeIfAbsent(
                            def.graphId(), k -> new ConcurrentHashMap<>());
                    versions.put(def.version(), def);
                    latestDraftByGraphId.put(def.graphId(), def);
                });
        if (result.changed()) {
            log.info("新增 DSL 草稿(内存), graphId={}, version={}", def.graphId(), def.version());
        }
        return result;
    }

    @Override
    public GraphDefinition loadVersion(String graphId, String version) {
        Map<String, GraphDefinition> versions = versionsByGraphId.get(graphId);
        return versions == null ? null : versions.get(version);
    }

    @Override
    public GraphDefinition loadLatest(String graphId) {
        return latestDraftByGraphId.get(graphId);
    }

    @Override
    public List<GraphDefinition> listVersions(String graphId) {
        Map<String, GraphDefinition> versions = versionsByGraphId.get(graphId);
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(versions.values());
    }

    @Override
    public List<GraphDefinition> listAll() {
        List<GraphDefinition> result = new ArrayList<>();
        for (String graphId : versionsByGraphId.keySet()) {
            GraphDefinition latest = loadLatest(graphId);
            if (latest != null) {
                result.add(latest);
            }
        }
        return result;
    }

    @Override
    public void markEnabled(String graphId, String version) {
        GraphDefinition def = loadVersion(graphId, version);
        if (def == null) {
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        enabledByGraphId.put(graphId, def);
    }

    @Override
    public GraphDefinition getEnabled(String graphId) {
        return enabledByGraphId.get(graphId);
    }

    @Override
    public List<GraphDefinition> listEnabled() {
        return new ArrayList<>(enabledByGraphId.values());
    }

    @Override
    public void disableCurrentEnabled(String graphId) {
        enabledByGraphId.remove(graphId);
    }

    @Override
    public List<String> listGraphIds() {
        return new ArrayList<>(versionsByGraphId.keySet());
    }
}
