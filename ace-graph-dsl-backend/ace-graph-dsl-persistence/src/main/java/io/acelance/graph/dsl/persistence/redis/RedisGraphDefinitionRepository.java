package io.acelance.graph.dsl.persistence.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.persistence.support.DraftSaveSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
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
}
