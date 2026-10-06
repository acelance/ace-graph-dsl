package io.acelance.graph.dsl.ai.memory;

import io.acelance.graph.dsl.media.MediaRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryUserPersistMetaTest {

    @Test
    void splitUrls_xlsxGoesToFiles_pngGoesToImages() {
        MemoryUserPersistMeta.SplitUrls split = MemoryUserPersistMeta.splitUrls(List.of(
                new MediaRef("https://cdn.example.com/a.png", "image/png", null, null),
                new MediaRef("https://cdn.example.com/ot.xlsx", null, null, "file"),
                new MediaRef("https://cdn.example.com/b.xlsx", null, null, null)
        ));
        assertEquals(List.of("https://cdn.example.com/a.png"), split.images());
        assertEquals(2, split.files().size());
        assertTrue(split.files().contains("https://cdn.example.com/ot.xlsx"));
        assertTrue(split.files().contains("https://cdn.example.com/b.xlsx"));
    }
}
