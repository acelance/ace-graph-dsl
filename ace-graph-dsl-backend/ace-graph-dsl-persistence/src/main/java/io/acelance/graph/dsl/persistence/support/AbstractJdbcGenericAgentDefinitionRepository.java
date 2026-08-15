package io.acelance.graph.dsl.persistence.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.definition.GenericAgentDefinition;
import io.acelance.graph.dsl.persistence.GenericAgentDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的通用 agent 节点定义持久化通用实现（SQLite / MySQL / PostgreSQL 等）。
 *
 * <p>整条定义以 JSON 存 {@code content_json}，另抽出 {@code display_name / model_id /
 * enabled} 等便于运维直查的列，与脚本节点表的存储策略保持一致。</p>
 *
 * <p><b>安全约定</b>：落库前 api-key 必须已由上层（{@code GenericAgentNodeService}）掩码，
 * 本层不再做二次判断，避免掩码语义分散。</p>
 */
public abstract class AbstractJdbcGenericAgentDefinitionRepository implements GenericAgentDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcGenericAgentDefinitionRepository.class);

    protected final JdbcTemplate jdbcTemplate;
    protected final ObjectMapper objectMapper;
    protected final String tablePrefix;

    protected AbstractJdbcGenericAgentDefinitionRepository(JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper,
                                                           String tablePrefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tablePrefix = normalizePrefix(tablePrefix);
        initSchema();
    }

    protected abstract void initSchema();

    protected String agentDefTable() {
        return tablePrefix + "agent_definition";
    }

    @Override
    public GenericAgentDefinition save(GenericAgentDefinition def) {
        try {
            String json = objectMapper.writeValueAsString(def);
            Timestamp createdAt = Timestamp.from(def.createdAt() != null ? def.createdAt() : Instant.now());
            Timestamp updatedAt = Timestamp.from(def.updatedAt() != null ? def.updatedAt() : Instant.now());
            String modelId = def.spec() != null ? def.spec().modelId() : null;
            int updated = jdbcTemplate.update(
                    "UPDATE " + agentDefTable() + " SET display_name = ?, content_json = ?, model_id = ?, "
                            + "version = ?, enabled = ?, created_by = ?, updated_at = ? WHERE node_id = ?",
                    def.effectiveDisplayName(), json, modelId, def.version(), def.enabled() ? 1 : 0,
                    def.createdBy(), updatedAt, def.nodeId());
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO " + agentDefTable() + " (node_id, display_name, content_json, model_id, "
                                + "version, enabled, created_by, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        def.nodeId(), def.effectiveDisplayName(), json, modelId, def.version(),
                        def.enabled() ? 1 : 0, def.createdBy(), createdAt, updatedAt);
            }
            log.info("保存通用 agent 节点定义, nodeId={}", def.nodeId());
            return def;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("GenericAgentDefinition 序列化失败", e);
        }
    }

    @Override
    public Optional<GenericAgentDefinition> findById(String nodeId) {
        List<GenericAgentDefinition> list = jdbcTemplate.query(
                "SELECT content_json FROM " + agentDefTable() + " WHERE node_id = ?",
                rowMapper(), nodeId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<GenericAgentDefinition> findAllEnabled() {
        return jdbcTemplate.query(
                "SELECT content_json FROM " + agentDefTable() + " WHERE enabled = 1 ORDER BY node_id",
                rowMapper());
    }

    @Override
    public List<GenericAgentDefinition> findAll() {
        return jdbcTemplate.query(
                "SELECT content_json FROM " + agentDefTable() + " ORDER BY node_id",
                rowMapper());
    }

    @Override
    public void delete(String nodeId) {
        jdbcTemplate.update("DELETE FROM " + agentDefTable() + " WHERE node_id = ?", nodeId);
    }

    private RowMapper<GenericAgentDefinition> rowMapper() {
        return (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString("content_json"), GenericAgentDefinition.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("GenericAgentDefinition 反序列化失败", e);
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
