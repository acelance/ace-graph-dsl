package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.CompileConfigDto;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.NodeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftSaveValidatorTest {

    private static GraphDefinition def(String graphId, String version, String nodeId) {
        return new GraphDefinition(
                graphId,
                "demo",
                version,
                "",
                Map.of(),
                List.of(new NodeRef(nodeId, Map.of())),
                List.of(),
                CompileConfigDto.defaultConfig());
    }

    @Test
    void unchangedWhenSameAsBaseVersion() {
        GraphDefinition base = def("g1", "1.0.0", "a");
        assertTrue(DraftSaveValidator.unchanged(def("g1", "1.0.0", "a"), "1.0.0", v -> base));
    }

    @Test
    void notUnchangedWhenBaseMissing() {
        assertFalse(DraftSaveValidator.unchanged(def("g1", "1.0.0", "a"), "1.0.0", v -> null));
    }

    @Test
    void rejectOverwriteExistingVersion() {
        GraphDefinition base = def("g1", "1.0.0", "a");
        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> DraftSaveValidator.requireInsertableVersion(
                        def("g1", "1.0.0", "b"),
                        "1.0.0",
                        v -> "1.0.0".equals(v) ? base : null,
                        List.of("1.0.0")));
        assertEquals(VersionConflictException.CODE_VERSION_EXISTS, ex.code());
    }

    @Test
    void requireVersionGreaterThanMax() {
        GraphDefinition base = def("g1", "1.0.1", "a");
        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> DraftSaveValidator.requireInsertableVersion(
                        def("g1", "1.0.0", "c"),
                        "1.0.1",
                        v -> "1.0.1".equals(v) ? base : null,
                        List.of("1.0.0", "1.0.1")));
        assertEquals(VersionConflictException.CODE_VERSION_NOT_INCREASED, ex.code());
    }
}
