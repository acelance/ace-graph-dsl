package io.acelance.graph.dsl.ai.advisor;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.resource.ResourceBinding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4b：延迟包装必须转发 mergeHistoryForPrompt；lambda 只实现 provide 会静默 no-op。
 */
class DeferredChatClientAdvisorProviderTest {

    @Test
    void deferredAnonymousClass_forwardsMergeHistoryForPrompt() {
        AtomicInteger mergeCalls = new AtomicInteger();
        AtomicReference<ChatClientAdvisorProvider> liveRef = new AtomicReference<>();
        liveRef.set(new ChatClientAdvisorProvider() {
            @Override
            public ChatClientAdvisorBundle provide(ChatClientAdvisorRequest request) {
                return ChatClientAdvisorBundle.empty();
            }

            @Override
            public List<Message> mergeHistoryForPrompt(ChatClientAdvisorRequest request,
                                                       List<Message> seedMessages) {
                mergeCalls.incrementAndGet();
                return List.of(new UserMessage("from-history"), seedMessages.get(0));
            }
        });

        ChatClientAdvisorProvider deferred = new ChatClientAdvisorProvider() {
            @Override
            public ChatClientAdvisorBundle provide(ChatClientAdvisorRequest request) {
                ChatClientAdvisorProvider live = liveRef.get();
                return live == null ? ChatClientAdvisorBundle.empty() : live.provide(request);
            }

            @Override
            public List<Message> mergeHistoryForPrompt(ChatClientAdvisorRequest request,
                                                       List<Message> seedMessages) {
                ChatClientAdvisorProvider live = liveRef.get();
                if (live == null) {
                    return seedMessages;
                }
                return live.mergeHistoryForPrompt(request, seedMessages);
            }
        };

        List<Message> merged = deferred.mergeHistoryForPrompt(sampleRequest(),
                List.of(new UserMessage("seed")));
        assertEquals(1, mergeCalls.get());
        assertEquals(2, merged.size());
        assertTrue(merged.get(0).getText().contains("from-history"));
    }

    @Test
    void functionalLambda_doesNotForwardMerge_documentsR4bTrap() {
        AtomicInteger mergeCalls = new AtomicInteger();
        ChatClientAdvisorProvider live = new ChatClientAdvisorProvider() {
            @Override
            public ChatClientAdvisorBundle provide(ChatClientAdvisorRequest request) {
                return ChatClientAdvisorBundle.empty();
            }

            @Override
            public List<Message> mergeHistoryForPrompt(ChatClientAdvisorRequest request,
                                                       List<Message> seedMessages) {
                mergeCalls.incrementAndGet();
                return List.of(new UserMessage("from-history"));
            }
        };
        ChatClientAdvisorProvider lambdaDeferred = request -> live.provide(request);
        List<Message> seed = List.of(new UserMessage("seed"));
        List<Message> merged = lambdaDeferred.mergeHistoryForPrompt(sampleRequest(), seed);
        assertEquals(0, mergeCalls.get(), "lambda 包装不会调用 live.merge");
        assertEquals(seed, merged);
    }

    private static ChatClientAdvisorRequest sampleRequest() {
        LlmRequestContext ctx = new LlmRequestContext(
                "a", "g", "b", "r", "s", null, ResourceBinding.disabledAll());
        return new ChatClientAdvisorRequest(ctx, MemoryMode.READ_WRITE, Set.of(), true, true);
    }
}
