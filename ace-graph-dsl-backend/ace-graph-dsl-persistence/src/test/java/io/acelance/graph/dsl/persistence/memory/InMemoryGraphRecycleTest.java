package io.acelance.graph.dsl.persistence.memory;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphRecycleEntry;
import io.acelance.graph.dsl.persistence.GraphRecycleMessages;
import io.acelance.graph.dsl.persistence.GraphRecycleSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存仓储回收箱：删除 / 恢复 / 清除 / 冲突。
 */
class InMemoryGraphRecycleTest {

    @Test
    void recycle_restore_roundTrip_keepsEnabledPointer() {
        InMemoryGraphDefinitionRepository repo = new InMemoryGraphDefinitionRepository();
        GraphDefinition v1 = def("bill-check", "1.0.0", "票据");
        GraphDefinition v2 = def("bill-check", "1.0.22", "票据模型核查校验GRAPH");
        repo.saveDraft(v1);
        repo.saveDraft(v2);
        repo.markEnabled("bill-check", "1.0.22");

        String recycleVersion = repo.moveToRecycle("bill-check");
        assertNotNull(recycleVersion);
        assertNull(repo.loadLatest("bill-check"));
        assertNull(repo.getEnabled("bill-check"));

        List<GraphRecycleEntry> list = repo.listRecycle();
        assertEquals(1, list.size());
        assertEquals("bill-check", list.get(0).graphId());
        assertEquals(recycleVersion, list.get(0).recycleVersion());

        GraphRecycleSnapshot snapshot = repo.loadRecycle("bill-check", recycleVersion);
        assertNotNull(snapshot);
        assertEquals("1.0.22", snapshot.enabledVersion());
        assertEquals(2, snapshot.definitions().size());

        GraphRecycleSnapshot restored = repo.restoreFromRecycle("bill-check", recycleVersion);
        assertEquals("1.0.22", restored.enabledVersion());
        assertEquals("1.0.22", repo.getEnabled("bill-check").version());
        assertEquals(2, repo.listVersions("bill-check").size());
        assertTrue(repo.listRecycle().isEmpty());
    }

    @Test
    void restore_conflict_whenFormalExists() {
        InMemoryGraphDefinitionRepository repo = new InMemoryGraphDefinitionRepository();
        repo.saveDraft(def("g1", "1.0.0", "A"));
        String rv = repo.moveToRecycle("g1");
        repo.saveDraft(def("g1", "2.0.0", "B"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> repo.restoreFromRecycle("g1", rv));
        assertEquals(GraphRecycleMessages.RESTORE_CONFLICT, ex.getMessage());
    }

    @Test
    void purge_removesRecycleRow() {
        InMemoryGraphDefinitionRepository repo = new InMemoryGraphDefinitionRepository();
        repo.saveDraft(def("g2", "1.0.0", "X"));
        String rv = repo.moveToRecycle("g2");
        assertTrue(repo.purgeRecycle("g2", rv));
        assertNull(repo.loadRecycle("g2", rv));
        assertFalse(repo.purgeRecycle("g2", rv));
    }

    private static GraphDefinition def(String graphId, String version, String displayName) {
        return new GraphDefinition(
                graphId, displayName, version, "",
                Map.of(), List.of(), List.of(), null, false);
    }
}
