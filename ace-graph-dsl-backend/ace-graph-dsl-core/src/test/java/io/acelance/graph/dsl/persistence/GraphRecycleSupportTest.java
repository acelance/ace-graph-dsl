package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.NodeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphRecycleSupport} 单测（内存仓储）。
 */
class GraphRecycleSupportTest {

    @Test
    void findSubgraphReferrers_dedupesByGraphId_andStripsVersion() {
        InMemoryGraphRepo repo = new InMemoryGraphRepo();
        repo.put("child", def("child", "1.0.0", List.of()));
        repo.put("order-flow", def("order-flow", "1.0.0", List.of(
                new NodeRef("sg1", "SUBGRAPH", Map.of(), 0.0, 0.0, null, "child@1.0.0", null, null, null))));
        repo.put("order-flow", def("order-flow", "1.0.1", List.of(
                new NodeRef("sg1", "SUBGRAPH", Map.of(), 0.0, 0.0, null, "child", null, null, null))));
        repo.put("pay-flow", def("pay-flow", "1.0.0", List.of(
                new NodeRef("sg2", "SUBGRAPH", Map.of(), 0.0, 0.0, null, "child", null, null, null))));
        repo.put("other", def("other", "1.0.0", List.of(
                new NodeRef("n1", "NORMAL", Map.of(), 0.0, 0.0, null, null, null, null, null))));

        List<String> referrers = GraphRecycleSupport.findSubgraphReferrers(repo, "child");
        assertEquals(2, referrers.size());
        assertTrue(referrers.contains("order-flow"));
        assertTrue(referrers.contains("pay-flow"));
    }

    @Test
    void validateSnapshotForRestore_rejectsMissingEnabledVersion() {
        GraphRecycleSnapshot snapshot = new GraphRecycleSnapshot(
                "g1", "G1", "9.9.9",
                List.of(def("g1", "1.0.0", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> GraphRecycleSupport.validateSnapshotForRestore(snapshot));
    }

    private static GraphDefinition def(String graphId, String version, List<NodeRef> nodes) {
        return new GraphDefinition(graphId, graphId, version, "", Map.of(), nodes, List.of(), null, false);
    }

    /** 仅覆盖 listGraphIds / listVersions 的轻量桩 */
    private static final class InMemoryGraphRepo implements GraphDefinitionRepository {
        private final Map<String, List<GraphDefinition>> byId = new java.util.LinkedHashMap<>();

        void put(String graphId, GraphDefinition def) {
            byId.computeIfAbsent(graphId, k -> new java.util.ArrayList<>()).add(def);
        }

        @Override
        public SaveDraftResult saveDraft(GraphDefinition def, String baseVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphDefinition loadVersion(String graphId, String version) {
            return listVersions(graphId).stream().filter(d -> version.equals(d.version())).findFirst().orElse(null);
        }

        @Override
        public GraphDefinition loadLatest(String graphId) {
            List<GraphDefinition> list = listVersions(graphId);
            return list.isEmpty() ? null : list.get(list.size() - 1);
        }

        @Override
        public List<GraphDefinition> listVersions(String graphId) {
            return byId.getOrDefault(graphId, List.of());
        }

        @Override
        public List<GraphDefinition> listAll() {
            return byId.values().stream().flatMap(List::stream).toList();
        }

        @Override
        public void markEnabled(String graphId, String version) {
        }

        @Override
        public GraphDefinition getEnabled(String graphId) {
            return null;
        }

        @Override
        public List<GraphDefinition> listEnabled() {
            return List.of();
        }

        @Override
        public void disableCurrentEnabled(String graphId) {
        }

        @Override
        public List<String> listGraphIds() {
            return new java.util.ArrayList<>(byId.keySet());
        }

        @Override
        public String moveToRecycle(String graphId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GraphRecycleEntry> listRecycle() {
            return List.of();
        }

        @Override
        public GraphRecycleSnapshot loadRecycle(String graphId, String recycleVersion) {
            return null;
        }

        @Override
        public GraphRecycleSnapshot restoreFromRecycle(String graphId, String recycleVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean purgeRecycle(String graphId, String recycleVersion) {
            return false;
        }

        @Override
        public boolean existsInFormalStore(String graphId) {
            return byId.containsKey(graphId);
        }
    }
}
