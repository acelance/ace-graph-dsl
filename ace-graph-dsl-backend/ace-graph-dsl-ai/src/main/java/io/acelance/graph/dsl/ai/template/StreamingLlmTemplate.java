package io.acelance.graph.dsl.ai.template;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.ai.media.DefaultMediaRefResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.ai.model.ModelEndpoint;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.skill.ForceSkillActivator;
import io.acelance.graph.dsl.ai.skill.SkillTools;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.ai.tool.ToolConflictPolicy;
import io.acelance.graph.dsl.ai.tool.ToolDeduper;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.media.MediaRefs;
import io.acelance.graph.dsl.prompt.PromptContentResolver;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.resource.ResourceBinding;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 流式 LLM 节点模板：prompt / Skill / Media / 工具 / 流式推送。
 *
 * <p>P3.2：经原生 {@link ChatClient} 装配并执行；带工具时由 Spring AI 内部完成
 * tool_call → 执行 → 回灌的多轮闭环。</p>
 */
public class StreamingLlmTemplate {

    private static final Logger log = LoggerFactory.getLogger(StreamingLlmTemplate.class);

    /** 流式推送终稿时的分片大小（字符），避免单包过大 */
    private static final int STREAM_EMIT_CHUNK = 64;

    private final PromptRenderer promptRenderer;
    private final ModelEndpointResolver endpointResolver;
    private final ChatModelFactory chatModelFactory;
    private final GraphStreamBridge streamBridge;
    private final SkillCatalogResolver skillCatalog;
    private final SkillContentLoader skillContent;
    private final SkillResourceLoader skillResources;
    private final MediaRefResolver mediaResolver;
    private final PromptContentResolver promptContent;

