package io.acelance.graph.dsl.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CachingChatModelFactoryTest {

    @Test
    void cachesByBaseUrlAndModelId() {
        AtomicInteger creates = new AtomicInteger();
        ChatModelFactory delegate = ep -> {
            creates.incrementAndGet();
            return new StubChatModelFactory().create(ep);
        };
        CachingChatModelFactory cache = new CachingChatModelFactory(delegate, 8);

        ModelEndpoint a = new ModelEndpoint("http://x", "k1", "m1");
        ModelEndpoint a2 = new ModelEndpoint("http://x", "k2", "m1"); // 不同 key，同缓存键
        ChatModel m1 = cache.create(a);
        ChatModel m2 = cache.create(a2);
        assertSame(m1, m2);
        assertEquals(1, creates.get());

        ChatModel m3 = cache.create(new ModelEndpoint("http://y", "k1", "m1"));
        assertEquals(2, creates.get());
        m3.call(new Prompt("hi"));
    }
}
