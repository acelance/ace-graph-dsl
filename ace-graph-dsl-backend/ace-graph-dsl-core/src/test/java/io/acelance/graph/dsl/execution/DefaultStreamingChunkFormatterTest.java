package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStreamingChunkFormatterTest {

    private final DefaultStreamingChunkFormatter formatter = new DefaultStreamingChunkFormatter();

    @Test
    void tokenMidChunkOmitsIsEnd() {
        Map<?, ?> payload = (Map<?, ?>) formatter.format(
                StreamingContext.ofToken(new TokenChunk("n1", "Hello", OutputType.AGENT_MODEL_STREAMING, false), "g1"));
        assertEquals("chunk", payload.get("type"));
        assertEquals("n1", payload.get("node"));
        assertEquals("Hello", payload.get("chunk"));
        assertFalse(payload.containsKey("isEnd"));
    }

    @Test
    void tokenLastChunkAddsIsEnd() {
        Map<?, ?> payload = (Map<?, ?>) formatter.format(
                StreamingContext.ofToken(new TokenChunk("n1", "", OutputType.AGENT_MODEL_FINISHED, true), "g1"));
        assertEquals("chunk", payload.get("type"));
        assertEquals("n1", payload.get("node"));
        assertEquals("", payload.get("chunk"));
        assertTrue(payload.containsKey("isEnd"));
        assertEquals(true, payload.get("isEnd"));
    }

    @Test
    void nullNodeIdNormalizedToEmpty() {
        Map<?, ?> payload = (Map<?, ?>) formatter.format(
                StreamingContext.ofToken(new TokenChunk(null, "x", OutputType.AGENT_MODEL_STREAMING, false), "g1"));
        assertEquals("", payload.get("node"));
    }

    @Test
    void listOfChunksStreamingTypeFlag() {
        List<TokenChunk> chunks = List.of(
                new TokenChunk("n1", "a", OutputType.AGENT_MODEL_STREAMING, false),
                new TokenChunk("n1", "b", OutputType.AGENT_MODEL_FINISHED, true));
        assertEquals(OutputType.AGENT_MODEL_STREAMING, chunks.get(0).outputType());
        assertTrue(chunks.get(1).last());
    }
}
