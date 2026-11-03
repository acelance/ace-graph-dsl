package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAA invoke threadId 与父图 runId 对齐单测。
 */
class SaaFlowInvokeSupportTest {

    @Test
    void prefersRunIdAsThreadId_alignedWithParentGraph() {
        Map<String, Object> data = new HashMap<>();
        data.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-abc");
        data.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "session-xyz");
        RunnableConfig cfg = SaaFlowInvokeSupport.buildInvokeConfig(
                new OverAllState(data), "g1", "saa_node");
        assertEquals("run-abc", cfg.threadId().orElse(null));
    }

    @Test
    void fallsBackToConversationIdWhenRunIdMissing() {
        Map<String, Object> data = new HashMap<>();
        data.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "session-only");
        RunnableConfig cfg = SaaFlowInvokeSupport.buildInvokeConfig(
                new OverAllState(data), "g1", "saa_node");
        assertEquals("session-only", cfg.threadId().orElse(null));
    }

    @Test
    void generatesThreadIdWhenBothMissing() {
        RunnableConfig cfg = SaaFlowInvokeSupport.buildInvokeConfig(
                new OverAllState(Map.of()), "g1", "saa_node");
        assertTrue(cfg.threadId().isPresent());
        assertTrue(cfg.threadId().get().length() >= 8);
    }
}
