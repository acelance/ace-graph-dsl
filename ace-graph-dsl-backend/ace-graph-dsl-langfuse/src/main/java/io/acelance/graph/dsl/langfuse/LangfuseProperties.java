package io.acelance.graph.dsl.langfuse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 接入配置（前缀 {@code ace.graph.dsl.langfuse}）。
 *
 * <p>默认 {@code enabled=false}：模块随 classpath 加载，但只有显式开启才真正收发 trace，
 * 未开启时不影响图执行（监听器/记录器均为空操作）。</p>
 */
@ConfigurationProperties(prefix = "ace.graph.dsl.langfuse")
public class LangfuseProperties {

    /** 是否启用 Langfuse 接入（默认关） */
    private boolean enabled = false;

    /** Langfuse 服务地址，自托管填如 http://localhost:3000，云版默认 https://cloud.langfuse.com */
    private String baseUrl = "https://cloud.langfuse.com";

    /** Langfuse public key（Basic Auth 用户名） */
    private String publicKey;

    /** Langfuse secret key（Basic Auth 密码） */
    private String secretKey;

    /** 批量上报间隔（毫秒），默认 2000 */
    private long flushIntervalMs = 2000;

    /** 单批最大事件数，默认 50 */
    private int maxBatchSize = 50;

    /** HTTP 连接超时（毫秒），默认 5000 */
    private long connectTimeoutMs = 5000;

    /** HTTP 写超时（毫秒），默认 10000 */
    private long writeTimeoutMs = 10000;

    /** trace 名称（默认 ace-graph-dsl-run） */
    private String traceName = "ace-graph-dsl-run";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getWriteTimeoutMs() {
        return writeTimeoutMs;
    }

    public void setWriteTimeoutMs(long writeTimeoutMs) {
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public String getTraceName() {
        return traceName;
    }

    public void setTraceName(String traceName) {
        this.traceName = traceName;
    }
}
