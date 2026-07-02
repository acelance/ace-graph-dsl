package io.acelance.graph.dsl.persistence.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.persistence.DynamicNodeDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的动态（脚本）节点定义持久化通用实现（SQLite / MySQL / PostgreSQL 等）。
 */
public abstract class AbstractJdbcDynamicNodeDefinitionRepository implements DynamicNodeDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcDynamicNodeDefinitionRepository.class);

    protected final JdbcTemplate jdbcTemplate;
    protected final ObjectMapper objectMapper;
    protected final String tablePrefix;

    protected AbstractJdbcDynamicNodeDefinitionRepository(JdbcTemplate jdbcTemplate,
                                                          ObjectMapper objectMapper,
                                                          String tablePrefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tablePrefix = normalizePrefix(tablePrefix);
        initSchema();
    }

    protected abstract void initSchema();

    protected String nodeDefTable() {
        return tablePrefix + "node_definition";
    }

    @Override
    public DynamicNodeDefinition save(DynamicNodeDefinition def) {
        try {
            String json = objectMapper.writeValueAsString(def);
            Timestamp createdAt = Timestamp.from(def.createdAt() != null ? def.createdAt() : Instant.now());
            Timestamp updatedAt = Timestamp.from(def.updatedAt() != null ? def.updatedAt() : Instant.now());
            int updated = jdbcTemplate.update(
                    "UPDATE " + nodeDefTable() + " SET display_name = ?, content_json = ?, script_hash = ?, "
                            + "enabled = ?, created_by = ?, updated_at = ? WHERE node_id = ?",
                    def.displayName(), json, def.scriptHash(), def.enabled() ? 1 : 0,
                    def.createdBy(), updatedAt, def.nodeId());
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO " + nodeDefTable() + " (node_id, display_name, content_json, script_hash, "
                                + "enabled, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        def.nodeId(), def.displayName(), json, def.scriptHash(), def.enabled() ? 1 : 0,
                        def.createdBy(), createdAt, updatedAt);
            }
            log.info("保存脚本节点定义, nodeId={}", def.nodeId());
            return def;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("DynamicNodeDefinition 序列化失败", e);
        }
    }

    @Override
    public Optional<DynamicNodeDefinition> findById(String nodeId) {
        List<DynamicNodeDefinition> list = jdbcTemplate.query(
                "SELECT content_json FROM " + nodeDefTable() + " WHERE node_id = ?",
                rowMapper(), nodeId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<DynamicNodeDefinition> findAllEnabled() {
        return jdbcTemplate.query(
                "SELECT content_json FROM " + nodeDefTable() + " WHERE enabled = 1 ORDER BY node_id",
                rowMapper());
    }

    @Override
    public List<DynamicNodeDefinition> findAll() {
        return jdbcTemplate.query(
                "SELECT content_json FROM " + nodeDefTable() + " ORDER BY node_id",
                rowMapper());
    }

    @Override
    public void delete(String nodeId) {
        jdbcTemplate.update("DELETE FROM " + nodeDefTable() + " WHERE node_id = ?", nodeId);
    }

    private RowMapper<DynamicNodeDefinition> rowMapper() {
        return (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString("content_json"), DynamicNodeDefinition.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("DynamicNodeDefinition 反序列化失败", e);
            }
        };
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "ace_graph_dsl_";
        }
        return prefix.endsWith("_") ? prefix : prefix + "_";
    }
}
