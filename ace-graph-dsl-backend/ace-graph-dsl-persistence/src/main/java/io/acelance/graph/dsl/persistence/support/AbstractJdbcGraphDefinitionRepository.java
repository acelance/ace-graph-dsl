package io.acelance.graph.dsl.persistence.support;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JDBC 的 Graph DSL 持久化（SQLite / MySQL / PostgreSQL / Oracle 等通用实现）。
 */
public abstract class AbstractJdbcGraphDefinitionRepository implements GraphDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcGraphDefinitionRepository.class);

    /** 唯一键冲突时最多重试次数（换 recycle_version 后缀） */
    private static final int RECYCLE_VERSION_RETRY = 8;

    protected final JdbcTemplate jdbcTemplate;
    protected final ObjectMapper objectMapper;
    protected final String tablePrefix;
    private final TransactionTemplate transactionTemplate;

    protected AbstractJdbcGraphDefinitionRepository(JdbcTemplate jdbcTemplate,
                                                    ObjectMapper objectMapper,
                                                    String tablePrefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tablePrefix = normalizePrefix(tablePrefix);
        this.transactionTemplate = buildTransactionTemplate(jdbcTemplate.getDataSource());
        initSchema();
    }

    protected abstract void initSchema();

    protected String defTable() {
        return tablePrefix + "definition";
    }

    protected String enabledTable() {
        return tablePrefix + "enabled";
    }

    /** 回收箱表名 */
    protected String recycleTable() {
        return tablePrefix + "definition_recycle";
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
            // api-key 脱敏：落库前将通用 agent 节点的明文 key 掩码
            GraphDefinition toSave = AgentSecretMasking.mask(def);
            String json = objectMapper.writeValueAsString(toSave);
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

    @Override
    public String moveToRecycle(String graphId) {
        return transactionTemplate.execute(status -> {
            GraphRecycleSnapshot snapshot = GraphRecycleSupport.buildSnapshot(this, graphId);
            if (snapshot == null) {
                throw new IllegalArgumentException("正式库不存在该图: " + graphId);
            }
            String recycleVersion = insertRecycleRowWithRetry(snapshot);
            int defDeleted = jdbcTemplate.update("DELETE FROM " + defTable() + " WHERE graph_id = ?", graphId);
            int enabledDeleted = jdbcTemplate.update("DELETE FROM " + enabledTable() + " WHERE graph_id = ?", graphId);
            log.info("图已移入回收箱, graphId={}, recycleVersion={}, defDeleted={}, enabledDeleted={}",
                    graphId, recycleVersion, defDeleted, enabledDeleted);
            return recycleVersion;
        });
    }

    @Override
    public List<GraphRecycleEntry> listRecycle() {
        return jdbcTemplate.query(
                "SELECT graph_id, display_name, recycle_version, deleted_at FROM " + recycleTable()
                        + " ORDER BY deleted_at DESC, id DESC",
                (rs, rowNum) -> new GraphRecycleEntry(
                        rs.getString("graph_id"),
                        rs.getString("display_name"),
                        rs.getString("recycle_version"),
                        rs.getTimestamp("deleted_at").toInstant()));
    }

    @Override
    public GraphRecycleSnapshot loadRecycle(String graphId, String recycleVersion) {
        List<GraphRecycleSnapshot> list = jdbcTemplate.query(
                "SELECT snapshot_json FROM " + recycleTable()
                        + " WHERE graph_id = ? AND recycle_version = ?",
                recycleSnapshotRowMapper(), graphId, recycleVersion);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public GraphRecycleSnapshot restoreFromRecycle(String graphId, String recycleVersion) {
        return transactionTemplate.execute(status -> {
            GraphRecycleSnapshot snapshot = loadRecycle(graphId, recycleVersion);
            if (snapshot == null) {
                throw new IllegalArgumentException(
                        "回收行不存在: " + graphId + " / " + recycleVersion);
            }
            GraphRecycleSupport.validateSnapshotForRestore(snapshot);
            if (existsInFormalStore(graphId)) {
                log.warn("恢复冲突：正式库已有同名图, graphId={}, recycleVersion={}", graphId, recycleVersion);
                throw new IllegalStateException(GraphRecycleMessages.RESTORE_CONFLICT);
            }
            insertDefinitionsFromSnapshot(snapshot);
            if (snapshot.enabledVersion() != null && !snapshot.enabledVersion().isBlank()) {
                upsertEnabled(graphId, snapshot.enabledVersion());
                log.info("恢复后写回启用指针, graphId={}, enabledVersion={}",
                        graphId, snapshot.enabledVersion());
            } else {
                log.info("恢复后无启用指针（删除前未发布）, graphId={}", graphId);
            }
            int purged = jdbcTemplate.update(
                    "DELETE FROM " + recycleTable() + " WHERE graph_id = ? AND recycle_version = ?",
                    graphId, recycleVersion);
            log.info("从回收箱恢复完成, graphId={}, recycleVersion={}, recycleDeleted={}",
                    graphId, recycleVersion, purged);
            return snapshot;
        });
    }

    @Override
    public boolean purgeRecycle(String graphId, String recycleVersion) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM " + recycleTable() + " WHERE graph_id = ? AND recycle_version = ?",
                graphId, recycleVersion);
        log.info("彻底清除回收行, graphId={}, recycleVersion={}, deleted={}",
                graphId, recycleVersion, deleted);
        return deleted > 0;
    }

    @Override
    public boolean existsInFormalStore(String graphId) {
        Integer defCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + defTable() + " WHERE graph_id = ?",
                Integer.class, graphId);
        if (defCount != null && defCount > 0) {
            return true;
        }
        Integer enabledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + enabledTable() + " WHERE graph_id = ?",
                Integer.class, graphId);
        return enabledCount != null && enabledCount > 0;
    }

    /**
     * 插入回收行；唯一键冲突则换 4 位随机串重试。
     */
    private String insertRecycleRowWithRetry(GraphRecycleSnapshot snapshot) {
        DuplicateKeyException lastConflict = null;
        for (int attempt = 1; attempt <= RECYCLE_VERSION_RETRY; attempt++) {
            String recycleVersion = RecycleVersionGenerator.generate();
            try {
                String json = objectMapper.writeValueAsString(snapshot);
                jdbcTemplate.update(
                        "INSERT INTO " + recycleTable()
                                + " (graph_id, recycle_version, display_name, snapshot_json, deleted_at)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        snapshot.graphId(), recycleVersion, snapshot.displayName(),
                        json, Timestamp.from(Instant.now()));
                return recycleVersion;
            } catch (DuplicateKeyException e) {
                lastConflict = e;
                log.warn("recycle_version 唯一键冲突，重试, graphId={}, attempt={}",
                        snapshot.graphId(), attempt);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("回收快照序列化失败", e);
            }
        }
        throw new IllegalStateException(
                "无法生成唯一 recycle_version, graphId=" + snapshot.graphId(), lastConflict);
    }

    private void insertDefinitionsFromSnapshot(GraphRecycleSnapshot snapshot) {
        List<GraphDefinition> definitions = snapshot.definitions();
        if (definitions == null) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        for (GraphDefinition def : definitions) {
            try {
                GraphDefinition toSave = AgentSecretMasking.mask(def);
                String json = objectMapper.writeValueAsString(toSave);
                jdbcTemplate.update(
                        "INSERT INTO " + defTable()
                                + " (graph_id, version, display_name, description, content_json, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        def.graphId(), def.version(), def.displayName(), def.description(), json, now);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("GraphDefinition 序列化失败", e);
            }
        }
        log.info("按快照插入定义行完成, graphId={}, count={}",
                snapshot.graphId(), definitions.size());
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

    private RowMapper<GraphRecycleSnapshot> recycleSnapshotRowMapper() {
        return (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString("snapshot_json"), GraphRecycleSnapshot.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("回收快照反序列化失败", e);
            }
        };
    }

    private static TransactionTemplate buildTransactionTemplate(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalStateException("JdbcTemplate 未绑定 DataSource，无法开启回收箱事务");
        }
        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
        return new TransactionTemplate(tm);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "ace_graph_dsl_";
        }
        return prefix.endsWith("_") ? prefix : prefix + "_";
    }
}
