package io.acelance.graph.dsl.media;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaRefsAndSafetyTest {

    @Test
    void normalize_acceptsListMapAndString() {
        List<MediaRef> fromList = MediaRefs.normalize(List.of(
                Map.of("url", "https://cdn.example.com/a.png", "mime", "image/png"),
                "https://cdn.example.com/b.jpg"
        ), "k");
        assertEquals(2, fromList.size());
        assertEquals("image/png", fromList.get(0).mime());
        assertTrue(fromList.get(1).hasUrl());

        List<MediaRef> fromSingle = MediaRefs.normalize(
                Map.of("url", "https://cdn.example.com/c.webp"), "k");
        assertEquals(1, fromSingle.size());
    }

    @Test
    void urlSafety_blocksPrivateAndFile() {
        assertTrue(MediaUrlSafety.isAllowed("https://cdn.example.com/x.png", "n1"));
        assertFalse(MediaUrlSafety.isAllowed("file:///etc/passwd", "n1"));
        assertFalse(MediaUrlSafety.isAllowed("http://127.0.0.1/a", "n1"));
        assertFalse(MediaUrlSafety.isAllowed("http://192.168.1.1/a", "n1"));
    }
}