    /** P3.5：由 {@link LlmResolvers} 单点注入 */
    public StreamingLlmTemplate(LlmResolvers resolvers, GraphStreamBridge streamBridge) {
        this(Objects.requireNonNull(resolvers, "resolvers").promptRenderer(),
                resolvers.modelEndpoints(),
                resolvers.chatModels(),
                streamBridge,
                resolvers.skillCatalog(),
                resolvers.skillContent(),
                resolvers.skillResources(),
                resolvers.media(),
                resolvers.prompts());
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                null, null, null, null, null);
    }

    public StreamingLlmTemplate(PromptRenderer promptRenderer,
                                ModelEndpointResolver endpointResolver,
                                ChatModelFactory chatModelFactory,
                                GraphStreamBridge streamBridge,
                                SkillCatalogResolver skillCatalog,
                                SkillContentLoader skillContent,
                                SkillResourceLoader skillResources) {
        this(promptRenderer, endpointResolver, chatModelFactory, streamBridge,
                skillCatalog, skillContent, skillResources, null, null);
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
                skillCatalog, skillContent, skillResources, mediaResolver, null);
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
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
        this.chatModelFactory = Objects.requireNonNull(chatModelFactory, "chatModelFactory");
        this.streamBridge = streamBridge != null ? streamBridge : GraphStreamBridge.NOOP;
        this.skillCatalog = skillCatalog;
        this.skillContent = skillContent;
        this.skillResources = skillResources;
        this.mediaResolver = mediaResolver != null ? mediaResolver : new DefaultMediaRefResolver();
        this.promptContent = promptContent;
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
        if (binding != null && binding.enableSkill() && !binding.skillKeys().isEmpty()) {
            List<SkillDescriptor> skills = skillCatalog == null
                    ? List.of()
                    : skillCatalog.resolve(ctx, binding.skillKeys());
            String l1 = SkillL1Catalog.format(skills);
            if (!l1.isEmpty()) {
                system = system + l1;
                log.info("节点 {} 已追加 Skill L1 目录: {} 项", ctx.nodeId(), skills.size());
            }
            forced = ForceSkillActivator.activate(ctx, skillContent, activated);
            if (skillContent != null) {
                allNamed.addAll(SkillTools.builtin(ctx, binding.skillKeys(),
                        skillContent, skillResources, activated));
            } else {
                log.warn("节点 {} enableSkill 但无 SkillContentLoader，跳过内置 skill 工具", ctx.nodeId());
            }
        }

        MediaRefResolver.ResolveResult mediaResult = resolveMedia(ctx, req.mediaInputKey());
        if (!mediaResult.skippedNotes().isEmpty()) {
            StringBuilder footnote = new StringBuilder(user);
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
        log.info("节点 {} 开始 LLM 调用(ChatClient): streaming={}, kind={}, modelId={}, tools={}, forcedSkills={}, medias={}",
                ctx.nodeId(), req.streaming(), req.streamResponseKind(), endpoint.modelId(),
                modelTools.size(), forced.size(), mediaResult.medias().size());

        String full;
        boolean wantStream = req.streaming() && ctx.runId() != null && !ctx.runId().isBlank();
        if (wantStream && modelTools.isEmpty()) {
            // 无工具：真流式
            full = streamCall(model, system, user, forced, mediaResult.medias(), ctx,
                    req.streamResponseKind());
        } else if (wantStream) {
            // 有工具：先走 ChatClient 多轮 call 闭环，再把终稿切片推送（保证 tool-calling 完整）
            log.info("节点 {} 流式+工具：先 ChatClient.call 多轮，再切片推送终稿", ctx.nodeId());
            full = syncCall(model, system, user, forced, mediaResult.medias(), modelTools, ctx.nodeId());
            emitTextChunks(full, ctx, req.streamResponseKind());
        } else {
            full = syncCall(model, system, user, forced, mediaResult.medias(), modelTools, ctx.nodeId());
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
     * 同步调用：ChatClient + toolCallbacks + {@link ToolCallAdvisor} 多轮闭环。
     */
    private String syncCall(ChatModel model, String system, String user,
                            List<ForceSkillActivator.ActivatedSkill> forced,
                            List<Media> medias,
                            List<ToolCallback> tools,
                            String nodeId) {
        ChatClient.Builder builder = ChatClient.builder(model);
        boolean hasTools = tools != null && !tools.isEmpty();
        if (hasTools) {
            // Spring AI 1.1：多轮 tool-calling 由 ToolCallAdvisor 驱动
            builder.defaultAdvisors(ToolCallAdvisor.builder()
                    .toolCallingManager(DefaultToolCallingManager.builder().build())
                    .build());
            log.info("节点 {} ChatClient 已挂载 ToolCallAdvisor，工具数={}", nodeId, tools.size());
        }
        ChatClient client = builder.build();
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .messages(buildMessages(system, user, forced, medias));
        if (hasTools) {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false) // 交给 Advisor，避免与模型内部执行重复
                    .build();
            spec = spec.toolCallbacks(tools).options(options);
        }
        String content = spec.call().content();
        log.info("节点 {} ChatClient.call 返回 chars={}", nodeId, content == null ? 0 : content.length());
        return nullToEmpty(content);
    }

    /**
     * 无工具时的真流式：ChatClient.stream().content()。
     */
    private String streamCall(ChatModel model, String system, String user,
                              List<ForceSkillActivator.ActivatedSkill> forced,
                              List<Media> medias,
                              LlmRequestContext ctx, String kind) {
        StringBuilder sb = new StringBuilder();
        ChatClient client = ChatClient.builder(model).build();
        try {
            client.prompt()
                    .messages(buildMessages(system, user, forced, medias))
                    .stream()
                    .content()
                    .doOnNext(tok -> {
                        if (tok != null && !tok.isEmpty()) {
                            sb.append(tok);
                            streamBridge.emit(ctx.runId(), new TokenChunk(
                                    ctx.nodeId(), tok, OutputType.AGENT_MODEL_STREAMING, kind, false));
                        }
                    })
                    .blockLast();
        } finally {
            streamBridge.emit(ctx.runId(), new TokenChunk(
                    ctx.nodeId(), "", OutputType.AGENT_MODEL_FINISHED, kind, true));
            log.info("节点 {} 流式调用完成: chars={}, kind={}", ctx.nodeId(), sb.length(), kind);
        }
        return sb.toString();
    }

    /** 将终稿按固定块推送（流式+工具路径） */
    private void emitTextChunks(String full, LlmRequestContext ctx, String kind) {
        String text = full == null ? "" : full;
        try {
            for (int i = 0; i < text.length(); i += STREAM_EMIT_CHUNK) {
                int end = Math.min(i + STREAM_EMIT_CHUNK, text.length());
                String tok = text.substring(i, end);
                streamBridge.emit(ctx.runId(), new TokenChunk(
                        ctx.nodeId(), tok, OutputType.AGENT_MODEL_STREAMING, kind, false));
            }
        } finally {
            streamBridge.emit(ctx.runId(), new TokenChunk(
                    ctx.nodeId(), "", OutputType.AGENT_MODEL_FINISHED, kind, true));
            log.info("节点 {} 工具多轮终稿已切片推送: chars={}, kind={}", ctx.nodeId(), text.length(), kind);
        }
    }

    private static List<Message> buildMessages(String system, String user,
                                               List<ForceSkillActivator.ActivatedSkill> forced,
                                               List<Media> medias) {
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
        if (medias != null && !medias.isEmpty()) {
            messages.add(UserMessage.builder().text(userText).media(medias).build());
        } else {
            messages.add(new UserMessage(userText));
        }
        return messages;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
