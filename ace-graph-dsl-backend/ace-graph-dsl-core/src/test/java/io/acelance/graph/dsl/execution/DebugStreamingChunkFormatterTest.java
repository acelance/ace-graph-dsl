package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugStreamingChunkFormatterTest {

    @Test
    void formatsTokenWithResponseKind() {
        DebugStreamingChunkFormatter fmt = new DebugStreamingChunkFormatter();
        TokenChunk tc = new TokenChunk("n1", "hi", OutputType.AGENT_MODEL_STREAMING, "BIZ", false);
        Object payload = fmt.format(StreamingContext.ofToken(tc, "g1"));
        assertTrue(payload instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload;
        assertEquals("debug_chunk", map.get("type"));
        assertEquals("g1", map.get("graphId"));
        assertEquals("BIZ", map.get("responseKind"));
        assertEquals("hi", map.get("chunk"));
    }
}
