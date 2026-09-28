package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CachingMcpToolResolver} 缓存命中与热刷新失效测试。
 */
class CachingMcpToolResolverTest {

    @Test
    void cachesByMcpKeyAndInvalidates() {
        AtomicInteger calls = new AtomicInteger();
        McpToolResolver delegate = (ctx, keys) -> {
            calls.incrementAndGet();
            List<NamedToolCallback> out = new ArrayList<>();
            for (String k : keys) {
                out.add(named("query", k));
            }
            return out;
        };
        CachingMcpToolResolver caching = new CachingMcpToolResolver(delegate);
        LlmRequestContext ctx = ctx();

        List<NamedToolCallback> first = caching.resolve(ctx, List.of("crm", "erp"));
        assertEquals(2, first.size());
        assertEquals(1, calls.get());
        assertEquals(2, caching.size());

        List<NamedToolCallback> second = caching.resolve(ctx, List.of("crm", "erp"));
        assertEquals(2, second.size());
        assertEquals(1, calls.get(), "整批命中不应再委派");

        caching.invalidate("crm");
        assertEquals(1, caching.size());
        List<NamedToolCallback> third = caching.resolve(ctx, List.of("crm", "erp"));
        assertEquals(2, third.size());
        assertEquals(2, calls.get(), "仅 crm miss 时应再委派一次");
    }

    @Test
    void invalidateAllClearsCache() {
        AtomicInteger calls = new AtomicInteger();
        McpToolResolver delegate = (ctx, keys) -> {
            calls.incrementAndGet();
            return keys.stream().map(k -> named("ping", k)).toList();
        };
        CachingMcpToolResolver caching = new CachingMcpToolResolver(delegate);
        LlmRequestContext ctx = ctx();

        caching.resolve(ctx, List.of("a"));
        assertEquals(1, caching.size());
        caching.invalidateAll();
        assertEquals(0, caching.size());
        caching.resolve(ctx, List.of("a"));
        assertEquals(2, calls.get());
    }

    @Test
    void ttlExpiresEntry() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        McpToolResolver delegate = (ctx, keys) -> {
            calls.incrementAndGet();
            return keys.stream().map(k -> named("t", k)).toList();
        };
        CachingMcpToolResolver caching = new CachingMcpToolResolver(delegate, 8, 30L);
        LlmRequestContext ctx = ctx();
        caching.resolve(ctx, List.of("s1"));
        assertEquals(1, calls.get());
        Thread.sleep(50L);
        caching.resolve(ctx, List.of("s1"));
        assertEquals(2, calls.get(), "TTL 过期应重新加载");
    }

    @Test
    void emptyResolverReturnsEmptyWhenKeysRequested() {
        List<NamedToolCallback> tools = EmptyMcpToolResolver.INSTANCE.resolve(ctx(), List.of("weather", "missing"));
        assertEquals(0, tools.size());
    }

    private static LlmRequestContext ctx() {
        return new LlmRequestContext("agent", "g1", "n1", "run", null, ResourceBinding.disabledAll());
    }

    private static NamedToolCallback named(String original, String serverKey) {
        String unique = ToolNames.toModelName(ToolSource.MCP, serverKey, original);
        ToolCallback cb = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(original)
                        .description("d")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
        return new NamedToolCallback(unique, original, serverKey, ToolSource.MCP, "d", cb);
    }
}
