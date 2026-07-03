package io.acelance.graph.dsl.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ace.graph.dsl")
public class AceGraphDslProperties {

    /** 是否启用 Ace Graph DSL 自动配置 */
    private boolean enabled = true;

    private final Web web = new Web();
    private final Checkpoint checkpoint = new Checkpoint();
    private final Persistence persistence = new Persistence();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Script script = new Script();
    private final DynamicNodes dynamicNodes = new DynamicNodes();
    private final AccessControl accessControl = new AccessControl();
    private final Observability observability = new Observability();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Web getWeb() {
        return web;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Script getScript() {
        return script;
    }

    public DynamicNodes getDynamicNodes() {
        return dynamicNodes;
    }

    public AccessControl getAccessControl() {
        return accessControl;
    }

    public Observability getObservability() {
        return observability;
    }

    public static class Observability {
        /** 是否启用内置 SLF4J 节点耗时监听器（默认关闭，避免日志噪声；宿主可注册自定义监听器） */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Web {
        /**
         * 是否暴露设计器 REST API（Controller 层）。
         * 设为 false 时仅保留运行时能力，可用于「内嵌运行时、外置设计器」部署形态。
         */
        private boolean enabled = true;

        /**
         * 设计器 REST API 的统一基础路径前缀，默认 {@code /api/graph}。
         * 宿主可自定义以避免与既有路由冲突，例如 {@code /platform/graph-dsl}。
         */
        private String basePath = "/api/graph";

        private final Cors cors = new Cors();
        private final Execution execution = new Execution();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public Cors getCors() {
            return cors;
        }

        public Execution getExecution() {
            return execution;
        }
    }

    public static class Execution {
        /** 是否暴露通用图执行端点（invoke / stream / resume），默认关闭 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Cors {
        /** 是否对设计器 API 启用内置 CORS（默认关闭，避免与宿主全局 CORS 冲突） */
        private boolean enabled = false;
        /** 允许的来源；默认 {@code *}。配合 allow-credentials=true 时请改为具体来源 */
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
        private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
        private List<String> exposedHeaders = new ArrayList<>();
        private boolean allowCredentials = false;
        private long maxAgeSeconds = 1800;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public List<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public List<String> getExposedHeaders() {
            return exposedHeaders;
        }

        public void setExposedHeaders(List<String> exposedHeaders) {
            this.exposedHeaders = exposedHeaders;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public long getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(long maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    public static class Checkpoint {
        /**
         * 当 {@code compile.saver} 指定的类型未注册时，是否告警并回退到 memory。
         * 设为 false 时将抛出异常，强制要求显式提供对应 CheckpointSaverProvider。
         */
        private boolean fallbackToMemory = true;
        /** 是否启用内置 Redis（Redisson）checkpoint saver（需 classpath 存在 RedissonClient 且容器中有该 Bean） */
        private boolean redisEnabled = true;

        public boolean isFallbackToMemory() {
            return fallbackToMemory;
        }

        public void setFallbackToMemory(boolean fallbackToMemory) {
            this.fallbackToMemory = fallbackToMemory;
        }

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }
    }

    public static class Persistence {
        /** auto | sqlite | redis | jdbc */
        private String type = "auto";
        /** type=auto 且 Redis 可用时是否优先 Redis */
        private boolean preferRedis = true;
        private final Sqlite sqlite = new Sqlite();
        private final Redis redis = new Redis();
        private final Jdbc jdbc = new Jdbc();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isPreferRedis() {
            return preferRedis;
        }

        public void setPreferRedis(boolean preferRedis) {
            this.preferRedis = preferRedis;
        }

        public Sqlite getSqlite() {
            return sqlite;
        }

        public Redis getRedis() {
            return redis;
        }

        public Jdbc getJdbc() {
            return jdbc;
        }
    }

    public static class Sqlite {
        /** SQLite 文件路径，默认用户目录下 */
        private String path = System.getProperty("user.home") + "/.ace-graph-dsl/graph-dsl.db";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Redis {
        private String keyPrefix = "ace-graph:dsl";

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public static class Jdbc {
        private String tablePrefix = "ace_graph_dsl_";

        public String getTablePrefix() {
            return tablePrefix;
        }

        public void setTablePrefix(String tablePrefix) {
            this.tablePrefix = tablePrefix;
        }
    }

    public static class Bootstrap {
        private boolean autoLoad = false;
        private List<String> goldenDefinitions = new ArrayList<>();

        public boolean isAutoLoad() {
            return autoLoad;
        }

        public void setAutoLoad(boolean autoLoad) {
            this.autoLoad = autoLoad;
        }

        public List<String> getGoldenDefinitions() {
            return goldenDefinitions;
        }

        public void setGoldenDefinitions(List<String> goldenDefinitions) {
            this.goldenDefinitions = goldenDefinitions;
        }
    }

    public static class Script {
        private boolean enabled = true;
        /** 默认脚本引擎（当 DSL 未指定 engine 时的回退值，如 aviator / spel） */
        private String defaultEngine = "aviator";
        /** 是否启用 Aviator 脚本引擎 */
        private boolean aviatorEnabled = true;
        /** 是否启用 SpEL 脚本引擎（Spring 内置，零额外依赖） */
        private boolean spelEnabled = true;
        private long executionTimeoutMs = 500;
        private int maxScriptSizeBytes = 65536;
        /** 脚本执行线程池最大线程数；<=0 时按 CPU 核数自动取值（下限 2） */
        private int executionPoolSize = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultEngine() {
            return defaultEngine;
        }

        public void setDefaultEngine(String defaultEngine) {
            this.defaultEngine = defaultEngine;
        }

        public boolean isAviatorEnabled() {
            return aviatorEnabled;
        }

        public void setAviatorEnabled(boolean aviatorEnabled) {
            this.aviatorEnabled = aviatorEnabled;
        }

        public boolean isSpelEnabled() {
            return spelEnabled;
        }

        public void setSpelEnabled(boolean spelEnabled) {
            this.spelEnabled = spelEnabled;
        }

        public long getExecutionTimeoutMs() {
            return executionTimeoutMs;
        }

        public void setExecutionTimeoutMs(long executionTimeoutMs) {
            this.executionTimeoutMs = executionTimeoutMs;
        }

        public int getMaxScriptSizeBytes() {
            return maxScriptSizeBytes;
        }

        public void setMaxScriptSizeBytes(int maxScriptSizeBytes) {
            this.maxScriptSizeBytes = maxScriptSizeBytes;
        }

        public int getExecutionPoolSize() {
            return executionPoolSize;
        }

        public void setExecutionPoolSize(int executionPoolSize) {
            this.executionPoolSize = executionPoolSize;
        }
    }

    public static class DynamicNodes {
        private boolean enabled = true;
        private boolean autoReloadOnStartup = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoReloadOnStartup() {
            return autoReloadOnStartup;
        }

        public void setAutoReloadOnStartup(boolean autoReloadOnStartup) {
            this.autoReloadOnStartup = autoReloadOnStartup;
        }
    }

    public static class AccessControl {
        private boolean enabled = true;
        /** 是否启用请求级菜单权限缓存（同一请求内复用权限判定结果，降低外部权限 RPC 调用） */
        private boolean cacheEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }
    }
}
