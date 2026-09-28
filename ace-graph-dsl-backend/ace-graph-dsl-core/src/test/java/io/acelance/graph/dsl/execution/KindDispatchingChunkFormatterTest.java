package io.acelance.graph.dsl.execution;

import io.acelance.graph.dsl.streaming.TokenChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KindDispatchingChunkFormatterTest {

    @Test
    void dispatchesByResponseKind() {
        KindDispatchingChunkFormatter fmt = new KindDispatchingChunkFormatter() {
            @Override
            protected Object onBiz(StreamingContext ctx) {
                return "biz";
            }

            @Override
            protected Object onOutput(StreamingContext ctx) {
                return "out";
            }

            @Override
            protected Object onOther(StreamingContext ctx, String kind) {
                return kind == null ? "null" : kind;
            }
        };
        assertEquals("biz", fmt.format(ctxWithKind("BIZ")));
        assertEquals("out", fmt.format(ctxWithKind("output")));
        assertEquals("TOOL", fmt.format(ctxWithKind("TOOL")));
        assertEquals("null", fmt.format(StreamingContext.ofToken(
                new TokenChunk("n", "t", null, false), "g")));
    }

    private static StreamingContext ctxWithKind(String kind) {
        return StreamingContext.ofToken(new TokenChunk("n", "t", null, kind, false), "g");
    }
}
