package io.acelance.graph.dsl.persistence.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.SaveDraftResult;
import io.acelance.graph.dsl.persistence.support.DraftSaveSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JDBC 的 Graph DSL 持久化（SQLite / MySQL / PostgreSQL / Oracle 等通用实现）。
 */
public abstract class AbstractJdbcGraphDefinitionRepository implements GraphDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcGraphDefinitionRepository.class);

    protected final JdbcTemplate jdbcTemplate;
    protected final ObjectMapper objectMapper;
    protected final String tablePrefix;

    protected AbstractJdbcGraphDefinitionRepository(JdbcTemplate jdbcTemplate,
                                                    ObjectMapper objectMapper,
                                                    String tablePrefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tablePrefix = normalizePrefix(tablePrefix);
        initSchema();
    }

    protected abstract void initSchema();

    protected String defTable() {
        return tablePrefix + "definition";
    }

    protected String enabledTable() {
        return tablePrefix + "enabled";
    }

    @Override
    public SaveDraftResult saveDraft(GraphDefinition def, String baseVersion) {
        SaveDraftResult result = DraftSaveSupport.saveDraft(
                def,
                baseVersion,
                version -> loadVersion(def.graphId(), version),
                () -> listVersions(def.graphId()).stream().map(GraphDefinition::version).toList(),
                () -> insertDraft(def));
        if (result.changed()) {
            log.info("新增 DSL 草稿, graphId={}, version={}, baseVersion={}",
                    def.graphId(), def.version(), baseVersion);
        } else {
            log.info("DSL 草稿无变更，跳过保存, graphId={}, baseVersion={}", def.graphId(), baseVersion);
        }
        return result;
    }

    private void insertDraft(GraphDefinition def) {
        try {
            String json = objectMapper.writeValueAsString(def);
            jdbcTemplate.update(
                    "INSERT INTO " + defTable() + " (graph_id, version, display_name, description, content_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    def.graphId(), def.version(), def.displayName(), def.description(), json, Timestamp.from(Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("GraphDefinition 序列化失败", e);
        }
    }

    @Override
    public GraphDefinition loadVersion(String graphId, String version) {
        List<GraphDefinition> list = jdbcTemplate.query(
                "SELECT content_json FROM " + defTable() + " WHERE graph_id = ? AND version = ? ORDER BY id DESC LIMIT 1",
                graphDefinitionRowMapper(), graphId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public GraphDefinition loadLatest(String graphId) {
        List<GraphDefinition> list = jdbcTemplate.query(
                "SELECT content_json FROM " + defTable() + " WHERE graph_id = ? ORDER BY id DESC LIMIT 1",
                graphDefinitionRowMapper(), graphId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<GraphDefinition> listVersions(String graphId) {
        return jdbcTemplate.query(
                "SELECT content_json FROM " + defTable()
                        + " WHERE graph_id = ? AND id IN ("
                        + "SELECT MAX(id) FROM " + defTable() + " WHERE graph_id = ? GROUP BY version"
                        + ") ORDER BY id ASC",
                graphDefinitionRowMapper(), graphId, graphId);
    }

    @Override
    public List<GraphDefinition> listAll() {
        List<String> graphIds = listGraphIds();
        List<GraphDefinition> result = new ArrayList<>();
        for (String graphId : graphIds) {
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
        upsertEnabled(graphId, version);
        log.info("启用 DSL 版本, graphId={}, version={}", graphId, version);
    }

    protected void upsertEnabled(String graphId, String version) {
        jdbcTemplate.update("DELETE FROM " + enabledTable() + " WHERE graph_id = ?", graphId);
        jdbcTemplate.update(
                "INSERT INTO " + enabledTable() + " (graph_id, version, enabled_at) VALUES (?, ?, ?)",
                graphId, version, Timestamp.from(Instant.now()));
    }

    @Override
    public GraphDefinition getEnabled(String graphId) {
        List<String> versions = jdbcTemplate.query(
                "SELECT version FROM " + enabledTable() + " WHERE graph_id = ?",
                (rs, rowNum) -> rs.getString("version"), graphId);
        if (versions.isEmpty()) {
            return null;
        }
        return loadVersion(graphId, versions.get(0));
    }

    @Override
    public List<GraphDefinition> listEnabled() {
        List<String> graphIds = jdbcTemplate.query(
                "SELECT graph_id FROM " + enabledTable(),
                (rs, rowNum) -> rs.getString("graph_id"));
        List<GraphDefinition> result = new ArrayList<>();
        for (String graphId : graphIds) {
            GraphDefinition def = getEnabled(graphId);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    @Override
    public void disableCurrentEnabled(String graphId) {
        jdbcTemplate.update("DELETE FROM " + enabledTable() + " WHERE graph_id = ?", graphId);
    }

    @Override
    public List<String> listGraphIds() {
        return jdbcTemplate.query(
                "SELECT DISTINCT graph_id FROM " + defTable() + " ORDER BY graph_id",
                (rs, rowNum) -> rs.getString("graph_id"));
    }

    private RowMapper<GraphDefinition> graphDefinitionRowMapper() {
        return (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString("content_json"), GraphDefinition.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("GraphDefinition 反序列化失败", e);
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
