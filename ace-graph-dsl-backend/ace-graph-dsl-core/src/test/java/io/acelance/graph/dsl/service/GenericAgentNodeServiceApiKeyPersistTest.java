package io.acelance.graph.dsl.service;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.agent.GenericAgentNodeFactory;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.definition.GenericAgentDefinition;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.persistence.GenericAgentDefinitionRepository;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class GenericAgentNodeServiceApiKeyPersistTest {

    private static final String NODE_ID = "agent:sql-gen";
    private static final String FULL_KEY = "sk-test-full-key-1de1";
    private static final String MASKED = "****1de1";

    @Mock
    private GenericAgentDefinitionRepository repository;

    private final Map<String, GenericAgentDefinition> store = new ConcurrentHashMap<>();

    private GenericAgentNodeService service;

    @BeforeEach
    void setUp() {
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            GenericAgentDefinition def = inv.getArgument(0);
            store.put(def.nodeId(), def);
            return def;
        });
        lenient().when(repository.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0))));

        GenericAgentNodeFactory factory = new GenericAgentNodeFactory() {
            @Override
            public GraphBoundAgentNode create(String nodeId, String graphId, GenericAgentSpec spec) {
                return stubNode(nodeId);
            }

            @Override
            public GraphBoundAgentNode create(String nodeId, String graphId, GenericAgentSpec spec,
                                              String displayName, String description, String version,
                                              Set<String> permissionTags) {
                return stubNode(nodeId);
            }
        };
        service = new GenericAgentNodeService(repository, new GraphNodeRegistry(List.of()), factory);
    }

    @Test
    void createPersistsFullInlineKeyAndHttpMaskDoesNotWriteBack() {
        GenericAgentDefinition saved = service.create(GenericAgentDefinition.fromSpec(
                NODE_ID, "SQL", spec(FULL_KEY, false)));
        assertEquals(FULL_KEY, store.get(NODE_ID).spec().modelApiKey());
        assertFalse(store.get(NODE_ID).spec().apiKeyMasked());
        assertEquals(MASKED, saved.masked().spec().modelApiKey());
        assertTrue(saved.masked().spec().apiKeyMasked());
    }

    @Test
    void createRejectsMaskedPlaceholder() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(MASKED, true))));
        assertTrue(ex.getMessage().contains("脱敏占位"));
        assertTrue(store.isEmpty());
    }

    @Test
    void updateKeepsStoredFullKeyWhenClientPostsHttpMask() {
        service.create(GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(FULL_KEY, false)));
        GenericAgentDefinition incoming = GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(MASKED, true));
        service.update(NODE_ID, incoming);
        assertEquals(FULL_KEY, store.get(NODE_ID).spec().modelApiKey());
        assertFalse(store.get(NODE_ID).spec().apiKeyMasked());
    }

    @Test
    void updateReplacesTruncatedLegacyRowWithFullKey() {
        store.put(NODE_ID, GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(MASKED, true)));
        service.update(NODE_ID, GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(FULL_KEY, false)));
        assertEquals(FULL_KEY, store.get(NODE_ID).spec().modelApiKey());
        assertFalse(store.get(NODE_ID).spec().apiKeyMasked());
    }

    @Test
    void updateRejectsWhenLegacyRowStillTruncatedAndNoNewKey() {
        store.put(NODE_ID, GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(MASKED, true)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(NODE_ID, GenericAgentDefinition.fromSpec(NODE_ID, "SQL", spec(MASKED, true))));
        assertTrue(ex.getMessage().contains("脱敏占位"));
    }

    private static GenericAgentSpec spec(String apiKey, boolean masked) {
        return new GenericAgentSpec(
                "https://example.com/compatible-mode/v1", apiKey, masked, "deepseek-v4-flash",
                "你是 SQL 生成器", "user_query", "agent_result", "BIZ", null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of(),
                MemoryMode.NONE, null, false, false, null, null);
    }

    private static GraphBoundAgentNode stubNode(String nodeId) {
        GraphNodeDescriptor descriptor = new GraphNodeDescriptor(
                nodeId, nodeId, GraphNodeDescriptor.CATEGORY_GENERIC_AGENT, "",
                Set.of(), Set.of("agent_result"), true, "1.0.0", Map.of(),
                NodeOrigin.GENERIC_AGENT, Set.of());
        return new GraphBoundAgentNode() {
            @Override
            public GraphNodeDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public NodeAction toAction(NodeRuntimeContext ctx) {
                return state -> Map.of();
            }

            @Override
            public GraphBoundAgentNode withGraphId(String graphId) {
                return this;
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> variables) {
                return Map.of();
            }
        };
    }
}
