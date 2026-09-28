package io.acelance.graph.dsl.persistence;

/**
 * DSL 持久化后端类型。
 */
public enum PersistenceType {
    AUTO,
    /** 进程内内存仓储（本地联调 / 无外部存储） */
    MEMORY,
    SQLITE,
    REDIS,
    JDBC
}
