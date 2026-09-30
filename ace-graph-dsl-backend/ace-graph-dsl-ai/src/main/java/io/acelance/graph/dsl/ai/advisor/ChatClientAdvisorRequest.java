package io.acelance.graph.dsl.ai.advisor;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.llm.MemoryWriteFlag;

import java.util.Objects;
import java.util.Set;

/**
 * 向业务 {@link ChatClientAdvisorProvider} 索取 Advisor 时的入参（P3.8）。
 *
 * <p>{@code memoryWrites} 非空时优先生效；空且 {@code memoryMode=READ_ONLY} 走只读 Advisor。</p>
 */
public record ChatClientAdvisorRequest(
        LlmRequestContext ctx,
        MemoryMode memoryMode,
        Set<MemoryWriteFlag> memoryWrites,
        boolean streaming,
        boolean hasTools
) {
    public ChatClientAdvisorRequest {
        Objects.requireNonNull(ctx, "ctx");
        memoryMode = memoryMode == null ? MemoryMode.NONE : memoryMode;
        memoryWrites = memoryWrites == null
                ? Set.of()
                : Set.copyOf(memoryWrites);
    }

    /** 兼容旧调用：仅 mode，writes 由 mode 推导。 */
    public ChatClientAdvisorRequest(LlmRequestContext ctx, MemoryMode memoryMode,
                                    boolean streaming, boolean hasTools) {
        this(ctx, memoryMode, MemoryWriteFlag.fromLegacyMode(memoryMode), streaming, hasTools);
    }
}
