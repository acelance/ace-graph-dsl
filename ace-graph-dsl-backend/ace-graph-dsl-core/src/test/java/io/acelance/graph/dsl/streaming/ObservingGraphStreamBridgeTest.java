package io.acelance.graph.dsl.streaming;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservingGraphStreamBridgeTest {

    @Test
    void observersInvokedSynchronouslyOnEmit() {
        List<String> seen = new ArrayList<>();
        AtomicBoolean observerRan = new AtomicBoolean(false);

        TokenChunkObserver observer = (runId, chunk) -> {
            observerRan.set(true);
            seen.add(chunk.token());
        };
        ReactorGraphStreamBridge reactor = new ReactorGraphStreamBridge();
        GraphStreamBridge bridge = new ObservingGraphStreamBridge(reactor, List.of(observer));

        bridge.register("r1");
        bridge.emit("r1", new TokenChunk("n1", "hi", OutputType.AGENT_MODEL_STREAMING, false));

        assertTrue(observerRan.get());
        assertEquals(List.of("hi"), seen);
    }

    @Test
    void observerFailureDoesNotBlockEmit() {
        TokenChunkObserver bad = (runId, chunk) -> {
            throw new IllegalStateException("boom");
        };
        ReactorGraphStreamBridge reactor = new ReactorGraphStreamBridge();
        GraphStreamBridge bridge = new ObservingGraphStreamBridge(reactor, List.of(bad));
        bridge.register("r1");
        bridge.emit("r1", new TokenChunk("n1", "x", OutputType.AGENT_MODEL_STREAMING, false));
        bridge.complete("r1");
    }
}
