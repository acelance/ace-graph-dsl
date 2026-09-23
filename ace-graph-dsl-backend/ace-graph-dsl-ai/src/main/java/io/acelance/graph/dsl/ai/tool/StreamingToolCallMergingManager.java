package io.acelance.graph.dsl.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 轻量包装 {@link ToolCallingManager}：执行前按 toolCall <strong>id</strong> 合并流式分片；
 * 并对模型幻觉的未注册工具名做<strong>软降级</strong>（占位 callback 回错误提示，不抛
 * {@code No ToolCallback found}）。
 *
 * <p>规避 Spring AI 1.1.x {@code MessageAggregator} 将同一 id 拆成多条（首条有 name、后续
 * name=null、args 碎片）导致的 NPE。</p>
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
        ChatResponse normalized = normalizeStreamingToolCalls(chatResponse);
        Set<String> known = knownToolNames(prompt);
        Set<String> unknown = unknownToolNames(normalized, known);
        if (!unknown.isEmpty()) {
            log.warn("unknown tool calls (soft degrade): unknown={}, available={}", unknown, known);
            Prompt enriched = attachUnknownToolFallbacks(prompt, unknown, known);
            if (enriched != null) {
                return delegate.executeToolCalls(enriched, normalized);
            }
            ChatResponse stripped = dropUnknownToolCalls(normalized, known);
            log.warn("unknown tool calls dropped (options not attachable): {}", unknown);
            return delegate.executeToolCalls(prompt, stripped);
        }
        return delegate.executeToolCalls(prompt, normalized);
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

    /** 本轮 Prompt Options 上已挂载的工具名。 */
    public static Set<String> knownToolNames(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions opts)) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        if (opts.getToolCallbacks() != null) {
            for (ToolCallback cb : opts.getToolCallbacks()) {
                if (cb != null && cb.getToolDefinition() != null
                        && StringUtils.hasText(cb.getToolDefinition().name())) {
                    names.add(cb.getToolDefinition().name().trim());
                }
            }
        }
        if (opts.getToolNames() != null) {
            for (String n : opts.getToolNames()) {
                if (StringUtils.hasText(n)) {
                    names.add(n.trim());
                }
            }
        }
        return names;
    }

    /** 模型声明的 toolCall name（去重保序）。 */
    public static List<String> requestedToolNames(ChatResponse chatResponse) {
        List<String> out = new ArrayList<>();
        if (chatResponse == null || chatResponse.getResults() == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Generation generation : chatResponse.getResults()) {
            if (generation == null || generation.getOutput() == null
                    || generation.getOutput().getToolCalls() == null) {
                continue;
            }
            for (AssistantMessage.ToolCall tc : generation.getOutput().getToolCalls()) {
                if (tc == null || !StringUtils.hasText(tc.name())) {
                    continue;
                }
                String name = tc.name().trim();
                if (seen.add(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    public static Set<String> unknownToolNames(ChatResponse chatResponse, Set<String> known) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String name : requestedToolNames(chatResponse)) {
            if (known == null || !known.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /**
     * 将未知名以占位 {@link ToolCallback} 并入 Options 副本；失败返回 {@code null}。
     */
    static Prompt attachUnknownToolFallbacks(Prompt prompt, Set<String> unknownNames, Set<String> knownNames) {
        if (prompt == null || unknownNames == null || unknownNames.isEmpty()) {
            return prompt;
        }
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions opts)) {
            return null;
        }
        List<ToolCallback> merged = new ArrayList<>();
        if (opts.getToolCallbacks() != null) {
            merged.addAll(opts.getToolCallbacks());
        }
        Set<String> already = new LinkedHashSet<>();
        for (ToolCallback cb : merged) {
            if (cb != null && cb.getToolDefinition() != null
                    && StringUtils.hasText(cb.getToolDefinition().name())) {
                already.add(cb.getToolDefinition().name().trim());
            }
        }
        String availableHint = knownNames == null || knownNames.isEmpty()
                ? "(none)"
                : knownNames.stream().sorted().collect(Collectors.joining(", "));
        for (String name : unknownNames) {
            if (!StringUtils.hasText(name) || already.contains(name)) {
                continue;
            }
            merged.add(new UnknownNameToolCallback(name, availableHint));
            already.add(name);
        }
        try {
            ChatOptions copy = opts.copy();
            if (!(copy instanceof ToolCallingChatOptions copyTool)) {
                return null;
            }
            copyTool.setToolCallbacks(merged);
            return new Prompt(prompt.getInstructions(), copy);
        }
        catch (Exception e) {
            log.warn("attach unknown-tool fallback failed: {}", e.toString());
            return null;
        }
    }

    static ChatResponse dropUnknownToolCalls(ChatResponse chatResponse, Set<String> known) {
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
            List<AssistantMessage.ToolCall> kept = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                if (tc == null || !StringUtils.hasText(tc.name())) {
                    continue;
                }
                if (known != null && known.contains(tc.name().trim())) {
                    kept.add(tc);
                }
                else {
                    changed = true;
                }
            }
            if (kept.size() == output.getToolCalls().size()) {
                generations.add(generation);
                continue;
            }
            changed = true;
            AssistantMessage.Builder msgBuilder = AssistantMessage.builder()
                    .content(output.getText())
                    .toolCalls(kept);
            if (output.getMetadata() != null && !output.getMetadata().isEmpty()) {
                msgBuilder.properties(output.getMetadata());
            }
            ChatGenerationMetadata genMeta = generation.getMetadata();
            generations.add(genMeta != null
                    ? new Generation(msgBuilder.build(), genMeta)
                    : new Generation(msgBuilder.build()));
        }
        if (!changed) {
            return chatResponse;
        }
        return ChatResponse.builder()
                .from(chatResponse)
                .generations(generations)
                .build();
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

    /**
     * 未知名占位工具：回错误提示，引导模型改调本轮已挂工具。
     */
    static final class UnknownNameToolCallback implements ToolCallback {

        private final String requestedName;
        private final String availableToolsHint;

        UnknownNameToolCallback(String requestedName, String availableToolsHint) {
            this.requestedName = requestedName;
            this.availableToolsHint = availableToolsHint == null ? "(none)" : availableToolsHint;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(requestedName)
                    .description("placeholder for unknown tool name")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String functionInput) {
            return "{\"error\":\"unknown_tool\",\"name\":\"" + jsonEscape(requestedName)
                    + "\",\"hint\":\"Tool does not exist. Only call tools listed for this turn: "
                    + jsonEscape(availableToolsHint) + "\"}";
        }

        private static String jsonEscape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
