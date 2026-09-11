package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalToolResolver} / {@link EmptyLocalToolResolver} / {@link LocalToolCallbacks} 单测。
 */
class LocalToolResolverTest {

    @Test
    void emptyResolverReturnsEmptyAndWarnsPath() {
        LlmRequestContext ctx = ctx();
        List<NamedToolCallback> out = EmptyLocalToolResolver.INSTANCE.resolve(ctx, List.of("a", "b"));
        assertTrue(out.isEmpty());
    }

    @Test
    void mapBackedResolverResolvesByKey() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, NamedToolCallback> store = new LinkedHashMap<>();
        store.put("echo", LocalToolCallbacks.of("echo", "echo", "echo tool",
                in -> {
                    calls.incrementAndGet();
                    return "ECHO:" + in;
                }));
        store.put("ping", LocalToolCallbacks.of("ping", "ping", "ping tool", in -> "PONG"));

        LocalToolResolver resolver = (c, keys) -> keys.stream()
                .filter(store::containsKey)
                .map(store::get)
                .toList();

        List<NamedToolCallback> got = resolver.resolve(ctx(), List.of("echo", "missing", "ping"));
        assertEquals(2, got.size());
        assertEquals("echo", got.get(0).serverKey());
        assertEquals(ToolSource.LOCAL, got.get(0).source());
        assertTrue(got.get(0).uniqueName().startsWith("local__"));
        assertEquals("ECHO:{}", got.get(0).delegate().call("{}"));
        assertEquals(1, calls.get());
    }

    @Test
    void localToolCallbacksSetsServerKeyForMissDiagnostics() {
        NamedToolCallback t = LocalToolCallbacks.of("crm.get_order", "get_order", "d", in -> "ok");
        assertEquals("crm.get_order", t.serverKey());
        assertEquals("get_order", t.originalName());
        assertEquals(ToolSource.LOCAL, t.source());
    }

    private static LlmRequestContext ctx() {
        return new LlmRequestContext("agent", "g1", "n1", "run", null, ResourceBinding.disabledAll());
    }
}
