package io.acelance.graph.dsl.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryModeTest {

    @Test
    void from_parsesKnownAndDefaultsUnknown() {
        assertEquals(MemoryMode.NONE, MemoryMode.from(null));
        assertEquals(MemoryMode.NONE, MemoryMode.from(" "));
        assertEquals(MemoryMode.READ_WRITE, MemoryMode.from("read_write"));
        assertEquals(MemoryMode.READ_ONLY, MemoryMode.from("READ_ONLY"));
        assertEquals(MemoryMode.NONE, MemoryMode.from("weird"));
    }
}
