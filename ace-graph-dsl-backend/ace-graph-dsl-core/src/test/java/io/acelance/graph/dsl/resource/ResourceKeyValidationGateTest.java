package io.acelance.graph.dsl.resource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceKeyValidationGate} 有/无 Validator 路径。
 */
class ResourceKeyValidationGateTest {

    @Test
    void nullValidatorSkipped() {
        assertDoesNotThrow(() -> ResourceKeyValidationGate.assertValid(
                null, "a", "g", "n", ResourceBinding.disabledAll()));
    }

    @Test
    void failingValidatorThrows() {
        ResourceKeyValidator alwaysFail = (agentCode, graphId, binding) ->
                new ResourceKeyValidator.ValidationResult(false, List.of(
                        new ResourceKeyValidator.ItemResult(
                                ResourceType.PROMPT, "p1",
                                ResourceKeyValidator.Status.MISSING, "not found")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ResourceKeyValidationGate.assertValid(
                        alwaysFail, "a", "g", "n1", ResourceBinding.disabledAll()));
        assertTrue(ex.getMessage().contains("p1"));
        assertTrue(ex.getMessage().contains("n1"));
    }

    @Test
    void passingValidatorOk() {
        ResourceKeyValidator ok = (agentCode, graphId, binding) ->
                ResourceKeyValidator.ValidationResult.passed();
        assertDoesNotThrow(() -> ResourceKeyValidationGate.assertValid(
                ok, "a", "g", "n", ResourceBinding.disabledAll()));
    }
}
