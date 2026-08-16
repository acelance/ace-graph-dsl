package io.acelance.graph.dsl.langfuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Langfuse ingestion 客户端：批量缓存事件 + 定时 flush，用 JDK {@link HttpClient} 直连 Langfuse
 * {@code /api/public/ingestion}（Basic Auth）。零外部依赖。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>发送失败仅记录 warn 日志、绝不抛错——图执行绝不能因观测后端异常而中断。</li>
 *   <li>进程退出（{@link #close}）时执行最后一次 flush，尽量不丢事件。</li>
 *   <li>{@link LangfuseHttpSender} 可注入，便于单测用内存桩验证事件形态。</li>
 * </ul>
 */
public class LangfuseClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LangfuseClient.class);

    private final LangfuseProperties props;
    private final LangfuseHttpSender sender;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http;
    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed = false;

    public LangfuseClient(LangfuseProperties props) {
        this(props, null);
    }

    public LangfuseClient(LangfuseProperties props, LangfuseHttpSender sender) {
        this.props = props;
        this.sender = sender != null ? sender : defaultSender();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "langfuse-flush");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(
                this::flush, props.getFlushIntervalMs(), props.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /** 入队一个 Langfuse 事件（span.create / generation.create / span.update / trace.update 等）。 */
    public void ingest(Map<String, Object> event) {
        if (closed) {
            return;
        }
        queue.offer(event);
    }

    /** 取出队首一批事件并上报（线程安全）。 */
    public void flush() {
        if (closed || queue.isEmpty()) {
            return;
        }
        List<Map<String, Object>> batch = new ArrayList<>();
        queue.drainTo(batch, props.getMaxBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of("batch", batch));
            sender.send(json);
        } catch (Exception e) {
            log.warn("Langfuse 事件上报失败（已丢弃本批 {} 条）: {}", batch.size(), e.toString());
        }
    }

    public int pendingCount() {
        return queue.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    private LangfuseHttpSender defaultSender() {
        String auth = Base64.getEncoder().encodeToString(
                (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8));
        String endpoint = props.getBaseUrl().replaceAll("/+$", "") + "/api/public/ingestion";
        return json -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + auth)
                    .timeout(Duration.ofMillis(props.getWriteTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Langfuse ingestion HTTP {}: {}", response.statusCode(), response.body());
            }
        };
    }
}
