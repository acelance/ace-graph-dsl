package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import io.acelance.graph.dsl.ai.memory.GraphNodeMemoryPersister;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryWriteFlag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAA 父节点 memoryWrites → Persister 钩子单测。
 */
class FlowAgentNodeActionMemoryPersistTest {

    @Test
    void writeMain_callsPersistAssistantWithParentOutputText() throws Exception {
        RecordingPersister persister = new RecordingPersister();
        Agent stub = new StubFlowAgent(Map.of("child_out", "终态答复正文"));

        FlowAgentNodeAction action = new FlowAgentNodeAction(
                "g1", "saa_node", "SEQUENTIAL", stub,
                List.of("user_query"), "agent_result",
                new String[]{"child_out"}, new String[]{"child"},
                EnumSet.of(MemoryWriteFlag.WRITE_ASSISTANT_MAIN_TEXT),
                persister);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "你好");
        data.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "cid-1");
        data.put(LlmRequestContext.ACE_AGENT_CODE_KEY, "agent-x");
        OverAllState state = new OverAllState(data);

        Map<String, Object> out = action.apply(state);
        assertEquals("终态答复正文", out.get("agent_result"));
        assertEquals(0, persister.userCalls.get());
        assertEquals(1, persister.assistantCalls.get());
        assertEquals("终态答复正文", persister.lastAssistantText);
        assertEquals("cid-1", persister.lastConversationId);
    }

    @Test
    void writeUserAndMain_callsBoth() throws Exception {
        RecordingPersister persister = new RecordingPersister();
        Agent stub = new StubFlowAgent(Map.of("child_out", "答"));

        FlowAgentNodeAction action = new FlowAgentNodeAction(
                "g1", "saa_node", "SEQUENTIAL", stub,
                List.of("user_query"), "agent_result",
                new String[]{"child_out"}, null,
                EnumSet.of(MemoryWriteFlag.WRITE_USER, MemoryWriteFlag.WRITE_ASSISTANT_MAIN_TEXT),
                persister);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "问");
        data.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "cid-2");
        OverAllState state = new OverAllState(data);

        action.apply(state);
        assertEquals(1, persister.userCalls.get());
        assertEquals(1, persister.assistantCalls.get());
    }

    @Test
    void thinkingOnly_doesNotPersistAssistant() throws Exception {
        RecordingPersister persister = new RecordingPersister();
        Agent stub = new StubFlowAgent(Map.of("child_out", "答"));

        FlowAgentNodeAction action = new FlowAgentNodeAction(
                "g1", "saa_node", "SEQUENTIAL", stub,
                List.of("user_query"), "agent_result",
                new String[]{"child_out"}, null,
                EnumSet.of(MemoryWriteFlag.WRITE_ASSISTANT_THINKING),
                persister);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "问");
        data.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "cid-3");
        action.apply(new OverAllState(data));

        assertEquals(0, persister.userCalls.get());
        assertEquals(0, persister.assistantCalls.get());
    }

    @Test
    void noConversationId_skipsPersist() throws Exception {
        RecordingPersister persister = new RecordingPersister();
        Agent stub = new StubFlowAgent(Map.of("child_out", "答"));

        FlowAgentNodeAction action = new FlowAgentNodeAction(
                "g1", "saa_node", "SEQUENTIAL", stub,
                List.of("user_query"), "agent_result",
                new String[]{"child_out"}, null,
                EnumSet.of(MemoryWriteFlag.WRITE_ASSISTANT_MAIN_TEXT),
                persister);

        action.apply(new OverAllState(Map.of("user_query", "问")));
        assertEquals(0, persister.assistantCalls.get());
    }

    @Test
    void emptyWrites_skipsEvenWithPersister() throws Exception {
        RecordingPersister persister = new RecordingPersister();
        Agent stub = new StubFlowAgent(Map.of("child_out", "答"));

        FlowAgentNodeAction action = new FlowAgentNodeAction(
                "g1", "saa_node", "SEQUENTIAL", stub,
                List.of("user_query"), "agent_result",
                new String[]{"child_out"}, null,
                Set.of(),
                persister);

        Map<String, Object> data = new HashMap<>();
        data.put("user_query", "问");
        data.put(LlmRequestContext.ACE_CONVERSATION_ID_KEY, "cid-4");
        action.apply(new OverAllState(data));
        assertEquals(0, persister.userCalls.get());
        assertEquals(0, persister.assistantCalls.get());
        assertTrue(persister.assistantTexts.isEmpty());
    }

    /** 不走真实图编译，只返回固定 OverAllState。 */
    private static final class StubFlowAgent extends Agent {
        private final Map<String, Object> resultData;

        StubFlowAgent(Map<String, Object> resultData) {
            super("stub", "memory-persist-ut");
            this.resultData = resultData;
        }

        @Override
        public Optional<OverAllState> invoke(Map<String, Object> input) {
            return Optional.of(new OverAllState(resultData));
        }

        @Override
        protected StateGraph initGraph() throws GraphStateException {
            throw new UnsupportedOperationException("stub");
        }
    }

    private static final class RecordingPersister implements GraphNodeMemoryPersister {
        final AtomicInteger userCalls = new AtomicInteger();
        final AtomicInteger assistantCalls = new AtomicInteger();
        final List<String> assistantTexts = new ArrayList<>();
        volatile String lastAssistantText;
        volatile String lastConversationId;

        @Override
        public void persistUser(PersistUserRequest request) {
            userCalls.incrementAndGet();
            lastConversationId = request.ctx().conversationId();
        }

        @Override
        public void persistAssistantMain(PersistAssistantMainRequest request) {
            assistantCalls.incrementAndGet();
            lastAssistantText = request.assistantText();
            lastConversationId = request.ctx().conversationId();
            assistantTexts.add(request.assistantText());
        }
    }
}
