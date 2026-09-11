package io.acelance.graph.dsl.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置桩 {@link ChatModelFactory}：无真实 LLM 时返回确定性 JSON，便于端到端验证。
 *
 * <p>行为对齐历史 {@code StubChatClientFactory} 的 call 结果形状（model/baseUrl/prompt/reply）。</p>
 */
public final class StubChatModelFactory implements ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(StubChatModelFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ChatModel create(ModelEndpoint endpoint) {
        log.info("创建 StubChatModel: modelId={}, baseUrl={}",
                endpoint == null ? null : endpoint.modelId(),
                endpoint == null ? null : endpoint.baseUrl());
        return new StubEchoChatModel(endpoint);
    }

    /**
     * 回声桩模型：同步返回 JSON；流式拆成「前半 + 后半」两个片段。
     */
    static final class StubEchoChatModel implements ChatModel {

        private final ModelEndpoint endpoint;

        StubEchoChatModel(ModelEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String text = buildReply(prompt);
            return wrap(text);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            String full = buildReply(prompt);
            int mid = Math.max(1, full.length() / 2);
            String a = full.substring(0, mid);
            String b = full.substring(mid);
            return Flux.just(wrap(a), wrap(b));
        }

        private String buildReply(Prompt prompt) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("model", endpoint != null ? endpoint.modelId() : null);
            result.put("baseUrl", endpoint != null ? endpoint.baseUrl() : null);
            result.put("prompt", promptText(prompt));
            result.put("reply", "【STUB】通用 agent 节点已按元数据装配并执行（未接入真实 LLM）。");
            try {
                return MAPPER.writeValueAsString(result);
            } catch (Exception e) {
                return result.toString();
            }
        }

        private static String promptText(Prompt prompt) {
            if (prompt == null || prompt.getInstructions() == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            prompt.getInstructions().forEach(m -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(m.getText());
            });
            return sb.toString();
        }

        private static ChatResponse wrap(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text == null ? "" : text))));
        }
    }
}
