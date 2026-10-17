package io.acelance.graph.dsl.llm;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWriteFlagTest {

    @Test
    void fromLegacyReadWrite_allThree() {
        Set<MemoryWriteFlag> flags = MemoryWriteFlag.fromLegacyMode(MemoryMode.READ_WRITE);
        assertEquals(EnumSet.of(
                MemoryWriteFlag.WRITE_USER,
                MemoryWriteFlag.WRITE_ASSISTANT_THINKING,
                MemoryWriteFlag.WRITE_ASSISTANT_MAIN_TEXT), flags);
    }

    @Test
    void resolveFlexible_explicitBizAndOutput() {
        Set<MemoryWriteFlag> biz = MemoryWriteFlag.resolveFlexible(
                List.of("WRITE_USER", "WRITE_ASSISTANT_THINKING"), MemoryMode.NONE);
        assertEquals(EnumSet.of(
                MemoryWriteFlag.WRITE_USER, MemoryWriteFlag.WRITE_ASSISTANT_THINKING), biz);

        Set<MemoryWriteFlag> out = MemoryWriteFlag.resolveFlexible(
                List.of("WRITE_ASSISTANT_MAIN_TEXT"), MemoryMode.READ_WRITE);
        assertEquals(EnumSet.of(MemoryWriteFlag.WRITE_ASSISTANT_MAIN_TEXT), out);
    }

    @Test
    void resolveFlexible_plusJoined() {
        Set<MemoryWriteFlag> flags = MemoryWriteFlag.resolveFlexible(
                List.of("WRITE_USER+WRITE_ASSISTANT_THINKING"), null);
        assertTrue(flags.contains(MemoryWriteFlag.WRITE_USER));
        assertTrue(flags.contains(MemoryWriteFlag.WRITE_ASSISTANT_THINKING));
        assertEquals(2, flags.size());
    }

    @Test
    void resolve_nullWrites_fallsBackToMode() {
        assertTrue(MemoryWriteFlag.resolve(null, MemoryMode.NONE).isEmpty());
        assertEquals(3, MemoryWriteFlag.resolve(null, MemoryMode.READ_WRITE).size());
    }

    @Test
    void resolve_emptyList_meansNoWrites() {
        assertTrue(MemoryWriteFlag.resolve(List.of(), MemoryMode.READ_WRITE).isEmpty());
    }
}
