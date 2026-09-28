package io.acelance.graph.dsl.ai.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeyListMemoryDisplayUserTextResolverTest {

    @Test
    void resolvesFirstNonBlankKey() {
        KeyListMemoryDisplayUserTextResolver r =
                new KeyListMemoryDisplayUserTextResolver("user_query", "query");
        String text = r.resolve(new MemoryDisplayUserTextResolver.MemoryDisplayUserTextRequest(
                null, Map.of("query", "q1", "user_query", "u1"), "llm-block"));
        assertEquals("u1", text);
    }

    @Test
    void returnsNullWhenNoneMatch() {
        KeyListMemoryDisplayUserTextResolver r =
                new KeyListMemoryDisplayUserTextResolver("user_query");
        assertNull(r.resolve(new MemoryDisplayUserTextResolver.MemoryDisplayUserTextRequest(
                null, Map.of("other", "x"), "llm")));
    }
}
