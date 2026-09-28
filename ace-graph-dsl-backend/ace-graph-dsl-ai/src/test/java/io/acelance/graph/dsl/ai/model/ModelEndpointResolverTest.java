package io.acelance.graph.dsl.ai.model;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.runtime.ModelOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEndpointResolverTest {

    @Test
    void inlineCompleteWinsWhenModelKeyNotEnabled() {
        ModelEndpointResolver resolver = new ModelEndpointResolver(null, null);
        LlmRequestContext ctx = ctx(ResourceBinding.disabledAll());
        InlineModel inline = new InlineModel("http://x", "sk", false, "m1");
        ModelEndpoint ep = resolver.resolve(ctx, inline, null);
        assertEquals("http://x", ep.baseUrl());
        assertEquals("m1", ep.modelId());
        assertEquals("sk", ep.apiKey());
    }

    @Test
    void overridePatchesOnInlineBase() {
        ModelEndpointResolver resolver = new ModelEndpointResolver(null, null);
        LlmRequestContext ctx = ctx(ResourceBinding.disabledAll());
        InlineModel inline = new InlineModel("http://x", "sk", false, "m1");
        ModelEndpoint ep = resolver.resolve(ctx, inline, new ModelOverride("gpt-4o", null, null));
        assertEquals("gpt-4o", ep.modelId());
        assertEquals("http://x", ep.baseUrl());
    }

    @Test
    void keyPathDoesNotFallBackToInlineOnIncomplete() {
        ModelEndpointResolver resolver = new ModelEndpointResolver(
                (c, key) -> new ModelEndpoint("http://cfg", "k", null), null);
        ResourceBinding b = new ResourceBinding(
                false, java.util.List.of(),
                true, "mdl-1",
                false, java.util.List.of(),
                false, java.util.List.of(), java.util.Map.of(),
                false, java.util.List.of());
        LlmRequestContext ctx = ctx(b);
        InlineModel inline = new InlineModel("http://inline", "sk", false, "inline-model");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(ctx, inline, null));
        assertTrue(ex.getMessage().contains("不完整"));
    }

    @Test
    void keyPathUsesMountResolver() {
        ModelEndpointResolver resolver = new ModelEndpointResolver(
                (c, key) -> new ModelEndpoint("http://cfg", "cfg-key", "cfg-model"), null);
        ResourceBinding b = new ResourceBinding(
                false, java.util.List.of(),
                true, "mdl-1",
                false, java.util.List.of(),
                false, java.util.List.of(), java.util.Map.of(),
                false, java.util.List.of());
        ModelEndpoint ep = resolver.resolve(ctx(b),
                new InlineModel("http://inline", "sk", false, "inline-model"), null);
        assertEquals("cfg-model", ep.modelId());
        assertEquals("http://cfg", ep.baseUrl());
    }

    private static LlmRequestContext ctx(ResourceBinding b) {
        return new LlmRequestContext("agent", "g1", "n1", "run", null, b);
    }
}
