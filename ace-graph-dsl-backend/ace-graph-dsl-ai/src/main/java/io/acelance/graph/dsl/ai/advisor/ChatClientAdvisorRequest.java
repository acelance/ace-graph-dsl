package io.acelance.graph.dsl.ai.advisor;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;

import java.util.Objects;

/**
 * 向业务 {@link ChatClientAdvisorProvider} 索取 Advisor 时的入参（P3.8）。
 */
public record ChatClientAdvisorRequest(
        LlmRequestContext ctx,
        MemoryMode memoryMode,
        boolean streaming,
        boolean hasTools
) {
    public ChatClientAdvisorRequest {
        Objects.requireNonNull(ctx, "ctx");
        memoryMode = memoryMode == null ? MemoryMode.NONE : memoryMode;
    }
}
