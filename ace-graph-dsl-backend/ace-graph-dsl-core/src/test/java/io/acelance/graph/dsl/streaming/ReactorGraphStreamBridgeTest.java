package io.acelance.graph.dsl.streaming;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorGraphStreamBridgeTest {

    /**
     * 真实场景：控制器先 {@code register(threadId)} 创建 sink，图执行后节点才 emit 片段，
     * 而 HTTP SSE 订阅通常在 {@code register} 之后、节点 emit 的任意时刻发生。
     * 因此桥接需缓冲「register 之后、订阅之前」的片段，订阅后回放。
     */
    @Test
    void buffersTokensEmittedBeforeSubscription() throws InterruptedException {
        ReactorGraphStreamBridge bridge = new ReactorGraphStreamBridge();
        Flux<TokenChunk> flux = bridge.register("r1"); // 控制器先 register，创建 sink
        // 订阅前 emit：unicast().onBackpressureBuffer() 会缓冲，订阅后回放
        bridge.emit("r1", new TokenChunk("n1", "a", OutputType.AGENT_MODEL_STREAMING, false));

        List<TokenChunk> collected = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        flux.subscribe(collected::add, e -> done.countDown(), done::countDown);

        // 订阅后 emit：实时投递
        bridge.emit("r1", new TokenChunk("n1", "b", OutputType.AGENT_MODEL_STREAMING, false));
        bridge.complete("r1");
        assertTrue(done.await(2, TimeUnit.SECONDS), "桥接 flux 应在 complete 后结束");
        assertEquals(2, collected.size(), "订阅前缓冲 + 订阅后实时片段都应送达");
        assertEquals("a", collected.get(0).token());
        assertEquals("b", collected.get(1).token());
    }

    @Test
    void completeWithoutEmitReturnsEmptyFlux() throws InterruptedException {
        ReactorGraphStreamBridge bridge = new ReactorGraphStreamBridge();
        Flux<TokenChunk> flux = bridge.register("r2");
        List<TokenChunk> collected = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        flux.subscribe(collected::add, e -> done.countDown(), done::countDown);
        bridge.complete("r2");
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(0, collected.size());
    }

    @Test
    void emitAndCompleteUnknownRunIdAreNoOps() {
        ReactorGraphStreamBridge bridge = new ReactorGraphStreamBridge();
        assertDoesNotThrow(() -> bridge.emit("ghost", new TokenChunk("n", "x", OutputType.AGENT_MODEL_STREAMING, false)));
        assertDoesNotThrow(() -> bridge.complete("ghost"));
    }
}
