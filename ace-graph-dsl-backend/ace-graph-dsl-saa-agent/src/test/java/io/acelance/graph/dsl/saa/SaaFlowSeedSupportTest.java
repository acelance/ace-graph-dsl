package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaaFlowSeedSupportTest {

    @Test
    void copyParentContext_透传保留键与media_refs_不覆盖已有() {
        Map<String, Object> parentData = new LinkedHashMap<>();
        parentData.put("user_query", "你好");
        parentData.put(LlmRequestContext.ACE_AGENT_CODE_KEY, "ztc-service-agent-v2");
        parentData.put(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-1");
        parentData.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "sess-1");
        parentData.put("media_refs", List.of("file-1"));
        parentData.put("noise", "skip-me");
        OverAllState parent = new OverAllState(parentData);

        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("user_query", "业务输入");
        seed.put(LlmRequestContext.ACE_AGENT_CODE_KEY, "already-set");

        List<String> copied = SaaFlowSeedSupport.copyParentContext(parent, seed);

        assertEquals("already-set", seed.get(LlmRequestContext.ACE_AGENT_CODE_KEY));
        assertFalse(copied.contains(LlmRequestContext.ACE_AGENT_CODE_KEY));
        assertEquals("run-1", seed.get(ModelOverrideSpec.ACE_RUN_ID_KEY));
        assertEquals("sess-1", seed.get(LlmRequestContext.ACE_CONVERSATION_ID_KEY));
        assertEquals(List.of("file-1"), seed.get("media_refs"));
        assertFalse(seed.containsKey("noise"));
        assertTrue(copied.contains(ModelOverrideSpec.ACE_RUN_ID_KEY));
        assertTrue(copied.contains("media_refs"));
    }
}
