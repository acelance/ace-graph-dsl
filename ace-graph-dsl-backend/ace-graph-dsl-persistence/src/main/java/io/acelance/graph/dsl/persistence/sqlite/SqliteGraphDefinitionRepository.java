package io.acelance.graph.dsl.persistence.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.persistence.support.AbstractJdbcGraphDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite 持久化实现（无外部数据源时的默认选择）。
 */
public class SqliteGraphDefinitionRepository extends AbstractJdbcGraphDefinitionRepository {

    public SqliteGraphDefinitionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, String tablePrefix) {
        super(jdbcTemplate, objectMapper, tablePrefix);
    }

    @Override
    protected void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    graph_id TEXT NOT NULL,
                    version TEXT NOT NULL,
                    display_name TEXT,
                    description TEXT,
                    content_json TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """.formatted(defTable()));
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_%s_graph_version ON %s(graph_id, version)
                """.formatted(defTable(), defTable()));
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    graph_id TEXT PRIMARY KEY,
                    version TEXT NOT NULL,
                    enabled_at TIMESTAMP NOT NULL
                )
                """.formatted(enabledTable()));
    }
}
