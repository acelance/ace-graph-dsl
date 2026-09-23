package io.acelance.graph.dsl.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 轻量包装 {@link ToolCallingManager}：执行前按 toolCall <strong>id</strong> 合并流式分片。
 *
 * <p>规避 Spring AI 1.1.x {@code MessageAggregator} 将同一 id 拆成多条（首条有 name、后续
 * name=null、args 碎片）导致的 NPE。不含业务协议 / 未知工具降级（那是业务侧职责）。</p>
 */
public final class StreamingToolCallMergingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolCallMergingManager.class);

    private final ToolCallingManager delegate;

    public StreamingToolCallMergingManager(ToolCallingManager delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public ToolCallingManager getDelegate() {
        return delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        return delegate.executeToolCalls(prompt, normalizeStreamingToolCalls(chatResponse));
    }

    public static ChatResponse normalizeStreamingToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
            return chatResponse;
        }
        boolean changed = false;
        List<Generation> generations = new ArrayList<>(chatResponse.getResults().size());
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output == null || output.getToolCalls() == null || output.getToolCalls().isEmpty()) {
                generations.add(generation);
                continue;
            }
            List<AssistantMessage.ToolCall> raw = output.getToolCalls();
            List<AssistantMessage.ToolCall> merged = mergeToolCallsById(raw);
            if (merged.size() != raw.size() || !merged.equals(raw)) {
                changed = true;
                AssistantMessage.Builder msgBuilder = AssistantMessage.builder()
                        .content(output.getText())
                        .toolCalls(merged);
                if (output.getMetadata() != null && !output.getMetadata().isEmpty()) {
                    msgBuilder.properties(output.getMetadata());
                }
                ChatGenerationMetadata genMeta = generation.getMetadata();
                generations.add(genMeta != null
                        ? new Generation(msgBuilder.build(), genMeta)
                        : new Generation(msgBuilder.build()));
            }
            else {
                generations.add(generation);
            }
        }
        if (!changed) {
            return chatResponse;
        }
        return ChatResponse.builder()
                .from(chatResponse)
                .generations(generations)
                .build();
    }

    /**
     * 按 id 合并流式 toolCall 分片：name 取首个非空，arguments 按序拼接。
     */
    public static List<AssistantMessage.ToolCall> mergeToolCallsById(List<AssistantMessage.ToolCall> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() == 1) {
            AssistantMessage.ToolCall only = raw.get(0);
            if (StringUtils.hasText(only.name())) {
                return raw;
            }
            if (!StringUtils.hasText(only.id())) {
                log.warn("drop orphan toolCall fragment without id/name, argsLen={}",
                        only.arguments() == null ? 0 : only.arguments().length());
                return List.of();
            }
            log.warn("drop toolCall id={} with null name", only.id());
            return List.of();
        }
        boolean needsMerge = raw.stream().anyMatch(tc ->
                tc.name() == null
                        || (tc.id() != null
                        && raw.stream().filter(o -> tc.id().equals(o.id())).count() > 1));
        if (!needsMerge) {
            return raw;
        }

        Map<String, MutableToolCall> byId = new LinkedHashMap<>();
        List<AssistantMessage.ToolCall> orphan = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : raw) {
            String id = tc.id();
            if (!StringUtils.hasText(id)) {
                if (StringUtils.hasText(tc.name())) {
                    orphan.add(tc);
                }
                else {
                    log.warn("drop orphan toolCall fragment without id/name, argsLen={}",
                            tc.arguments() == null ? 0 : tc.arguments().length());
                }
                continue;
            }
            MutableToolCall acc = byId.computeIfAbsent(id, k -> new MutableToolCall(id, tc.type()));
            if (StringUtils.hasText(tc.name()) && !StringUtils.hasText(acc.name)) {
                acc.name = tc.name();
            }
            if (StringUtils.hasText(tc.type()) && !StringUtils.hasText(acc.type)) {
                acc.type = tc.type();
            }
            if (tc.arguments() != null) {
                acc.args.append(tc.arguments());
            }
        }

        List<AssistantMessage.ToolCall> merged = new ArrayList<>();
        for (MutableToolCall acc : byId.values()) {
            if (!StringUtils.hasText(acc.name)) {
                log.warn("merged toolCall id={} still has null name, skip", acc.id);
                continue;
            }
            String type = StringUtils.hasText(acc.type) ? acc.type : "function";
            merged.add(new AssistantMessage.ToolCall(acc.id, type, acc.name, acc.args.toString()));
        }
        merged.addAll(orphan);

        if (log.isDebugEnabled() && merged.size() != raw.size()) {
            log.debug("stream toolCalls merged: {} -> {} (by id)", raw.size(), merged.size());
        }
        return merged;
    }

    private static final class MutableToolCall {
        final String id;
        String type;
        String name;
        final StringBuilder args = new StringBuilder();

        MutableToolCall(String id, String type) {
            this.id = id;
            this.type = type;
        }
    }
}
