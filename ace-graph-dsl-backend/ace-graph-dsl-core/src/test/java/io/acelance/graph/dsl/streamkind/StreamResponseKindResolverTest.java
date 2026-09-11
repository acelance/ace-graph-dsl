package io.acelance.graph.dsl.streamkind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamResponseKindResolverTest {

    private final StreamResponseKindResolver resolver =
            new StreamResponseKindResolver(new DefaultStreamResponseKindCatalog());

    @Test
    void blankFallsBackToFirst() {
        assertEquals("BIZ", resolver.resolveOrDefault("g1", null).code());
        assertEquals("BIZ", resolver.resolveOrDefault("g1", "  ").code());
    }

    @Test
    void knownCodeKept() {
        assertEquals("OUTPUT", resolver.resolveOrDefault("g1", "OUTPUT").code());
    }

    @Test
    void ofRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> StreamResponseKind.of(null));
        assertThrows(IllegalArgumentException.class, () -> StreamResponseKind.of(""));
    }
}
