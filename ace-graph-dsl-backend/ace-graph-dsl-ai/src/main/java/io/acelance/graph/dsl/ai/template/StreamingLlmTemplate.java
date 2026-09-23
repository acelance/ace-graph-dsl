package io.acelance.graph.dsl.ai.template;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.ai.advisor.ChatClientAdvisorBundle;
import io.acelance.graph.dsl.ai.advisor.ChatClientAdvisorProvider;
import io.acelance.graph.dsl.ai.advisor.ChatClientAdvisorRequest;
import io.acelance.graph.dsl.ai.media.DefaultMediaRefResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.ai.memory.MemoryDisplayUserTextResolver;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.ai.model.ModelEndpoint;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.options.LlmChatOptionsCustomizer;
import io.acelance.graph.dsl.ai.skill.ForceSkillActivator;
import io.acelance.graph.dsl.ai.skill.SkillTools;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.ai.tool.StreamingToolCallMergingManager;
import io.acelance.graph.dsl.ai.tool.ToolConflictPolicy;
import io.acelance.graph.dsl.ai.tool.ToolDeduper;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.llm.MemoryMode;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.media.MediaRefs;
import io.acelance.graph.dsl.prompt.PromptContentResolver;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.skill.ForceSkills;
import io.acelance.graph.dsl.skill.SkillCatalogResolver;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillDescriptor;
import io.acelance.graph.dsl.skill.SkillL1Catalog;
import io.acelance.graph.dsl.skill.SkillResourceLoader;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 流式 LLM 节点模板：prompt / Skill / Media / 工具 / 记忆 Advisor / 流式推送。
 *
 * <p>经原生 {@link ChatClient} 装配并执行；带工具时由 Spring AI 完成
 * tool_call → 执行 → 回灌多轮。有工具 + 流式走 {@code stream().chatResponse()}（通道 A 稳健过滤）；
 * 工具进度等业务帧不经本类代做/拦截（通道 B）。</p>
 * <p>可选 {@link ChatClientAdvisorProvider} + {@link MemoryMode}，透传
 * {@link ChatMemory#CONVERSATION_ID}。</p>
 */
public class StreamingLlmTemplate {

    private static final Logger log = LoggerFactory.getLogger(StreamingLlmTemplate.class);

    private final PromptRenderer promptRenderer;
    private final ModelEndpointResolver endpointResolver;
    private final ChatModelFactory chatModelFactory;
    private final GraphStreamBridge streamBridge;
    private final SkillCatalogResolver skillCatalog;
    private final SkillContentLoader skillContent;
    private final SkillResourceLoader skillResources;
    private final MediaRefResolver mediaResolver;
    private final PromptContentResolver promptContent;
    private final ChatClientAdvisorProvider advisorProvider;
    private final LlmChatOptionsCustomizer optionsCustomizer;
    /** 可选：业务侧决定记忆 USER 展示正文从哪些 state key 取 */
    private final MemoryDisplayUserTextResolver memoryDisplayUserTextResolver;
    /** 流式+工具手动多轮上限，默认 30；可用 {@code ace.graph.dsl.llm.stream-tool-max-rounds} 配置 */
    private int streamToolMaxRounds = 30;

    /** P3.5 / P3.8：由 {@link LlmResolvers} 单点注入 */
    public StreamingLlmTemplate(LlmResolvers resolvers, GraphStreamBridge streamBridge) {
        this(resolvers, streamBridge, null);
    }

    public StreamingLlmTemplate(LlmResolvers resolvers,
                                GraphStreamBridge streamBridge,
                                MemoryDisplayUserTextResolver memoryDisplayUserTextResolver) {
        this(Objects.requireNonNull(resolvers, "resolvers").promptRenderer(),
                resolvers.modelEndpoints(),
                resolvers.chatModels(),
                streamBridge,
                resolvers.skillCatalog(),
                resolvers.skillContent(),
                resolvers.skillResources(),
                resolvers.media(),
                resolvers.prompts(),
                resolvers.advisorProvider(),
                resolvers.optionsCustomizer(),
                memoryDisplayUserTextResolver);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                null, null, null, null, null, null, null, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                skillCatalog, skillContent, skillResources, null, null, null, null, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources,
                                MediaRefResolver mediaResolver) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                skillCatalog, skillContent, skillResources, mediaResolver, null, null, null, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources,
                                MediaRefResolver mediaResolver,
                                PromptContentResolver promptContent) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                skillCatalog, skillContent, skillResources, mediaResolver, promptContent, null, null, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources,
                                MediaRefResolver mediaResolver,
                                PromptContentResolver promptContent,
                                ChatClientAdvisorProvider advisorProvider) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                skillCatalog, skillContent, skillResources, mediaResolver, promptContent, advisorProvider, null, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources,
                                MediaRefResolver mediaResolver,
                                PromptContentResolver promptContent,
                                ChatClientAdvisorProvider advisorProvider,
                                LlmChatOptionsCustomizer optionsCustomizer) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                skillCatalog, skillContent, skillResources, mediaResolver, promptContent,
                advisorProvider, optionsCustomizer, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources,
                                MediaRefResolver mediaResolver,
                                PromptContentResolver promptContent,
                                ChatClientAdvisorProvider advisorProvider,
                                LlmChatOptionsCustomizer optionsCustomizer,
                                MemoryDisplayUserTextResolver memoryDisplayUserTextResolver) {
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
        this.chatModelFactory = Objects.requireNonNull(chatModelFactory, "chatModelFactory");
        this.streamBridge = streamBridge != null ? streamBridge : GraphStreamBridge.NOOP;
        this.skillCatalog = skillCatalog;
        this.skillContent = skillContent;
        this.skillResources = skillResources;
        this.mediaResolver = mediaResolver != null ? mediaResolver : new DefaultMediaRefResolver();
        this.promptContent = promptContent;
        this.advisorProvider = advisorProvider;
        this.optionsCustomizer = optionsCustomizer;
        this.memoryDisplayUserTextResolver = memoryDisplayUserTextResolver;
    }

    /**
     * 设置流式+工具多轮上限（默认 30）。&lt;=0 回退 30。
     */
    public void setStreamToolMaxRounds(int streamToolMaxRounds) {
        this.streamToolMaxRounds = streamToolMaxRounds > 0 ? streamToolMaxRounds : 30;
    }

    public int getStreamToolMaxRounds() {
        return streamToolMaxRounds;
    }

    public Map<String, Object> execute(LlmCallRequest req) {
        Objects.requireNonNull(req, "LlmCallRequest");
        LlmRequestContext ctx = req.context();
        long start = System.nanoTime();
        if (ctx.agentCode() == null || ctx.agentCode().isBlank()) {
            log.error("节点 {} 缺少 agentCode（保留键 {}），请在执行入口写入",
                    ctx.nodeId(), LlmRequestContext.ACE_AGENT_CODE_KEY);
        }

        String systemRaw = assembleSystemTemplate(req);
        String system = promptRenderer.render(systemRaw, req.variables(), ctx.nodeId());
        String user = promptRenderer.render(
                nullToEmpty(req.userMessage()), req.variables(), ctx.nodeId());

        ResourceBinding binding = ctx.binding();
        Set<String> activated = new LinkedHashSet<>();
        List<NamedToolCallback> allNamed = new ArrayList<>();
        if (req.tools() != null) {
            allNamed.addAll(req.tools());
        }

        List<ForceSkillActivator.ActivatedSkill> forced = List.of();
        if (binding != null && binding.enableSkill()) {
            // 口令/点选 forceSkills 并入本 run 有效白名单，避免「解析到却因未勾选静默跳过」
            List<String> effectiveSkillKeys = ForceSkills.effectiveSkillKeys(
                    ctx.state(), ctx.nodeId(), binding.skillKeys());
            if (!effectiveSkillKeys.isEmpty()) {
                List<SkillDescriptor> skills = skillCatalog == null
                        ? List.of()
                        : skillCatalog.resolve(ctx, effectiveSkillKeys);
                String l1 = SkillL1Catalog.format(skills);
                if (!l1.isEmpty()) {
                    system = system + l1;
                    log.info("节点 {} 已追加 Skill L1 目录: {} 项（effectiveKeys={}）",
                            ctx.nodeId(), skills.size(), effectiveSkillKeys.size());
                }
                forced = ForceSkillActivator.activate(ctx, skillContent, activated, effectiveSkillKeys);
                if (skillContent != null) {
                    allNamed.addAll(SkillTools.builtin(ctx, effectiveSkillKeys,
                            skillContent, skillResources, activated));
                } else {
                    log.warn("节点 {} enableSkill 但无 SkillContentLoader，跳过内置 skill 工具", ctx.nodeId());
                }
            }
        }

        MediaRefResolver.ResolveResult mediaResult = resolveMedia(ctx, req.mediaInputKey());
        if (!mediaResult.materialNotes().isEmpty() || !mediaResult.skippedNotes().isEmpty()) {
            StringBuilder footnote = new StringBuilder(user);
            for (String note : mediaResult.materialNotes()) {
                footnote.append('\n').append(note);
            }
            for (String note : mediaResult.skippedNotes()) {
                footnote.append('\n').append(note);
            }
            user = footnote.toString();
        }

        List<ToolCallback> modelTools = resolveModelTools(allNamed, ctx.nodeId(), req.conflictPolicy());

        ModelEndpoint endpoint = endpointResolver.resolve(
                ctx,
                req.inlineModel() != null ? req.inlineModel() : new InlineModel(null, null, false, null),
                req.modelOverride());
        ChatModel model = chatModelFactory.create(endpoint);
        if (model == null) {
            throw new IllegalStateException("ChatModelFactory 返回 null, nodeId=" + ctx.nodeId());
        }
        log.info("节点 {} 开始 LLM 调用(ChatClient): streaming={}, kind={}, modelId={}, tools={}, forcedSkills={}, medias={}, materials={}, memoryMode={}, conversationId={}",
                ctx.nodeId(), req.streaming(), req.streamResponseKind(), endpoint.modelId(),
                modelTools.size(), forced.size(), mediaResult.medias().size(),
                mediaResult.materialNotes().size(),
                req.memoryMode(), ctx.conversationId());

        List<Message> messages = buildMessages(system, user, forced, mediaResult.medias(),
                resolveMemoryDisplayUserText(ctx, req.variables(), user));
        String full;
        boolean wantStream = req.streaming() && ctx.runId() != null && !ctx.runId().isBlank();
        if (wantStream && modelTools.isEmpty()) {
            full = streamCall(model, messages, modelTools, req, ctx, req.streamResponseKind());
        } else if (wantStream) {
            log.info("节点 {} 流式+工具：ChatClient.stream().chatResponse() 真流式（通道 A）", ctx.nodeId());
            full = streamCallWithTools(model, messages, modelTools, req, ctx, req.streamResponseKind());
        } else {
            full = syncCall(model, messages, modelTools, req);
            log.info("节点 {} 同步调用完成: chars={}, kind={}, elapsedMs={}",
                    ctx.nodeId(), full.length(), req.streamResponseKind(),
                    (System.nanoTime() - start) / 1_000_000);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(req.outputKey(), full);
        return out;
    }

    /**
     * 组装 system：promptKeys（经 {@link PromptContentResolver}）+ 节点内联追加（req.systemTemplate）。
     */
    private String assembleSystemTemplate(LlmCallRequest req) {
        LlmRequestContext ctx = req.context();
        ResourceBinding binding = ctx.binding();
        StringBuilder sb = new StringBuilder();
        if (binding != null && (binding.enablePrompt() || !binding.promptKeys().isEmpty())) {
            if (!binding.promptKeys().isEmpty()) {
                if (promptContent == null) {
                    log.error("节点 {} 配置了 promptKeys 但无 PromptContentResolver", ctx.nodeId());
                    throw new IllegalStateException(
                            "promptKeys 已配置但 PromptContentResolver 缺失, nodeId=" + ctx.nodeId());
                }
                String fromKeys = promptContent.resolve(ctx, binding.promptKeys());
                if (fromKeys != null && !fromKeys.isBlank()) {
                    sb.append(fromKeys.trim());
                    log.info("节点 {} 已合并 promptKeys 段, chars={}", ctx.nodeId(), fromKeys.length());
                }
            }
        }
        String inline = nullToEmpty(req.systemTemplate()).trim();
        if (!inline.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(inline);
        }
        if (sb.isEmpty()) {
            sb.append("（未配置 prompt）");
        }
        return sb.toString();
    }

    private MediaRefResolver.ResolveResult resolveMedia(LlmRequestContext ctx, String mediaInputKey) {
        List<MediaRef> refs = MediaRefs.readFrom(ctx.state(), mediaInputKey);
        if (refs.isEmpty()) {
            return MediaRefResolver.ResolveResult.empty();
        }
        return mediaResolver.resolve(ctx, refs);
    }

    private static List<ToolCallback> resolveModelTools(List<NamedToolCallback> named, String nodeId,
                                                         ToolConflictPolicy policy) {
        if (named == null || named.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> callbacks = ToolDeduper.toModelCallbacks(named, policy);
        log.info("节点 {} 工具挂载完成: named={}, modelCallbacks={}, conflictPolicy={}",
                nodeId, named.size(), callbacks.size(),
                policy == null ? ToolDeduper.DEFAULT_POLICY : policy);
        return callbacks;
    }

    /**
     * sync / stream 共用：挂 ToolCallAdvisor + 业务记忆 Advisor，并透传 CONVERSATION_ID。
     */
    private ChatClient.ChatClientRequestSpec prepareSpec(ChatModel model,
                                                         List<Message> messages,
                                                         List<ToolCallback> tools,
                                                         LlmCallRequest req) {
        LlmRequestContext ctx = req.context();
        MemoryMode mode = req.memoryMode() == null ? MemoryMode.NONE : req.memoryMode();
        boolean hasTools = tools != null && !tools.isEmpty();

        ChatClient.Builder builder = ChatClient.builder(model);
        List<Advisor> defaults = new ArrayList<>();
        if (hasTools) {
            ToolCallingManager merging = new StreamingToolCallMergingManager(
                    DefaultToolCallingManager.builder().build());
            // Spring AI 1.1.x ToolCallAdvisor.adviseStream 未实现；仅同步 call 路径挂 Advisor。
            // 流式+工具走 streamCallWithTools 手动多轮（见该方法）。
            if (!req.streaming()) {
                defaults.add(ToolCallAdvisor.builder()
                        .toolCallingManager(merging)
                        .build());
                log.info("节点 {} ChatClient 已挂载 ToolCallAdvisor+Merging，工具数={}", ctx.nodeId(), tools.size());
            }
            else {
                log.info("节点 {} 流式+工具：跳过 ToolCallAdvisor（adviseStream 未实现），改手动多轮+Merging",
                        ctx.nodeId());
            }
        }

        ChatClientAdvisorBundle mem = ChatClientAdvisorBundle.empty();
        // 流式+工具手动多轮：中间轮不挂记忆；终答后由 persistMemoryAfterToolStream echo 落盘。
        boolean allowMemory = !(hasTools && req.streaming());
        if (allowMemory
                && mode != MemoryMode.NONE
                && ctx.conversationId() != null && !ctx.conversationId().isBlank()
                && advisorProvider != null) {
            mem = advisorProvider.provide(new ChatClientAdvisorRequest(
                    ctx, mode, req.streaming(), hasTools));
            if (mem == null) {
                mem = ChatClientAdvisorBundle.empty();
            }
            if (!mem.advisors().isEmpty()) {
                defaults.addAll(mem.advisors());
            }
            log.info("节点 {} 记忆 Advisor 挂载: mode={}, conversationId={}, advisors={}",
                    ctx.nodeId(), mode, ctx.conversationId(), mem.advisors().size());
        } else {
            log.info("节点 {} 跳过记忆 Advisor: mode={}, conversationIdBlank={}, providerNull={}, streamWithTools={}",
                    ctx.nodeId(), mode,
                    ctx.conversationId() == null || ctx.conversationId().isBlank(),
                    advisorProvider == null,
                    hasTools && req.streaming());
        }

        if (!defaults.isEmpty()) {
            builder.defaultAdvisors(defaults.toArray(Advisor[]::new));
        }

        ChatClient.ChatClientRequestSpec spec = builder.build().prompt().messages(messages);

        Map<String, Object> params = new LinkedHashMap<>();
        if (mem.advisorParams() != null && !mem.advisorParams().isEmpty()) {
            params.putAll(mem.advisorParams());
        }
        // 框架 conversationId 后写，避免被业务 advisorParams 覆盖
        if (ctx.conversationId() != null && !ctx.conversationId().isBlank()) {
            params.put(ChatMemory.CONVERSATION_ID, ctx.conversationId());
        }
        if (!params.isEmpty()) {
            Map<String, Object> finalParams = params;
            spec = spec.advisors(a -> a.params(finalParams));
        }

        if (hasTools) {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();
            ChatOptions finalOptions = applyOptionsCustomizer(options, req);
            spec = spec.toolCallbacks(tools).options(finalOptions);
        }
        else {
            ChatOptions customized = applyOptionsCustomizer(null, req);
            if (customized != null) {
                spec = spec.options(customized);
            }
        }
        return spec;
    }

    private ChatOptions applyOptionsCustomizer(ChatOptions base, LlmCallRequest req) {
        if (optionsCustomizer == null) {
            return base;
        }
        try {
            ChatOptions out = optionsCustomizer.customize(base, req);
            if (out != null) {
                log.info("节点 {} ChatOptions 已由 LlmChatOptionsCustomizer 定制: deepThinking={}, type={}",
                        req.context().nodeId(), req.deepThinking(), out.getClass().getSimpleName());
                return out;
            }
        }
        catch (RuntimeException ex) {
            log.warn("节点 {} LlmChatOptionsCustomizer 失败，沿用原 Options: {}",
                    req.context().nodeId(), ex.toString());
        }
        return base;
    }

    private String syncCall(ChatModel model, List<Message> messages,
                            List<ToolCallback> tools, LlmCallRequest req) {
        ChatClient.ChatClientRequestSpec spec = prepareSpec(model, messages, tools, req);
        String content = spec.call().content();
        log.info("节点 {} ChatClient.call 返回 chars={}",
                req.context().nodeId(), content == null ? 0 : content.length());
        return nullToEmpty(content);
    }

    private String streamCall(ChatModel model, List<Message> messages,
                              List<ToolCallback> tools, LlmCallRequest req,
                              LlmRequestContext ctx, String kind) {
        StringBuilder sb = new StringBuilder();
        ChatClient.ChatClientRequestSpec spec = prepareSpec(model, messages, tools, req);
        try {
            spec.stream()
                    .content()
                    .doOnNext(tok -> {
                        if (tok != null && !tok.isEmpty()) {
                            sb.append(tok);
                            emitStreamingToken(ctx, tok, kind, req.streamAttrs());
                        }
                    })
                    .blockLast();
        } finally {
            emitFinished(ctx, kind, req.streamAttrs());
            log.info("节点 {} 流式调用完成: chars={}, kind={}", ctx.nodeId(), sb.length(), kind);
        }
        return sb.toString();
    }

    /**
     * 有工具真流式（通道 A）。
     *
     * <p>Spring AI 1.1.x {@link ToolCallAdvisor#adviseStream} 未实现，故不挂 Advisor；
     * 在 Template 内手动：stream 一轮 → 若有 toolCalls 则 Merging 执行并回灌 → 再 stream，
     * 直至无 toolCalls；仅无 toolCalls 的文本进入 visible / bridge。</p>
     */
    private String streamCallWithTools(ChatModel model, List<Message> messages,
                                       List<ToolCallback> tools, LlmCallRequest req,
                                       LlmRequestContext ctx, String kind) {
        ToolCallingManager toolManager = new StreamingToolCallMergingManager(
                DefaultToolCallingManager.builder().build());
        List<Message> conversation = new ArrayList<>(messages);
        StringBuilder visible = new StringBuilder();
        Map<String, Object> attrs = req.streamAttrs();
        final int maxRounds = streamToolMaxRounds;
        try {
            for (int round = 0; round < maxRounds; round++) {
                List<ChatResponse> frames = new ArrayList<>();
                ChatClient.ChatClientRequestSpec spec = prepareSpec(model, conversation, tools, req);
                spec.stream()
                        .chatResponse()
                        .doOnNext(cr -> {
                            if (cr == null) {
                                return;
                            }
                            frames.add(cr);
                            if (cr.hasToolCalls()) {
                                return;
                            }
                            String tok = extractAssistantTextDelta(cr);
                            if (tok != null && !tok.isEmpty()) {
                                visible.append(tok);
                                emitStreamingToken(ctx, tok, kind, attrs);
                            }
                        })
                        .blockLast();

                ChatResponse toolRound = lastWithToolCalls(frames);
                if (toolRound == null) {
                    break;
                }
                ToolCallingChatOptions opts = ToolCallingChatOptions.builder()
                        .toolCallbacks(tools)
                        .internalToolExecutionEnabled(false)
                        .build();
                ChatOptions finalOpts = applyOptionsCustomizer(opts, req);
                ToolCallingChatOptions toolOpts = opts;
                if (finalOpts instanceof ToolCallingChatOptions customized) {
                    // 流式手动多轮必须关闭内置执行，避免与 Template 循环重复跑工具
                    toolOpts = ToolCallingChatOptions.builder()
                            .toolCallbacks(customized.getToolCallbacks() != null
                                    ? customized.getToolCallbacks() : tools)
                            .toolNames(customized.getToolNames())
                            .toolContext(customized.getToolContext())
                            .internalToolExecutionEnabled(false)
                            .build();
                }
                Prompt prompt = new Prompt(conversation, toolOpts);
                var result = toolManager.executeToolCalls(prompt, toolRound);
                conversation = new ArrayList<>(result.conversationHistory());
                log.info("节点 {} 流式工具轮完成: round={}, historyMsgs={}",
                        ctx.nodeId(), round + 1, conversation.size());
            }
            // 终答已推完：用 Echo ChatModel + 记忆 Advisor 走一遍 call，落 USER/ASSISTANT（含 thinking drain）
            persistMemoryAfterToolStream(messages, visible.toString(), req);
        } finally {
            emitFinished(ctx, kind, attrs);
            log.info("节点 {} 流式+工具完成: chars={}, kind={}", ctx.nodeId(), visible.length(), kind);
        }
        return visible.toString();
    }

    /**
     * 流式+工具多轮不挂记忆 Advisor；终答后用 Echo 模型触发 Advisor before/after 落盘，
     * 不二次调用真实 LLM。
     */
    private void persistMemoryAfterToolStream(List<Message> seedMessages,
                                              String assistantText,
                                              LlmCallRequest req) {
        MemoryMode mode = req.memoryMode() == null ? MemoryMode.NONE : req.memoryMode();
        if (mode == MemoryMode.NONE || advisorProvider == null) {
            return;
        }
        LlmRequestContext ctx = req.context();
        if (ctx.conversationId() == null || ctx.conversationId().isBlank()) {
            return;
        }
        try {
            LlmCallRequest echoReq = LlmCallRequest.builder()
                    .context(ctx)
                    .systemTemplate(req.systemTemplate())
                    .userMessage(req.userMessage())
                    .variables(req.variables())
                    .outputKey(req.outputKey())
                    .streaming(false)
                    .streamResponseKind(req.streamResponseKind())
                    .inlineModel(req.inlineModel())
                    .modelOverride(req.modelOverride())
                    .tools(List.of())
                    .mediaInputKey(req.mediaInputKey())
                    .conflictPolicy(req.conflictPolicy())
                    .memoryMode(mode)
                    .deepThinking(req.deepThinking())
                    .streamAttrs(req.streamAttrs())
                    .build();
            ChatModel echo = new EchoAssistantChatModel(assistantText == null ? "" : assistantText);
            ChatClient.ChatClientRequestSpec spec = prepareSpec(echo, seedMessages, List.of(), echoReq);
            spec.call().content();
            log.info("节点 {} 流式+工具终答记忆已 echo 落盘: chars={}", ctx.nodeId(),
                    assistantText == null ? 0 : assistantText.length());
        }
        catch (RuntimeException ex) {
            log.warn("节点 {} 流式+工具记忆 echo 落盘失败: {}", ctx.nodeId(), ex.toString());
        }
    }

    /** 仅回放既定 ASSISTANT 正文，供记忆 Advisor 落盘，不访问远端模型。 */
    private static final class EchoAssistantChatModel implements ChatModel {
        private final String text;

        EchoAssistantChatModel(String text) {
            this.text = text == null ? "" : text;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    private static ChatResponse lastWithToolCalls(List<ChatResponse> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        for (int i = frames.size() - 1; i >= 0; i--) {
            ChatResponse cr = frames.get(i);
            if (cr != null && cr.hasToolCalls()) {
                return StreamingToolCallMergingManager.normalizeStreamingToolCalls(cr);
            }
        }
        return null;
    }

    /** 取本帧 assistant 文本增量（流式每帧通常为 delta）。 */
    private static String extractAssistantTextDelta(ChatResponse cr) {
        Generation gen = cr.getResult();
        if (gen == null || gen.getOutput() == null) {
            return null;
        }
        AssistantMessage output = gen.getOutput();
        String text = output.getText();
        return text == null || text.isEmpty() ? null : text;
    }

    private void emitStreamingToken(LlmRequestContext ctx, String tok, String kind, Map<String, Object> attrs) {
        streamBridge.emit(ctx.runId(), new TokenChunk(
                ctx.nodeId(), tok, OutputType.AGENT_MODEL_STREAMING, kind, false, attrs));
    }

    private void emitFinished(LlmRequestContext ctx, String kind, Map<String, Object> attrs) {
        streamBridge.emit(ctx.runId(), new TokenChunk(
                ctx.nodeId(), "", OutputType.AGENT_MODEL_FINISHED, kind, true, attrs));
    }

    /**
     * 记忆 USER 展示正文：委托业务 {@link MemoryDisplayUserTextResolver}；
     * 未注册 SPI 时返回 null（不写 display_content，落库用 LLM user 全文）。
     */
    private String resolveMemoryDisplayUserText(LlmRequestContext ctx,
                                                Map<String, Object> variables,
                                                String llmUserText) {
        if (memoryDisplayUserTextResolver == null) {
            return null;
        }
        try {
            String resolved = memoryDisplayUserTextResolver.resolve(
                    new MemoryDisplayUserTextResolver.MemoryDisplayUserTextRequest(ctx, variables, llmUserText));
            if (resolved != null && !resolved.isBlank()) {
                return resolved.trim();
            }
        } catch (RuntimeException ex) {
            log.warn("节点 {} MemoryDisplayUserTextResolver 失败，跳过 display_content: {}",
                    ctx != null ? ctx.nodeId() : "?", ex.toString());
        }
        return null;
    }

    private static List<Message> buildMessages(String system, String user,
                                               List<ForceSkillActivator.ActivatedSkill> forced,
                                               List<Media> medias,
                                               String displayUserText) {
        List<Message> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(new SystemMessage(system));
        }
        if (forced != null) {
            for (ForceSkillActivator.ActivatedSkill s : forced) {
                messages.add(new SystemMessage(
                        "[forceSkills activated] skill=" + s.code() + "\n\n" + s.body()));
            }
        }
        String userText = user == null || user.isBlank() ? " " : user;
        Map<String, Object> meta = new LinkedHashMap<>();
        if (displayUserText != null && !displayUserText.isBlank()
                && !displayUserText.equals(userText)) {
            // 与 lesso LessoChatMemoryExtras.DISPLAY_CONTENT 同名，避免框架依赖 memory 模块
            meta.put("display_content", displayUserText);
        }
        var builder = UserMessage.builder().text(userText);
        if (!meta.isEmpty()) {
            builder.metadata(meta);
        }
        if (medias != null && !medias.isEmpty()) {
            builder.media(medias);
        }
        messages.add(builder.build());
        return messages;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
