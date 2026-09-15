package io.acelance.graph.dsl.ai.template;

import io.acelance.graph.dsl.ai.advisor.ChatClientAdvisorBundle;
import io.acelance.graph.dsl.ai.advisor.ChatClientAdvisorProvider;
import io.acelance.graph.dsl.ai.advisor.ChatClientAdvisorRequest;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.model.StubChatModelFactory;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.8：假 Advisor 验证 Provider 调用条件与 CONVERSATION_ID 透传。
 */
class ChatClientAdvisorProviderTemplateTest {

    @Test
    void readWrite_withConversationId_invokesProviderAndPassesConversationIdParam() {
        AtomicInteger provideCalls = new AtomicInteger();
        AtomicReference<ChatClientAdvisorRequest> captured = new AtomicReference<>();
        AtomicReference<Object> conversationParam = new AtomicReference<>();

        ChatClientAdvisorProvider provider = request -> {
            provideCalls.incrementAndGet();
            captured.set(request);
            CallAdvisor probe = new CallAdvisor() {
                @Override
                public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest,
                                                     CallAdvisorChain chain) {
                    Object cid = chatClientRequest.context().get(ChatMemory.CONVERSATION_ID);
                    conversationParam.set(cid);
                    return chain.nextCall(chatClientRequest);
                }

                @Override
                public String getName() {
                    return "probe-memory";
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            return new ChatClientAdvisorBundle(
                    List.of(probe), Map.of(ChatMemory.CONVERSATION_ID, "should-be-overridden-or-merged"));
        };

        LlmRequestContext ctx = new LlmRequestContext(
                "agent", "g1", "n1", "run-1", "session-abc", null, ResourceBinding.disabledAll());
        StreamingLlmTemplate template = newTemplate(provider);

        Map<String, Object> out = template.execute(LlmCallRequest.builder()
                .context(ctx)
                .systemTemplate("sys")
                .userMessage("hello")
                .outputKey("out")
                .streaming(false)
                .inlineModel(new InlineModel("http://x", "k", false, "m"))
                .memoryMode(MemoryMode.READ_WRITE)
                .build());

        assertTrue(out.containsKey("out"));
        assertEquals(1, provideCalls.get());
        assertEquals(MemoryMode.READ_WRITE, captured.get().memoryMode());
        assertEquals("session-abc", captured.get().ctx().conversationId());
        assertNotNull(conversationParam.get(), "CallAdvisor 应读到 advisor context 中的 CONVERSATION_ID");
        assertEquals("session-abc", String.valueOf(conversationParam.get()));
    }

    @Test
    void noneMode_skipsProviderEvenWithConversationId() {
        AtomicInteger provideCalls = new AtomicInteger();
        ChatClientAdvisorProvider provider = request -> {
            provideCalls.incrementAndGet();
            return ChatClientAdvisorBundle.empty();
        };

        LlmRequestContext ctx = new LlmRequestContext(
                "agent", "g1", "n1", "run-1", "session-abc", null, ResourceBinding.disabledAll());
        StreamingLlmTemplate template = newTemplate(provider);

        template.execute(LlmCallRequest.builder()
                .context(ctx)
                .systemTemplate("sys")
                .userMessage("hello")
                .outputKey("out")
                .streaming(false)
                .inlineModel(new InlineModel("http://x", "k", false, "m"))
                .memoryMode(MemoryMode.NONE)
                .build());

        assertEquals(0, provideCalls.get());
    }

    @Test
    void blankConversationId_skipsProvider() {
        AtomicInteger provideCalls = new AtomicInteger();
        ChatClientAdvisorProvider provider = request -> {
            provideCalls.incrementAndGet();
            return ChatClientAdvisorBundle.empty();
        };

        LlmRequestContext ctx = new LlmRequestContext(
                "agent", "g1", "n1", "run-1", "  ", null, ResourceBinding.disabledAll());
        assertNull(ctx.conversationId());

        StreamingLlmTemplate template = newTemplate(provider);
        template.execute(LlmCallRequest.builder()
                .context(ctx)
                .systemTemplate("sys")
                .userMessage("hello")
                .outputKey("out")
                .streaming(false)
                .inlineModel(new InlineModel("http://x", "k", false, "m"))
                .memoryMode(MemoryMode.READ_WRITE)
                .build());

        assertEquals(0, provideCalls.get());
    }

    @Test
    void conversationIdReservedKey_stable() {
        assertEquals("ace.graph.dsl.conversationId", LlmRequestContext.ACE_CONVERSATION_ID_KEY);
        assertNotNull(ChatMemory.CONVERSATION_ID);
    }

    private static StreamingLlmTemplate newTemplate(ChatClientAdvisorProvider provider) {
        ChatModelFactory factory = new StubChatModelFactory();
        return new StreamingLlmTemplate(
                new PromptRenderer(),
                new ModelEndpointResolver(null, null),
                factory,
                null,
                null, null, null, null, null, provider);
    }
}
