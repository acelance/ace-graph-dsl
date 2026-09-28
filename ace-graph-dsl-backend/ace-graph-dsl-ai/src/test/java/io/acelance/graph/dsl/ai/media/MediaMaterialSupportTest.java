package io.acelance.graph.dsl.ai.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MediaMaterialSupportTest {

    @Test
    void stripMaterialNotes_keepsUserText() {
        String raw = "帮我解析考勤表\n[material] name=a.xlsx mime=unknown url=https://cdn.example.com/a.xlsx";
        assertEquals("帮我解析考勤表", MediaMaterialSupport.stripMaterialNotes(raw));
    }

    @Test
    void stripMaterialNotes_allMaterial_returnsEmpty() {
        assertEquals("", MediaMaterialSupport.stripMaterialNotes(
                "[material] name=a.xlsx mime=unknown url=https://cdn.example.com/a.xlsx"));
    }

    @Test
    void stripMaterialNotes_noMarker_unchanged() {
        assertEquals("普通问题", MediaMaterialSupport.stripMaterialNotes("普通问题"));
    }
}
