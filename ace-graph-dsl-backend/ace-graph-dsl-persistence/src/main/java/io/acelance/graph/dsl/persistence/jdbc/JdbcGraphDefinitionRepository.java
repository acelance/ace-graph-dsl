package io.acelance.graph.dsl.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.persistence.support.AbstractJdbcGraphDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通用 JDBC 持久化实现（MySQL / MariaDB / H2）。
 *
 * <p>DDL 使用 {@code AUTO_INCREMENT} + {@code TEXT}，兼容 MySQL 5.7+ / MariaDB 10.2+。
 * 如需 PostgreSQL / Oracle，请自行扩展并覆盖 {@link #initSchema()}。</p>
 */
public class JdbcGraphDefinitionRepository extends AbstractJdbcGraphDefinitionRepository {

    public JdbcGraphDefinitionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, String tablePrefix) {
        super(jdbcTemplate, objectMapper, tablePrefix);
    }

    @Override
    protected void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    graph_id VARCHAR(128) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    display_name VARCHAR(256),
                    description TEXT,
                    content_json TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(defTable()));
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    graph_id VARCHAR(128) PRIMARY KEY,
                    version VARCHAR(64) NOT NULL,
                    enabled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(enabledTable()));
        // snapshot_json 使用 MEDIUMTEXT，避免大图被 TEXT 64KB 截断
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    graph_id VARCHAR(128) NOT NULL,
                    recycle_version VARCHAR(64) NOT NULL,
                    display_name VARCHAR(256),
                    snapshot_json MEDIUMTEXT NOT NULL,
                    deleted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_graph_recycle (graph_id, recycle_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(recycleTable()));
    }
}
