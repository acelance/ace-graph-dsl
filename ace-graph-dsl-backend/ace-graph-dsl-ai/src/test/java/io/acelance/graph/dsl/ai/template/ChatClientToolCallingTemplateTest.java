package io.acelance.graph.dsl.ai.template;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.tool.LocalToolCallbacks;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatClient 多轮 tool-calling；有工具真流式通道 A。
 */
class ChatClientToolCallingTemplateTest {

    @Test
    void syncCall_executesToolThenReturnsFinalText() {
        AtomicInteger toolHits = new AtomicInteger();
        NamedToolCallback echo = LocalToolCallbacks.of(
                "echo", "echo", "echo desc",
                in -> {
                    toolHits.incrementAndGet();
                    return "TOOL_RESULT";
                });
        String modelToolName = echo.uniqueName();

        ChatModelFactory factory = endpoint -> new OneShotToolThenAnswerModel(modelToolName);
        StreamingLlmTemplate template = new StreamingLlmTemplate(
                new PromptRenderer(),
                new ModelEndpointResolver(null, null),
                factory,
                GraphStreamBridge.NOOP);

        ResourceBinding binding = ResourceBinding.disabledAll();
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "r", null, binding);

        Map<String, Object> out = template.execute(LlmCallRequest.builder()
                .context(ctx)
                .systemTemplate("sys")
                .userMessage("please echo")
                .variables(Map.of())
                .outputKey("out")
                .streaming(false)
                .inlineModel(new InlineModel("http://x", "k", false, "m"))
                .tools(List.of(echo))
                .build());

        assertEquals(1, toolHits.get(), "工具应被调用一次");
        String text = String.valueOf(out.get("out"));
        assertTrue(text.contains("FINAL"), "终稿应含 FINAL，实际=" + text);
    }

    @Test
    void streamWithTools_emitsFinalTextOnly_andMatchesOutputKey() {
        AtomicInteger toolHits = new AtomicInteger();
        NamedToolCallback echo = LocalToolCallbacks.of(
                "echo", "echo", "echo desc",
                in -> {
                    toolHits.incrementAndGet();
                    return "TOOL_RESULT";
                });
        String modelToolName = echo.uniqueName();

        List<TokenChunk> emitted = new ArrayList<>();
        GraphStreamBridge bridge = new GraphStreamBridge() {
            @Override
            public void emit(String runId, TokenChunk chunk) {
                emitted.add(chunk);
            }

            @Override
            public void complete(String runId) {
            }

            @Override
            public Flux<TokenChunk> register(String runId) {
                return Flux.empty();
            }
        };

        ChatModelFactory factory = endpoint -> new OneShotToolThenAnswerModel(modelToolName);
        StreamingLlmTemplate template = new StreamingLlmTemplate(
                new PromptRenderer(),
                new ModelEndpointResolver(null, null),
                factory,
                bridge);

        ResourceBinding binding = ResourceBinding.disabledAll();
        LlmRequestContext ctx = new LlmRequestContext("a", "g", "n", "run-stream", null, binding);

        Map<String, Object> out = template.execute(LlmCallRequest.builder()
                .context(ctx)
                .systemTemplate("sys")
                .userMessage("please echo")
                .variables(Map.of())
                .outputKey("out")
                .streaming(true)
                .streamResponseKind("BIZ")
                .inlineModel(new InlineModel("http://x", "k", false, "m"))
                .tools(List.of(echo))
                .build());

        assertEquals(1, toolHits.get());
        String output = String.valueOf(out.get("out"));
        assertTrue(output.contains("FINAL"), "outputKey 应含 FINAL，实际=" + output);

        StringBuilder joined = new StringBuilder();
        boolean sawFinished = false;
        for (TokenChunk c : emitted) {
            if (c.last() || c.outputType() == OutputType.AGENT_MODEL_FINISHED) {
                sawFinished = true;
                continue;
            }
            if (c.token() != null) {
                joined.append(c.token());
            }
        }
        assertTrue(sawFinished, "应有 FINISHED");
        assertEquals(output, joined.toString(), "通道 A 拼接应等于 outputKey");
    }

    /**
     * 首轮返回 tool_call；检测到 ToolResponseMessage 后返回终稿。
     */
    static final class OneShotToolThenAnswerModel implements ChatModel {

        private final String toolName;
        private final AtomicInteger rounds = new AtomicInteger();

        OneShotToolThenAnswerModel(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int round = rounds.incrementAndGet();
            boolean hasToolResp = prompt.getInstructions().stream()
                    .anyMatch(m -> m instanceof ToolResponseMessage);
            if (!hasToolResp) {
                AssistantMessage.ToolCall tc =
                        new AssistantMessage.ToolCall("call-1", "function", toolName, "{}");
                AssistantMessage am = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(tc))
                        .build();
                ChatGenerationMetadata meta = ChatGenerationMetadata.builder()
                        .finishReason("TOOL_CALLS")
                        .build();
                return new ChatResponse(List.of(new Generation(am, meta)));
            }
            String toolPayload = prompt.getInstructions().stream()
                    .filter(m -> m instanceof ToolResponseMessage)
                    .map(m -> ((ToolResponseMessage) m).getResponses().toString())
                    .findFirst()
                    .orElse("");
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("FINAL round=" + round + " tools=" + toolPayload))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
