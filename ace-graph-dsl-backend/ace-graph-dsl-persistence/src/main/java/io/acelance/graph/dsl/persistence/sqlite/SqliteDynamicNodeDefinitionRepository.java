package io.acelance.graph.dsl.persistence.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.persistence.support.AbstractJdbcDynamicNodeDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite 动态节点定义持久化。
 */
public class SqliteDynamicNodeDefinitionRepository extends AbstractJdbcDynamicNodeDefinitionRepository {

    public SqliteDynamicNodeDefinitionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, String tablePrefix) {
        super(jdbcTemplate, objectMapper, tablePrefix);
    }

    @Override
    protected void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    node_id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    content_json TEXT NOT NULL,
                    script_hash TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_by TEXT,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.formatted(nodeDefTable()));
    }
}
