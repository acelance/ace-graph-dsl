package io.acelance.graph.dsl.persistence.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Redis 持久化实现。
 */
public class RedisGraphDefinitionRepository implements GraphDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisGraphDefinitionRepository.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisGraphDefinitionRepository(StringRedisTemplate redis, ObjectMapper objectMapper, String keyPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
    }

    private String defKey(String graphId, String version) {
        return keyPrefix + "def:" + graphId + ":" + version;
    }

    private String versionsKey(String graphId) {
        return keyPrefix + "versions:" + graphId;
    }

    private String enabledKey(String graphId) {
        return keyPrefix + "enabled:" + graphId;
    }

    private String graphIdsKey() {
        return keyPrefix + "graph-ids";
    }

    private String recycleKey(String graphId, String recycleVersion) {
        return keyPrefix + "recycle:" + graphId + ":" + recycleVersion;
    }

    private String recycleIndexKey() {
        return keyPrefix + "recycle-index";
    }

    @Override
    public SaveDraftResult saveDraft(GraphDefinition def, String baseVersion) {
        SaveDraftResult result = DraftSaveSupport.saveDraft(
                def,
                baseVersion,
                version -> loadVersion(def.graphId(), version),
                () -> {
                    Set<String> versions = redis.opsForSet().members(versionsKey(def.graphId()));
                    return versions == null ? List.of() : new ArrayList<>(versions);
                },
                () -> {
                    try {
                        String json = objectMapper.writeValueAsString(def);
                        redis.opsForValue().set(defKey(def.graphId(), def.version()), json);
                        redis.opsForSet().add(versionsKey(def.graphId()), def.version());
                        redis.opsForSet().add(graphIdsKey(), def.graphId());
                    } catch (JsonProcessingException e) {
                        throw new IllegalArgumentException("GraphDefinition 序列化失败", e);
                    }
                });
        if (result.changed()) {
            log.info("新增 DSL 草稿(Redis), graphId={}, version={}", def.graphId(), def.version());
        }
        return result;
    }

    @Override
    public GraphDefinition loadVersion(String graphId, String version) {
        String json = redis.opsForValue().get(defKey(graphId, version));
        return deserialize(json);
    }

    @Override
    public GraphDefinition loadLatest(String graphId) {
        Set<String> versions = redis.opsForSet().members(versionsKey(graphId));
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        GraphDefinition latest = null;
        for (String version : versions) {
            GraphDefinition def = loadVersion(graphId, version);
            if (def != null) {
                latest = def;
            }
        }
        return latest;
    }

    @Override
    public List<GraphDefinition> listVersions(String graphId) {
        Set<String> versions = redis.opsForSet().members(versionsKey(graphId));
        if (versions == null) {
            return List.of();
        }
        List<GraphDefinition> result = new ArrayList<>();
        for (String version : versions) {
            GraphDefinition def = loadVersion(graphId, version);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    @Override
    public List<GraphDefinition> listAll() {
        List<GraphDefinition> result = new ArrayList<>();
        for (String graphId : listGraphIds()) {
            GraphDefinition latest = loadLatest(graphId);
            if (latest != null) {
                result.add(latest);
            }
        }
        return result;
    }

    @Override
    public void markEnabled(String graphId, String version) {
        if (loadVersion(graphId, version) == null) {
            throw new IllegalArgumentException("版本不存在: " + graphId + "@" + version);
        }
        redis.opsForValue().set(enabledKey(graphId), version + "|" + Instant.now());
    }

    @Override
    public GraphDefinition getEnabled(String graphId) {
        String raw = redis.opsForValue().get(enabledKey(graphId));
        if (raw == null) {
            return null;
        }
        String version = raw.split("\\|")[0];
        return loadVersion(graphId, version);
    }

    @Override
    public List<GraphDefinition> listEnabled() {
        List<GraphDefinition> result = new ArrayList<>();
        for (String graphId : listGraphIds()) {
            GraphDefinition def = getEnabled(graphId);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    @Override
    public void disableCurrentEnabled(String graphId) {
        redis.delete(enabledKey(graphId));
    }

    @Override
    public List<String> listGraphIds() {
        Set<String> ids = redis.opsForSet().members(graphIdsKey());
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    @Override
    public String moveToRecycle(String graphId) {
        GraphRecycleSnapshot snapshot = GraphRecycleSupport.buildSnapshot(this, graphId);
        if (snapshot == null) {
            throw new IllegalArgumentException("正式库不存在该图: " + graphId);
        }
        String recycleVersion = insertRecycleMetaWithRetry(graphId, snapshot);
        // 删除正式定义与启用指针
        Set<String> versions = redis.opsForSet().members(versionsKey(graphId));
        if (versions != null) {
            for (String version : versions) {
                redis.delete(defKey(graphId, version));
            }
        }
        redis.delete(versionsKey(graphId));
        redis.delete(enabledKey(graphId));
        redis.opsForSet().remove(graphIdsKey(), graphId);
        log.info("图已移入回收箱(Redis), graphId={}, recycleVersion={}", graphId, recycleVersion);
        return recycleVersion;
    }

    @Override
    public List<GraphRecycleEntry> listRecycle() {
        Set<String> members = redis.opsForZSet().reverseRange(recycleIndexKey(), 0, -1);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<GraphRecycleEntry> result = new ArrayList<>();
        for (String member : members) {
            int sep = member.indexOf('\0');
            if (sep <= 0) {
                continue;
            }
            String graphId = member.substring(0, sep);
            String recycleVersion = member.substring(sep + 1);
            GraphRecycleEntryMeta meta = loadRecycleMeta(graphId, recycleVersion);
            if (meta != null) {
                result.add(new GraphRecycleEntry(
                        meta.graphId(), meta.displayName(), meta.recycleVersion(),
                        Instant.parse(meta.deletedAt())));
            }
        }
        result.sort(Comparator.comparing(GraphRecycleEntry::deletedAt).reversed());
        return result;
    }

    @Override
    public GraphRecycleSnapshot loadRecycle(String graphId, String recycleVersion) {
        GraphRecycleEntryMeta meta = loadRecycleMeta(graphId, recycleVersion);
        return meta == null ? null : meta.snapshot();
    }

    @Override
    public GraphRecycleSnapshot restoreFromRecycle(String graphId, String recycleVersion) {
        GraphRecycleEntryMeta meta = loadRecycleMeta(graphId, recycleVersion);
        if (meta == null) {
            throw new IllegalArgumentException(
                    "回收行不存在: " + graphId + " / " + recycleVersion);
        }
        GraphRecycleSnapshot snapshot = meta.snapshot();
        GraphRecycleSupport.validateSnapshotForRestore(snapshot);
        if (existsInFormalStore(graphId)) {
            log.warn("恢复冲突(Redis)：正式库已有同名图, graphId={}", graphId);
            throw new IllegalStateException(GraphRecycleMessages.RESTORE_CONFLICT);
        }
        GraphDefinition latest = null;
        for (GraphDefinition def : snapshot.definitions()) {
            try {
                String json = objectMapper.writeValueAsString(def);
                redis.opsForValue().set(defKey(graphId, def.version()), json);
                redis.opsForSet().add(versionsKey(graphId), def.version());
                latest = def;
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("GraphDefinition 序列化失败", e);
            }
        }
        redis.opsForSet().add(graphIdsKey(), graphId);
        if (snapshot.enabledVersion() != null && !snapshot.enabledVersion().isBlank()) {
            redis.opsForValue().set(enabledKey(graphId),
                    snapshot.enabledVersion() + "|" + Instant.now());
        }
        redis.delete(recycleKey(graphId, recycleVersion));
        redis.opsForZSet().remove(recycleIndexKey(), graphId + "\0" + recycleVersion);
        log.info("从回收箱恢复完成(Redis), graphId={}, recycleVersion={}, latestVersion={}",
                graphId, recycleVersion, latest != null ? latest.version() : null);
        return snapshot;
    }

    @Override
    public boolean purgeRecycle(String graphId, String recycleVersion) {
        Boolean deleted = redis.delete(recycleKey(graphId, recycleVersion));
        redis.opsForZSet().remove(recycleIndexKey(), graphId + "\0" + recycleVersion);
        log.info("彻底清除回收行(Redis), graphId={}, recycleVersion={}, deleted={}",
                graphId, recycleVersion, Boolean.TRUE.equals(deleted));
        return Boolean.TRUE.equals(deleted);
    }

    @Override
    public boolean existsInFormalStore(String graphId) {
        Boolean inIds = redis.opsForSet().isMember(graphIdsKey(), graphId);
        if (Boolean.TRUE.equals(inIds)) {
            return true;
        }
        return redis.opsForValue().get(enabledKey(graphId)) != null;
    }

    /** 写入回收元数据；唯一键冲突则换后缀重试 */
    private String insertRecycleMetaWithRetry(String graphId, GraphRecycleSnapshot snapshot) {
        for (int attempt = 1; attempt <= 8; attempt++) {
            String recycleVersion = RecycleVersionGenerator.generate();
            try {
                GraphRecycleEntryMeta meta = new GraphRecycleEntryMeta(
                        graphId, snapshot.displayName(), recycleVersion,
                        Instant.now().toString(), snapshot);
                String json = objectMapper.writeValueAsString(meta);
                Boolean created = redis.opsForValue()
                        .setIfAbsent(recycleKey(graphId, recycleVersion), json);
                if (Boolean.TRUE.equals(created)) {
                    redis.opsForZSet().add(recycleIndexKey(), graphId + "\0" + recycleVersion,
                            Instant.now().toEpochMilli());
                    return recycleVersion;
                }
                log.warn("recycle_version 冲突(Redis)，重试, graphId={}, attempt={}", graphId, attempt);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("回收快照序列化失败", e);
            }
        }
        throw new IllegalStateException("无法生成唯一 recycle_version, graphId=" + graphId);
    }

    private GraphRecycleEntryMeta loadRecycleMeta(String graphId, String recycleVersion) {
        String json = redis.opsForValue().get(recycleKey(graphId, recycleVersion));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, GraphRecycleEntryMeta.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("回收快照反序列化失败", e);
        }
    }

    private GraphDefinition deserialize(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, GraphDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GraphDefinition 反序列化失败", e);
        }
    }

    /** Redis 回收行存储结构 */
    private record GraphRecycleEntryMeta(
            String graphId,
            String displayName,
            String recycleVersion,
            String deletedAt,
            GraphRecycleSnapshot snapshot
    ) {
    }
}
