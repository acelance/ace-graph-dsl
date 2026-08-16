package io.acelance.graph.dsl.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LangfuseClient} 行为验证：批量入队 / 定时 flush / maxBatchSize 截断 /
 * close 后 ingest 无效 / close 触发末次 flush。用内存 {@link LangfuseHttpSender} 桩捕获 JSON，
 * 验证其形态为 {@code {"batch":[...]}} 且事件数量正确。
 */
class LangfuseClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private LangfuseClient client;

    /** 记录最近一次 send 的 JSON，以及累计 send 次数（验证调度器/close 是否重复 flush）。 */
    static class CapturingSender implements LangfuseHttpSender {
        final List<String> payloads = new ArrayList<>();
        final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public void send(String json) {
            payloads.add(json);
            sendCount.incrementAndGet();
        }
    }

    private LangfuseProperties testProps(int flushIntervalMs, int maxBatchSize) {
        LangfuseProperties p = new LangfuseProperties();
        p.setBaseUrl("http://localhost:3000");
        p.setFlushIntervalMs(flushIntervalMs);
        p.setMaxBatchSize(maxBatchSize);
        return p;
    }

    private Map<String, Object> event(String type) {
        return Map.of("type", type, "id", java.util.UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void flushBatchesEventsIntoSingleJson() throws Exception {
        CapturingSender sender = new CapturingSender();
        client = new LangfuseClient(testProps(1_000_000, 50), sender);

        for (int i = 0; i < 3; i++) {
            client.ingest(event("span.create"));
        }
        assertEquals(3, client.pendingCount());

        client.flush();

        assertEquals(0, client.pendingCount());
        assertEquals(1, sender.sendCount.get(), "一次 flush 产生一个 HTTP 批次");
        JsonNode root = mapper.readTree(sender.payloads.get(0));
        JsonNode batch = root.get("batch");
        assertTrue(batch.isArray());
        assertEquals(3, batch.size());
        assertEquals("span.create", batch.get(0).get("type").asText());
    }

    @Test
    void flushRespectsMaxBatchSizeAndDrainsRemaining() throws Exception {
        CapturingSender sender = new CapturingSender();
        client = new LangfuseClient(testProps(1_000_000, 50), sender);

        for (int i = 0; i < 120; i++) {
            client.ingest(event("span.update"));
        }
        assertEquals(120, client.pendingCount());

        client.flush();
        assertEquals(70, client.pendingCount());
        client.flush();
        assertEquals(20, client.pendingCount());
        client.flush();
        assertEquals(0, client.pendingCount());

        assertEquals(3, sender.sendCount.get(), "120 条按 50/50/20 分三批");
        assertEquals(50, mapper.readTree(sender.payloads.get(0)).get("batch").size());
        assertEquals(50, mapper.readTree(sender.payloads.get(1)).get("batch").size());
        assertEquals(20, mapper.readTree(sender.payloads.get(2)).get("batch").size());
    }

    @Test
    void flushIsNoopWhenQueueEmpty() {
        CapturingSender sender = new CapturingSender();
        client = new LangfuseClient(testProps(1_000_000, 50), sender);
        client.flush();
        assertEquals(0, sender.sendCount.get());
    }

    @Test
    void ingestAfterCloseIsIgnored() {
        CapturingSender sender = new CapturingSender();
        client = new LangfuseClient(testProps(1_000_000, 50), sender);
        client.close();
        client.ingest(event("trace.create"));
        assertEquals(0, client.pendingCount());
        assertEquals(0, sender.sendCount.get());
    }

    @Test
    void closeTriggersFinalFlush() throws Exception {
        CapturingSender sender = new CapturingSender();
        client = new LangfuseClient(testProps(1_000_000, 50), sender);
        client.ingest(event("trace.create"));
        assertEquals(1, client.pendingCount());

        // 不手动 flush，直接 close —— close 内部应执行末次 flush
        client.close();
        client = null; // 避免 tearDown 重复 close（幂等但冗余）

        assertEquals(1, sender.sendCount.get());
        assertFalse(sender.payloads.isEmpty());
        assertEquals("trace.create",
                mapper.readTree(sender.payloads.get(0)).get("batch").get(0).get("type").asText());
    }
}
