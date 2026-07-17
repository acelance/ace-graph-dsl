package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEdgeJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripDoesNotEmitConditionalProperty() throws Exception {
        GraphDefinition def = new GraphDefinition(
                "g1", "test", "1.0.0", "",
                Map.of(),
                List.of(new NodeRef("a", Map.of(), null, null)),
                List.of(new GraphEdge("a", null, GraphEdge.TYPE_CONDITIONAL, "disp",
                        Map.of("k", "b"), null, null)),
                new CompileConfigDto(List.of(), "memory"), null);

        String json = objectMapper.writeValueAsString(def);
        JsonNode edge = objectMapper.readTree(json).get("edges").get(0);
        assertFalse(edge.has("conditional"), "JSON 不应包含 isConditional 衍生的 conditional 字段");
        assertFalse(edge.has("scriptRouting"), "JSON 不应包含 isScriptRouting 衍生字段");

        GraphDefinition restored = objectMapper.readValue(json, GraphDefinition.class);
        assertNotNull(restored.edges().get(0));
        assertTrue(restored.edges().get(0).isConditional());
    }

    @Test
    void deserializeLegacyJsonWithConditionalField() throws Exception {
        String legacy = """
                {
                  "graphId": "g1",
                  "displayName": "test",
                  "version": "1.0.0",
                  "description": "",
                  "keyStrategies": {},
                  "nodes": [{"nodeId": "a", "config": {}}],
                  "edges": [{
                    "from": "a",
                    "to": "",
                    "type": "conditional",
                    "conditional": true,
                    "dispatcher": "disp",
                    "mapping": {"k": "b"}
                  }],
                  "compile": {"interruptBefore": [], "saver": "memory"}
                }
                """;

        GraphDefinition def = objectMapper.readValue(legacy, GraphDefinition.class);
        assertTrue(def.edges().get(0).isConditional());
    }
}
