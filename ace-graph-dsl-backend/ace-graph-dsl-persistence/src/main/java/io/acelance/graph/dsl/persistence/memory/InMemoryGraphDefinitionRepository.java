package io.acelance.graph.dsl.persistence.memory;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.GraphRecycleEntry;
import io.acelance.graph.dsl.persistence.GraphRecycleMessages;
import io.acelance.graph.dsl.persistence.GraphRecycleSnapshot;
import io.acelance.graph.dsl.persistence.GraphRecycleSupport;
import io.acelance.graph.dsl.persistence.RecycleVersionGenerator;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.persistence.support.DraftSaveSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    /** graphId + "\\0" + recycleVersion -> 回收条目 */
    private final Map<String, RecycleRecord> recycleByKey = new ConcurrentHashMap<>();
    private final Object recycleLock = new Object();

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

    @Override
    public String moveToRecycle(String graphId) {
        synchronized (recycleLock) {
            GraphRecycleSnapshot snapshot = GraphRecycleSupport.buildSnapshot(this, graphId);
            if (snapshot == null) {
                throw new IllegalArgumentException("正式库不存在该图: " + graphId);
            }
            String recycleVersion = allocateRecycleVersion(graphId);
            recycleByKey.put(recycleKey(graphId, recycleVersion),
                    new RecycleRecord(graphId, snapshot.displayName(), recycleVersion,
                            Instant.now(), snapshot));
            versionsByGraphId.remove(graphId);
            latestDraftByGraphId.remove(graphId);
            enabledByGraphId.remove(graphId);
            log.info("图已移入回收箱(内存), graphId={}, recycleVersion={}", graphId, recycleVersion);
            return recycleVersion;
        }
    }

    @Override
    public List<GraphRecycleEntry> listRecycle() {
        return recycleByKey.values().stream()
                .sorted(Comparator.comparing(RecycleRecord::deletedAt).reversed())
                .map(r -> new GraphRecycleEntry(r.graphId(), r.displayName(),
                        r.recycleVersion(), r.deletedAt()))
                .toList();
    }

    @Override
    public GraphRecycleSnapshot loadRecycle(String graphId, String recycleVersion) {
        RecycleRecord record = recycleByKey.get(recycleKey(graphId, recycleVersion));
        return record == null ? null : record.snapshot();
    }

    @Override
    public GraphRecycleSnapshot restoreFromRecycle(String graphId, String recycleVersion) {
        synchronized (recycleLock) {
            RecycleRecord record = recycleByKey.get(recycleKey(graphId, recycleVersion));
            if (record == null) {
                throw new IllegalArgumentException(
                        "回收行不存在: " + graphId + " / " + recycleVersion);
            }
            GraphRecycleSnapshot snapshot = record.snapshot();
            GraphRecycleSupport.validateSnapshotForRestore(snapshot);
            if (existsInFormalStore(graphId)) {
                log.warn("恢复冲突(内存)：正式库已有同名图, graphId={}", graphId);
                throw new IllegalStateException(GraphRecycleMessages.RESTORE_CONFLICT);
            }
            Map<String, GraphDefinition> versions = new ConcurrentHashMap<>();
            GraphDefinition latest = null;
            for (GraphDefinition def : snapshot.definitions()) {
                versions.put(def.version(), def);
                latest = def;
            }
            versionsByGraphId.put(graphId, versions);
            if (latest != null) {
                latestDraftByGraphId.put(graphId, latest);
            }
            if (snapshot.enabledVersion() != null && !snapshot.enabledVersion().isBlank()) {
                GraphDefinition enabled = versions.get(snapshot.enabledVersion());
                if (enabled != null) {
                    enabledByGraphId.put(graphId, enabled);
                }
            }
            recycleByKey.remove(recycleKey(graphId, recycleVersion));
            log.info("从回收箱恢复完成(内存), graphId={}, recycleVersion={}", graphId, recycleVersion);
            return snapshot;
        }
    }

    @Override
    public boolean purgeRecycle(String graphId, String recycleVersion) {
        RecycleRecord removed = recycleByKey.remove(recycleKey(graphId, recycleVersion));
        log.info("彻底清除回收行(内存), graphId={}, recycleVersion={}, deleted={}",
                graphId, recycleVersion, removed != null);
        return removed != null;
    }

    @Override
    public boolean existsInFormalStore(String graphId) {
        return versionsByGraphId.containsKey(graphId) || enabledByGraphId.containsKey(graphId);
    }

    private String allocateRecycleVersion(String graphId) {
        for (int i = 0; i < 8; i++) {
            String recycleVersion = RecycleVersionGenerator.generate();
            if (!recycleByKey.containsKey(recycleKey(graphId, recycleVersion))) {
                return recycleVersion;
            }
        }
        throw new IllegalStateException("无法生成唯一 recycle_version, graphId=" + graphId);
    }

    private static String recycleKey(String graphId, String recycleVersion) {
        return graphId + '\0' + recycleVersion;
    }

    private record RecycleRecord(
            String graphId,
            String displayName,
            String recycleVersion,
            Instant deletedAt,
            GraphRecycleSnapshot snapshot
    ) {
    }
}
