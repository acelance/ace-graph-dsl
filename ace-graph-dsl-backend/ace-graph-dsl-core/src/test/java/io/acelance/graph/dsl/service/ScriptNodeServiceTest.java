package io.acelance.graph.dsl.service;

import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.persistence.DynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.script.AviatorScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.ScriptNodeFactory;
import io.acelance.graph.dsl.script.SpelScriptEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScriptNodeServiceTest {

    @Mock
    private DynamicNodeDefinitionRepository repository;

    private ScriptNodeService service;

    @BeforeEach
    void setUp() {
        ScriptEngineRegistry registry = new ScriptEngineRegistry(List.of(
                new AviatorScriptEngine(1000),
                new SpelScriptEngine(1000)));
        ScriptNodeFactory factory = new ScriptNodeFactory(registry);
        GraphNodeRegistry nodeRegistry = new GraphNodeRegistry(List.of());
        service = new ScriptNodeService(repository, nodeRegistry, factory, registry, 1024, "aviator", null);
    }

    @Test
    void rejectBlankScript() {
        assertThrows(IllegalArgumentException.class, () -> service.validateScript("aviator", "  "));
    }

    @Test
    void rejectOversizedScript() {
        String huge = "x".repeat(2048);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateScript("aviator", huge));
        assertTrue(ex.getMessage().contains("大小限制"));
    }

    @Test
    void rejectUnknownEngine() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validateScript("no-such-engine", "1 + 1"));
    }

    @Test
    void testRunDraftAviator() {
        Map<String, Object> out = service.testRunDraft(
                "aviator",
                "seq.map('normalized_query', state.query)",
                Set.of("query"),
                Set.of("normalized_query"),
                Map.of("query", "hello"),
                Map.of());
        assertEquals("hello", out.get("normalized_query"));
    }

    @Test
    void testRunDraftSpel() {
        Map<String, Object> out = service.testRunDraft(
                "spel",
                "{'normalized_query': #state['query']?.trim()}",
                Set.of("query"),
                Set.of("normalized_query"),
                Map.of("query", "  hi  "),
                Map.of());
        assertEquals("hi", out.get("normalized_query"));
    }

    @Test
    void inputKeysTrimExtraMockState() {
        // secret 不在 inputKeys 中：试跑前置校验应直接给出可读提示（避免静默裁剪后引擎报错）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.testRunDraft(
                "aviator",
                "seq.map('flag', state.secret == nil ? 'ok' : 'leak')",
                Set.of("query"),
                Set.of("flag"),
                Map.of("query", "x", "secret", "y"),
                Map.of()));
        assertTrue(ex.getMessage().contains("Mock State"), () -> ex.getMessage());
        assertTrue(ex.getMessage().contains("secret"), () -> ex.getMessage());
    }

    @Test
    void rejectMockKeysNotInInputKeysWithFriendlyMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.testRunDraft(
                        "aviator",
                        "seq.map('final_price', state.amount)",
                        Set.of("query"),
                        Set.of("final_price"),
                        Map.of("amount", 100, "discount", 0.9, "member", "normal"),
                        Map.of()));
        assertTrue(ex.getMessage().contains("Input Keys"), () -> ex.getMessage());
        assertTrue(ex.getMessage().contains("amount"), () -> ex.getMessage());
    }

    @Test
    void createDoesNotPersistWhenValidationFails() {
        DynamicNodeDefinition input = new DynamicNodeDefinition(
                "script:bad",
                "坏脚本",
                "NORMAL",
                "",
                Set.of("query"),
                Set.of("out"),
                false,
                "1.0.0",
                "aviator",
                "!!!invalid!!!",
                "",
                Map.of(),
                NodeOrigin.SCRIPT,
                Set.of(),
                "tester",
                Instant.now(),
                Instant.now(),
                true);
        when(repository.findById("script:bad")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.create(input));
        verify(repository, never()).save(any());
    }
}
