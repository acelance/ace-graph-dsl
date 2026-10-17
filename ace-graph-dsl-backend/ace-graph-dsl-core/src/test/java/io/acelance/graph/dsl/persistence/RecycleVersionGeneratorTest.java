package io.acelance.graph.dsl.persistence;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecycleVersionGenerator} 单测。
 */
class RecycleVersionGeneratorTest {

    private static final Pattern RECYCLE_VERSION =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}[A-Za-z0-9]{4}$");

    @Test
    void generate_matchesFormat() {
        String version = RecycleVersionGenerator.generate();
        assertTrue(RECYCLE_VERSION.matcher(version).matches(), "unexpected: " + version);
    }

    @Test
    void generate_suffixIsRandomEnough() {
        Set<String> suffixes = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            suffixes.add(RecycleVersionGenerator.suffixOf(RecycleVersionGenerator.generate()));
        }
        assertTrue(suffixes.size() > 10, "expected diverse suffixes, got " + suffixes.size());
    }

    @Test
    void suffixOf_returnsLast4() {
        assertEquals("Ab3x", RecycleVersionGenerator.suffixOf("2026-10-16 23:28:01.123Ab3x"));
    }
}
