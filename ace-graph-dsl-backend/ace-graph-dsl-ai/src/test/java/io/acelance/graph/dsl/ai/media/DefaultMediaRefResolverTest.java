package io.acelance.graph.dsl.ai.media;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMediaRefResolverTest {

    @Test
    void resolvesExplicitMimeAndExtension() {
        DefaultMediaRefResolver resolver = new DefaultMediaRefResolver();
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "r", null,
                ResourceBinding.disabledAll());
        MediaRefResolver.ResolveResult r = resolver.resolve(ctx, List.of(
                new MediaRef("https://cdn.example.com/a.png", "image/png", null, null),
                new MediaRef("https://cdn.example.com/b.JPEG", null, null, null),
                new MediaRef("https://cdn.example.com/noext", null, null, null),
                new MediaRef(null, "image/png", null, null)
        ));
        assertEquals(2, r.medias().size());
        assertTrue(r.skippedNotes().stream().anyMatch(s -> s.contains("类型未识别")));
    }

    @Test
    void truncatesOverMaxItems() {
        DefaultMediaRefResolver resolver = new DefaultMediaRefResolver(2);
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "r", null,
                ResourceBinding.disabledAll());
        List<MediaRef> refs = List.of(
                new MediaRef("https://cdn.example.com/1.png", null, null, null),
                new MediaRef("https://cdn.example.com/2.png", null, null, null),
                new MediaRef("https://cdn.example.com/3.png", null, null, null)
        );
        assertEquals(2, resolver.resolve(ctx, refs).medias().size());
    }
}
