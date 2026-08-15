package io.acelance.graph.dsl.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.persistence.support.AbstractJdbcGenericAgentDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC 通用 agent 节点定义持久化。
 */
public class JdbcGenericAgentDefinitionRepository extends AbstractJdbcGenericAgentDefinitionRepository {

    public JdbcGenericAgentDefinitionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, String tablePrefix) {
        super(jdbcTemplate, objectMapper, tablePrefix);
    }

    @Override
    protected void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    node_id VARCHAR(128) PRIMARY KEY,
                    display_name VARCHAR(256) NOT NULL,
                    content_json TEXT NOT NULL,
                    model_id VARCHAR(128),
                    version VARCHAR(32) NOT NULL DEFAULT '1.0.0',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_by VARCHAR(128),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(agentDefTable()));
    }
}
