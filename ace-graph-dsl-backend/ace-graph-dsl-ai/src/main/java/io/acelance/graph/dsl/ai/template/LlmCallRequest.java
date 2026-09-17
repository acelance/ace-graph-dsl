package io.acelance.graph.dsl.ai.template;

import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.ai.options.LlmChatOptionsCustomizer;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.ai.tool.ToolConflictPolicy;
import io.acelance.graph.dsl.ai.tool.ToolDeduper;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.runtime.ModelOverride;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 请求级 LLM 调用参数（含工具、冲突策略、mediaInputKey、memoryMode、deepThinking）。
 */
public record LlmCallRequest(
        LlmRequestContext context,
        String systemTemplate,
        String userMessage,
        Map<String, Object> variables,
        String outputKey,
        boolean streaming,
        String streamResponseKind,
        InlineModel inlineModel,
        ModelOverride modelOverride,
        List<NamedToolCallback> tools,
        String mediaInputKey,
        ToolConflictPolicy conflictPolicy,
        MemoryMode memoryMode,
        boolean deepThinking,
        Map<String, Object> streamAttrs
) {
    public LlmCallRequest {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(outputKey, "outputKey");
        if (outputKey.isBlank()) {
            throw new IllegalArgumentException("outputKey 不能为空");
        }
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        systemTemplate = systemTemplate == null ? "" : systemTemplate;
        userMessage = userMessage == null ? "" : userMessage;
        tools = tools == null ? List.of() : List.copyOf(tools);
        mediaInputKey = mediaInputKey == null || mediaInputKey.isBlank() ? null : mediaInputKey.trim();
        conflictPolicy = conflictPolicy == null ? ToolDeduper.DEFAULT_POLICY : conflictPolicy;
        memoryMode = memoryMode == null ? MemoryMode.NONE : memoryMode;
        streamAttrs = (streamAttrs == null || streamAttrs.isEmpty()) ? Map.of() : Map.copyOf(streamAttrs);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LlmRequestContext context;
        private String systemTemplate = "";
        private String userMessage = "";
        private Map<String, Object> variables = Map.of();
        private String outputKey;
        private boolean streaming;
        private String streamResponseKind;
        private InlineModel inlineModel;
        private ModelOverride modelOverride;
        private List<NamedToolCallback> tools = List.of();
        private String mediaInputKey;
        private ToolConflictPolicy conflictPolicy;
        private MemoryMode memoryMode = MemoryMode.NONE;
        private boolean deepThinking;
        private Map<String, Object> streamAttrs = Map.of();

        public Builder context(LlmRequestContext context) {
            this.context = context;
            return this;
        }

        public Builder systemTemplate(String systemTemplate) {
            this.systemTemplate = systemTemplate;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        public Builder outputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public Builder streamResponseKind(String streamResponseKind) {
            this.streamResponseKind = streamResponseKind;
            return this;
        }

        public Builder inlineModel(InlineModel inlineModel) {
            this.inlineModel = inlineModel;
            return this;
        }

        public Builder modelOverride(ModelOverride modelOverride) {
            this.modelOverride = modelOverride;
            return this;
        }

        public Builder tools(List<NamedToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        /** 多模态引用所在 state key；空=纯文本 */
        public Builder mediaInputKey(String mediaInputKey) {
            this.mediaInputKey = mediaInputKey;
            return this;
        }

        /** LOCAL vs MCP 同名冲突策略；默认 LOCAL_FIRST */
        public Builder conflictPolicy(ToolConflictPolicy conflictPolicy) {
            this.conflictPolicy = conflictPolicy;
            return this;
        }

        /** 节点记忆模式；默认 NONE */
        public Builder memoryMode(MemoryMode memoryMode) {
            this.memoryMode = memoryMode;
            return this;
        }

        /**
         * 本轮是否开启深度思考（state {@code ace.graph.dsl.deepThinking} ∧ 节点 {@code applyDeepThinking}）。
         * 由 {@link LlmChatOptionsCustomizer} 写入 ChatOptions.extraBody。
         */
        public Builder deepThinking(boolean deepThinking) {
            this.deepThinking = deepThinking;
            return this;
        }

        /**
         * 流式 token 透传属性（如业务附加参数），写入 {@link io.acelance.graph.dsl.streaming.TokenChunk#attrs()}。
         */
        public Builder streamAttrs(Map<String, Object> streamAttrs) {
            this.streamAttrs = streamAttrs;
            return this;
        }

        public LlmCallRequest build() {
            return new LlmCallRequest(context, systemTemplate, userMessage, variables,
                    outputKey, streaming, streamResponseKind, inlineModel, modelOverride, tools,
                    mediaInputKey, conflictPolicy, memoryMode, deepThinking, streamAttrs);
        }
    }
}
