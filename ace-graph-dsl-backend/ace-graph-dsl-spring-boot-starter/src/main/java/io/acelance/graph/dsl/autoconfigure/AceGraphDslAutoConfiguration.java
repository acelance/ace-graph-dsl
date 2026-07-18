package io.acelance.graph.dsl.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.acelance.graph.dsl.audit.GraphAuditLogger;
import io.acelance.graph.dsl.audit.Slf4jGraphAuditLogger;
import io.acelance.graph.dsl.annotation.GraphAnnotationBeanDefinitionRegistryPostProcessor;
import io.acelance.graph.dsl.checkpoint.CheckpointSaverProvider;
import io.acelance.graph.dsl.checkpoint.CheckpointSaverRegistry;
import io.acelance.graph.dsl.checkpoint.MemoryCheckpointSaverProvider;
import io.acelance.graph.dsl.execution.DefaultGraphExecutionEventAdapter;
import io.acelance.graph.dsl.execution.GraphExecutionEventAdapter;
import io.acelance.graph.dsl.observability.GraphExecutionListener;
import io.acelance.graph.dsl.observability.Slf4jGraphExecutionListener;
import io.acelance.graph.dsl.persistence.DynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.PersistenceType;
import io.acelance.graph.dsl.persistence.jdbc.JdbcDynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.persistence.jdbc.JdbcGraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.memory.InMemoryDynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.persistence.memory.InMemoryGraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.redis.RedisGraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.sqlite.SqliteDynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.persistence.sqlite.SqliteGraphDefinitionRepository;
import io.acelance.graph.dsl.script.AviatorScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.SpelScriptEngine;
import io.acelance.graph.dsl.security.GraphNodeAccessControl;
import io.acelance.graph.dsl.security.PermissiveGraphNodeAccessControl;
import io.acelance.graph.dsl.security.menu.DefaultGraphMenuCatalog;
import io.acelance.graph.dsl.security.menu.GraphMenuAccessControl;
import io.acelance.graph.dsl.security.menu.GraphMenuCatalog;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.PermissiveGraphMenuAccessControl;
import io.acelance.graph.dsl.service.ScriptNodeService;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@AutoConfiguration
@EnableConfigurationProperties(AceGraphDslProperties.class)
@ConditionalOnProperty(prefix = "ace.graph.dsl", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = {
        "io.acelance.graph.dsl.autoconfigure",
        "io.acelance.graph.dsl.builder",
        "io.acelance.graph.dsl.registry",
        "io.acelance.graph.dsl.script",
        "io.acelance.graph.dsl.service",
        "io.acelance.graph.dsl.store"
})
public class AceGraphDslAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslAutoConfiguration.class);

    /**
     * 库内部专用 ObjectMapper。
     *
     * <p>始终以限定名 {@code aceGraphDslObjectMapper} 注册，库内部组件通过
     * {@code @Qualifier(AceGraphDslBeans.OBJECT_MAPPER)} 显式注入，避免覆盖或被宿主全局
     * {@code ObjectMapper} 覆盖，从而隔离 JSON 序列化行为。</p>
     */
    @Bean(name = AceGraphDslBeans.OBJECT_MAPPER)
    @ConditionalOnMissingBean(name = AceGraphDslBeans.OBJECT_MAPPER)
    public ObjectMapper aceGraphDslObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnMissingBean(GraphAnnotationBeanDefinitionRegistryPostProcessor.class)
    public GraphAnnotationBeanDefinitionRegistryPostProcessor graphAnnotationRegistrar() {
        return new GraphAnnotationBeanDefinitionRegistryPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean(GraphAuditLogger.class)
    public GraphAuditLogger graphAuditLogger() {
        return new Slf4jGraphAuditLogger();
    }

    @Bean
    @ConditionalOnMissingBean(GraphExecutionEventAdapter.class)
    public GraphExecutionEventAdapter graphExecutionEventAdapter() {
        return new DefaultGraphExecutionEventAdapter();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ace.graph.dsl.observability", name = "enabled", havingValue = "true")
    public GraphExecutionListener slf4jGraphExecutionListener() {
        return new Slf4jGraphExecutionListener();
    }

    @Bean
    public MemoryCheckpointSaverProvider memoryCheckpointSaverProvider() {
        return new MemoryCheckpointSaverProvider();
    }

    @Bean
    @ConditionalOnMissingBean(CheckpointSaverRegistry.class)
    public CheckpointSaverRegistry checkpointSaverRegistry(
            ObjectProvider<CheckpointSaverProvider> providers,
            AceGraphDslProperties properties) {
        return new CheckpointSaverRegistry(
                providers.orderedStream().toList(),
                properties.getCheckpoint().isFallbackToMemory());
    }

    @Bean
    @ConditionalOnMissingBean(GraphNodeAccessControl.class)
    public GraphNodeAccessControl graphNodeAccessControl() {
        return new PermissiveGraphNodeAccessControl();
    }

    @Bean(name = AceGraphDslBeans.GRAPH_MENU_ACCESS_CONTROL_DELEGATE)
    @ConditionalOnMissingBean(name = AceGraphDslBeans.GRAPH_MENU_ACCESS_CONTROL_DELEGATE)
    public GraphMenuAccessControl graphMenuAccessControlDelegate() {
        return new PermissiveGraphMenuAccessControl();
    }

    @Bean
    @ConditionalOnMissingBean(GraphMenuCatalog.class)
    public GraphMenuCatalog graphMenuCatalog() {
        return new DefaultGraphMenuCatalog();
    }

    @Bean
    @ConditionalOnMissingBean(GraphMenuPermissionResolver.class)
    public GraphMenuPermissionResolver graphMenuPermissionResolver(
            GraphMenuCatalog graphMenuCatalog,
            GraphMenuAccessControl graphMenuAccessControl) {
        return new GraphMenuPermissionResolver(graphMenuCatalog, graphMenuAccessControl);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "aviator-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScriptEngine aviatorScriptEngine(AceGraphDslProperties properties) {
        return new AviatorScriptEngine(
                properties.getScript().getExecutionTimeoutMs(),
                properties.getScript().getExecutionPoolSize());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "spel-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScriptEngine spelScriptEngine() {
        return new SpelScriptEngine();
    }

    @Bean
    @ConditionalOnMissingBean(ScriptNodeService.class)
    public ScriptNodeService scriptNodeService(
            DynamicNodeDefinitionRepository dynamicNodeDefinitionRepository,
            io.acelance.graph.dsl.registry.GraphNodeRegistry nodeRegistry,
            io.acelance.graph.dsl.script.ScriptNodeFactory scriptNodeFactory,
            io.acelance.graph.dsl.script.ScriptEngineRegistry engineRegistry,
            GraphAuditLogger auditLogger,
            AceGraphDslProperties properties) {
        return new ScriptNodeService(
                dynamicNodeDefinitionRepository,
                nodeRegistry,
                scriptNodeFactory,
                engineRegistry,
                properties.getScript().getMaxScriptSizeBytes(),
                properties.getScript().getDefaultEngine(),
                auditLogger);
    }

    @Bean
    @ConditionalOnMissingBean(DynamicNodeDefinitionRepository.class)
    public DynamicNodeDefinitionRepository dynamicNodeDefinitionRepository(
            AceGraphDslProperties properties,
            @Qualifier(AceGraphDslBeans.OBJECT_MAPPER) ObjectMapper objectMapper,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<DataSourceProperties> dataSourcePropertiesProvider,
            ObjectProvider<DataSource> aceGraphDslSqliteDataSourceProvider) {

        if (!properties.getDynamicNodes().isEnabled()) {
            return new InMemoryDynamicNodeDefinitionRepository();
        }

        PersistenceType type = resolvePersistenceType(
                properties, null, dataSourcePropertiesProvider);
        String tablePrefix = properties.getPersistence().getJdbc().getTablePrefix();

        return switch (type) {
            case JDBC -> {
                DataSource ds = dataSourceProvider.getIfAvailable();
                if (ds == null) {
                    throw new IllegalStateException("动态节点持久化需要 DataSource");
                }
                yield new JdbcDynamicNodeDefinitionRepository(new JdbcTemplate(ds), objectMapper, tablePrefix);
            }
            case SQLITE -> new SqliteDynamicNodeDefinitionRepository(
                    new JdbcTemplate(aceGraphDslSqliteDataSourceProvider.getObject()),
                    objectMapper,
                    tablePrefix);
            default -> new InMemoryDynamicNodeDefinitionRepository();
        };
    }

    @Bean(name = "aceGraphDslSqliteDataSource")
    @ConditionalOnMissingBean(name = "aceGraphDslSqliteDataSource")
    @ConditionalOnExpression("'${ace.graph.dsl.persistence.type:auto}'.equals('auto')"
            + " || '${ace.graph.dsl.persistence.type:auto}'.equals('sqlite')")
    public DataSource aceGraphDslSqliteDataSource(AceGraphDslProperties properties) throws Exception {
        Path dbPath = Path.of(properties.getPersistence().getSqlite().getPath());
        Files.createDirectories(dbPath.getParent() != null ? dbPath.getParent() : Path.of("."));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(1);
        return ds;
    }

    @Bean
    @ConditionalOnMissingBean(GraphDefinitionRepository.class)
    public GraphDefinitionRepository graphDefinitionRepository(
            AceGraphDslProperties properties,
            @Qualifier(AceGraphDslBeans.OBJECT_MAPPER) ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<DataSourceProperties> dataSourcePropertiesProvider,
            ObjectProvider<DataSource> aceGraphDslSqliteDataSourceProvider) {

        PersistenceType type = resolvePersistenceType(properties, redisConnectionFactoryProvider, dataSourcePropertiesProvider);
        log.info("Ace Graph DSL 持久化后端: {}", type);

        return switch (type) {
            case REDIS -> {
                StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
                if (redis == null) {
                    redis = new StringRedisTemplate(redisConnectionFactoryProvider.getObject());
                    redis.afterPropertiesSet();
                }
                yield new RedisGraphDefinitionRepository(redis, objectMapper, properties.getPersistence().getRedis().getKeyPrefix());
            }
            case JDBC -> {
                DataSource ds = dataSourceProvider.getIfAvailable();
                if (ds == null) {
                    throw new IllegalStateException("ace.graph.dsl.persistence.type=jdbc 但未配置 DataSource");
                }
                yield new JdbcGraphDefinitionRepository(new JdbcTemplate(ds), objectMapper, properties.getPersistence().getJdbc().getTablePrefix());
            }
            case SQLITE -> new SqliteGraphDefinitionRepository(
                    new JdbcTemplate(aceGraphDslSqliteDataSourceProvider.getObject()),
                    objectMapper,
                    properties.getPersistence().getJdbc().getTablePrefix());
            default -> new InMemoryGraphDefinitionRepository();
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "ace.graph.dsl.bootstrap", name = "auto-load", havingValue = "true")
    public GraphDslBootstrapLoader graphDslBootstrapLoader(
            GraphDefinitionRepository repository,
            io.acelance.graph.dsl.store.GraphRuntime runtime,
            @Qualifier(AceGraphDslBeans.OBJECT_MAPPER) ObjectMapper objectMapper,
            AceGraphDslProperties properties,
            ResourceLoader resourceLoader) {
        return new GraphDslBootstrapLoader(repository, runtime, objectMapper, properties, resourceLoader);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ace.graph.dsl.dynamic-nodes", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DynamicNodeBootstrapLoader dynamicNodeBootstrapLoader(
            ScriptNodeService scriptNodeService,
            AceGraphDslProperties properties) {
        return new DynamicNodeBootstrapLoader(scriptNodeService, properties);
    }

    private PersistenceType resolvePersistenceType(
            AceGraphDslProperties properties,
            ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider,
            ObjectProvider<DataSourceProperties> dataSourcePropertiesProvider) {

        String configured = properties.getPersistence().getType();
        PersistenceType explicit = switch (configured.toLowerCase()) {
            case "sqlite" -> PersistenceType.SQLITE;
            case "redis" -> PersistenceType.REDIS;
            case "jdbc" -> PersistenceType.JDBC;
            default -> PersistenceType.AUTO;
        };
        if (explicit != PersistenceType.AUTO) {
            return explicit;
        }
        if (properties.getPersistence().isPreferRedis() && redisConnectionFactoryProvider.getIfAvailable() != null) {
            return PersistenceType.REDIS;
        }
        DataSourceProperties dsProps = dataSourcePropertiesProvider.getIfAvailable();
        if (dsProps != null && dsProps.getUrl() != null && !dsProps.getUrl().contains("sqlite")) {
            return PersistenceType.JDBC;
        }
        return PersistenceType.SQLITE;
    }

    /**
     * Redis（Redisson）checkpoint saver 自动装配。
     *
     * <p>仅当 classpath 存在 {@code org.redisson.api.RedissonClient}、容器中存在其 Bean，
     * 且 {@code ace.graph.dsl.checkpoint.redis-enabled=true}（默认）时生效。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnProperty(prefix = "ace.graph.dsl.checkpoint", name = "redis-enabled", havingValue = "true", matchIfMissing = true)
    static class RedisCheckpointConfiguration {

        @Bean
        @ConditionalOnBean(RedissonClient.class)
        public RedisCheckpointSaverProvider redisCheckpointSaverProvider(RedissonClient redissonClient) {
            return new RedisCheckpointSaverProvider(redissonClient);
        }
    }
}
