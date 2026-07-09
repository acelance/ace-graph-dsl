package io.acelance.graph.dsl.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDefinitionContentComparatorTest {

    @Test
    void ignoresVersionAndDisplayName() {
        GraphDefinition a = def("1.0.0", "A", List.of(node("n1")));
        GraphDefinition b = def("2.0.0", "B", List.of(node("n1")));
        assertTrue(GraphDefinitionContentComparator.sameContent(a, b));
    }

    @Test
    void detectsNodeChange() {
        GraphDefinition a = def("1.0.0", "A", List.of(node("n1")));
        GraphDefinition b = def("1.0.0", "A", List.of(node("n2")));
        assertFalse(GraphDefinitionContentComparator.sameContent(a, b));
    }

    private static GraphDefinition def(String version, String name, List<NodeRef> nodes) {
        return new GraphDefinition(
                "g1", name, version, "",
                Map.of(),
                nodes,
                List.of(),
                CompileConfigDto.defaultConfig(), null);
    }

    private static NodeRef node(String id) {
        return new NodeRef(id, Map.of(), null, null);
    }
}
