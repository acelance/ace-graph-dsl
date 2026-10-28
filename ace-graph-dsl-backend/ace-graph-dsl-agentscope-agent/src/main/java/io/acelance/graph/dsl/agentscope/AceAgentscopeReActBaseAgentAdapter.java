package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.acelance.graph.dsl.ai.media.DefaultMediaRefResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 路径 B / G-MCP / G-STREAM-INNER：将 AS 2.x {@link ReActAgent} 包装为 SAA {@link BaseAgent}。
 *
 * <p>流式走 {@code streamEvents}（非弃用）；TokenChunk 挂父 nodeId/kind/<strong>bizParam attrs</strong>。
 * {@code applyDeepThinking} ∧ state → {@link AgentScopeGenerateOptionsCustomizer} 写<strong>模型</strong>
 * GenerateOptions（与 attrs / 前端思考气泡正交）。</p>
 *
 * <p>多模态：state {@code media_refs} → {@link MediaRefResolver} → ImageBlock / 材料注记（对齐经典 Template）。</p>
 */
public final class AceAgentscopeReActBaseAgentAdapter extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(AceAgentscopeReActBaseAgentAdapter.class);

    private final String instructionTemplate;
    private final String bindingOutputKey;
    private final String parentGraphId;
    private final String parentNodeId;
    private final String agentId;
    private final String baseSysPrompt;
    /** 发布期已解析的固定模型（内联）；走 modelConfigKey 时为 null，运行时再挂载 */
    private final Model model;
    /** model 为空时必填；按执行入口 agentCode 延迟 create */
    private final AgentScopeModelFactory modelFactory;
    private final GenericAgentSpec agentSpec;
    private final AgentScopeToolAssembly toolAssembly;
    private final ReActAgent fixedReActAgent;
    private final GraphStreamBridge streamBridge;
    private final AgentScopeParentStreamContext parentStream;
    private final AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer;
    /** 多模态解析；可空则用 DefaultMediaRefResolver */
    private final MediaRefResolver mediaResolver;
    private volatile Model cachedRuntimeModel;
    private volatile String cachedRuntimeAgentCode;

    /**
     * 生产路径（G-MCP + G-STREAM-INNER）。
     *
     * @param model        可空：内联模型在发布期传入；modelConfigKey 路径传 null，由 {@code modelFactory} 运行时解析
     * @param modelFactory {@code model == null} 时必填
     * @param mediaResolver 可空；宿主应注入 LessoMediaRefResolver（内网 base64）
     */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              Model model,
                                              String baseSysPrompt,
                                              GenericAgentSpec agentSpec,
                                              AgentScopeToolAssembly toolAssembly,
                                              String parentGraphId,
                                              String parentNodeId,
                                              String agentId,
                                              GraphStreamBridge streamBridge,
                                              AgentScopeParentStreamContext parentStream,
                                              AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer,
                                              AgentScopeModelFactory modelFactory,
                                              MediaRefResolver mediaResolver) {
        super(name, description, true, false, bindingOutputKey, new ReplaceStrategy());
        if (model == null && modelFactory == null) {
            throw new IllegalArgumentException("AGENTSCOPE model 与 modelFactory 不能同时为空");
        }
        this.model = model;
        this.modelFactory = modelFactory;
        this.baseSysPrompt = StringUtils.hasText(baseSysPrompt) ? baseSysPrompt.trim() : "You are a helpful assistant.";
        this.agentSpec = Objects.requireNonNull(agentSpec, "agentSpec");
        this.toolAssembly = toolAssembly != null ? toolAssembly : AgentScopeToolAssembly.noop();
        this.fixedReActAgent = null;
        this.instructionTemplate = instructionTemplate;
        this.bindingOutputKey = Objects.requireNonNull(bindingOutputKey, "bindingOutputKey");
        this.parentGraphId = parentGraphId;
        this.parentNodeId = parentNodeId;
        this.agentId = agentId;
        this.streamBridge = streamBridge != null ? streamBridge : GraphStreamBridge.NOOP;
        this.parentStream = parentStream != null ? parentStream : AgentScopeParentStreamContext.EMPTY;
        this.generateOptionsCustomizer = generateOptionsCustomizer;
        this.mediaResolver = mediaResolver != null ? mediaResolver : new DefaultMediaRefResolver();
    }

    /**
     * 生产路径兼容：无显式 MediaRefResolver（用框架默认）。
     */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              Model model,
                                              String baseSysPrompt,
                                              GenericAgentSpec agentSpec,
                                              AgentScopeToolAssembly toolAssembly,
                                              String parentGraphId,
                                              String parentNodeId,
                                              String agentId,
                                              GraphStreamBridge streamBridge,
                                              AgentScopeParentStreamContext parentStream,
                                              AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer,
                                              AgentScopeModelFactory modelFactory) {
        this(name, description, bindingOutputKey, instructionTemplate, model, baseSysPrompt, agentSpec,
                toolAssembly, parentGraphId, parentNodeId, agentId, streamBridge, parentStream,
                generateOptionsCustomizer, modelFactory, null);
    }

    /**
     * 生产路径：已在发布期解析好的固定 {@link Model}（内联）。
     */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              Model model,
                                              String baseSysPrompt,
                                              GenericAgentSpec agentSpec,
                                              AgentScopeToolAssembly toolAssembly,
                                              String parentGraphId,
                                              String parentNodeId,
                                              String agentId,
                                              GraphStreamBridge streamBridge,
                                              AgentScopeParentStreamContext parentStream,
                                              AgentScopeGenerateOptionsCustomizer generateOptionsCustomizer) {
        this(name, description, bindingOutputKey, instructionTemplate,
                Objects.requireNonNull(model, "model"), baseSysPrompt, agentSpec, toolAssembly,
                parentGraphId, parentNodeId, agentId, streamBridge, parentStream,
                generateOptionsCustomizer, null);
    }

    /** 兼容：无 GenerateOptions 定制器。 */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              Model model,
                                              String baseSysPrompt,
                                              GenericAgentSpec agentSpec,
                                              AgentScopeToolAssembly toolAssembly,
                                              String parentGraphId,
                                              String parentNodeId,
                                              String agentId,
                                              GraphStreamBridge streamBridge,
                                              AgentScopeParentStreamContext parentStream) {
        this(name, description, bindingOutputKey, instructionTemplate, model, baseSysPrompt, agentSpec,
                toolAssembly, parentGraphId, parentNodeId, agentId, streamBridge, parentStream, null);
    }

    /** 兼容旧生产构造。 */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              Model model,
                                              String baseSysPrompt,
                                              GenericAgentSpec agentSpec,
                                              AgentScopeToolAssembly toolAssembly,
                                              String parentGraphId,
                                              String parentNodeId,
                                              String agentId) {
        this(name, description, bindingOutputKey, instructionTemplate, model, baseSysPrompt, agentSpec,
                toolAssembly, parentGraphId, parentNodeId, agentId,
                GraphStreamBridge.NOOP, AgentScopeParentStreamContext.EMPTY, null);
    }

    /** 无工具单测路径。 */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              ReActAgent reActAgent,
                                              String parentGraphId,
                                              String parentNodeId) {
        this(name, description, bindingOutputKey, instructionTemplate, reActAgent,
                parentGraphId, parentNodeId, GraphStreamBridge.NOOP, AgentScopeParentStreamContext.EMPTY);
    }

    /** 单测：预构建 Agent + bridge。 */
    public AceAgentscopeReActBaseAgentAdapter(String name,
                                              String description,
                                              String bindingOutputKey,
                                              String instructionTemplate,
                                              ReActAgent reActAgent,
                                              String parentGraphId,
                                              String parentNodeId,
                                              GraphStreamBridge streamBridge,
                                              AgentScopeParentStreamContext parentStream) {
        super(name, description, true, false, bindingOutputKey, new ReplaceStrategy());
        this.fixedReActAgent = Objects.requireNonNull(reActAgent, "reActAgent");
        this.model = null;
        this.modelFactory = null;
        this.baseSysPrompt = null;
        this.agentSpec = null;
        this.toolAssembly = null;
        this.instructionTemplate = instructionTemplate;
        this.bindingOutputKey = Objects.requireNonNull(bindingOutputKey, "bindingOutputKey");
        this.parentGraphId = parentGraphId;
        this.parentNodeId = parentNodeId;
        this.agentId = null;
        this.streamBridge = streamBridge != null ? streamBridge : GraphStreamBridge.NOOP;
        this.parentStream = parentStream != null ? parentStream : AgentScopeParentStreamContext.EMPTY;
        this.generateOptionsCustomizer = null;
        this.mediaResolver = null;
    }

    @Override
    public Node asNode(boolean includeContents, boolean returnReasoningContents) {
        NodeActionWithConfig action = (state, config) -> invokeReAct(state);
        return new Node(name(), compileConfig -> AsyncNodeActionWithConfig.node_async(action));
    }

    @Override
    protected StateGraph initGraph() throws GraphStateException {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put(bindingOutputKey, new ReplaceStrategy());
        StateGraph graph = new StateGraph(() -> strategies);
        graph.addNode(name(), node_async(this::invokeReAct));
        graph.addEdge(START, name());
        graph.addEdge(name(), END);
        return graph;
    }

    private Map<String, Object> invokeReAct(OverAllState state) {
        long start = System.nanoTime();
        log.info("AGENTSCOPE 子 Agent 开始, bridge={}, graphId={}, parentNodeId={}, subName={}, agentId={}, outputKey={}",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(), agentId,
                bindingOutputKey);
        try {
            String rendered = AgentscopeInstructionTemplate.render(instructionTemplate, state);
            if (!StringUtils.hasText(rendered)) {
                rendered = readFallbackInput(state);
            }
            log.info("AGENTSCOPE 子 Agent 输入就绪, graphId={}, parentNodeId={}, subName={}, inputLen={}",
                    parentGraphId, parentNodeId, name(), rendered != null ? rendered.length() : 0);

            boolean deepThinking = resolveDeepThinking(state);
            ResolvedReAct resolved = resolveReActAgent(state, deepThinking);
            String runId = readRunId(state);
            Msg userMsg = buildUserMsgWithMedia(state, rendered, runId);
            boolean wantStream = AgentScopeStreamSupport.canStream(streamBridge, runId);
            String text;
            if (wantStream) {
                log.info("AGENTSCOPE 开启内层流式(streamEvents), bridge={}, graphId={}, parentNodeId={}, subName={}, "
                                + "runId={}, responseKind={}, attrsKeys={}, hasTools={}, deepThinking={}",
                        AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(), runId,
                        parentStream.streamResponseKind(), parentStream.streamAttrs().keySet(),
                        resolved.hasTools(), deepThinking);
                text = invokeStreaming(resolved.agent(), userMsg, runId, resolved.hasTools());
            } else {
                log.warn("AGENTSCOPE 内层流式降级为整段写回, bridge={}, graphId={}, parentNodeId={}, subName={}, "
                                + "runIdBlank={}, bridgeNoop={}, deepThinking={}",
                        AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(),
                        !StringUtils.hasText(runId), streamBridge == GraphStreamBridge.NOOP, deepThinking);
                text = invokeBlocking(resolved.agent(), userMsg);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put(bindingOutputKey, text != null ? text : "");
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.info("AGENTSCOPE 子 Agent 成功, bridge={}, graphId={}, parentNodeId={}, subName={}, outputKey={}, "
                            + "costMs={}, replyLen={}, streamed={}",
                    AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(),
                    bindingOutputKey, costMs, text != null ? text.length() : 0, wantStream);
            return out;
        } catch (Exception ex) {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.error("AGENTSCOPE 子 Agent 失败, bridge={}, graphId={}, parentNodeId={}, subName={}, outputKey={}, "
                            + "costMs={}, err={}",
                    AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(),
                    bindingOutputKey, costMs, ex.toString(), ex);
            throw ex instanceof RuntimeException re ? re : new IllegalStateException(
                    "AGENTSCOPE 子 Agent 执行失败: " + name() + ", graphId=" + parentGraphId, ex);
        }
    }

    /**
     * {@code ReActAgent.streamEvents} → bridge emit（AS 2.0.4 非弃用 API）。
     */
    private String invokeStreaming(ReActAgent agent, Msg userMsg, String runId, boolean hasTools) {
        String kind = parentStream.streamResponseKind();
        Map<String, Object> attrs = parentStream.streamAttrs();
        AgentScopeVisibleStreamSession session = new AgentScopeVisibleStreamSession(
                parentGraphId, parentNodeId, name(), hasTools);
        Consumer<String> emit = delta -> AgentScopeStreamSupport.emitStreaming(
                streamBridge, runId, parentNodeId, kind, attrs, delta);

        try {
            agent.streamEvents(List.of(userMsg))
                    .doOnNext(event -> session.accept(event, emit))
                    .blockLast();

            int flushed = session.finish(emit);
            AgentScopeStreamSupport.emitFinished(streamBridge, runId, parentNodeId, kind, attrs);
            String text = session.aggregatedText();
            log.info("AGENTSCOPE 流式完成, graphId={}, parentNodeId={}, subName={}, emitCount={}, replyLen={}, "
                            + "hasTools={}, flushedLen={}",
                    parentGraphId, parentNodeId, name(), session.emitCount(), text.length(),
                    hasTools, flushed);
            return text;
        } catch (Exception streamEx) {
            log.warn("AGENTSCOPE 内层 streamEvents 失败，降级 call 写 outputKey, bridge={}, graphId={}, "
                            + "parentNodeId={}, subName={}, runId={}, priorEmitCount={}, err={}",
                    AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, parentGraphId, parentNodeId, name(), runId,
                    session.emitCount(), streamEx.toString(), streamEx);
            String text = invokeBlocking(agent, userMsg);
            if (session.emitCount() > 0) {
                AgentScopeStreamSupport.emitFinished(streamBridge, runId, parentNodeId, kind, attrs);
            }
            log.info("AGENTSCOPE streamEvents→call 降级完成, graphId={}, parentNodeId={}, subName={}, replyLen={}",
                    parentGraphId, parentNodeId, name(), text.length());
            return text;
        }
    }

    private String invokeBlocking(ReActAgent agent, Msg userMsg) {
        Msg reply = agent.call(List.of(userMsg)).block();
        String text = reply != null ? reply.getTextContent() : null;
        return text != null ? text : "";
    }

    private ResolvedReAct resolveReActAgent(OverAllState state, boolean deepThinking) {
        if (fixedReActAgent != null) {
            return new ResolvedReAct(fixedReActAgent, false);
        }
        AgentScopeToolAssemblyResult assembled = toolAssembly.assemble(
                parentGraphId, parentNodeId, agentId, agentSpec, state);
        String sysPrompt = baseSysPrompt;
        if (StringUtils.hasText(assembled.sysPromptSuffix())) {
            sysPrompt = baseSysPrompt + assembled.sysPromptSuffix();
        }
        Model resolvedModel = resolveModel(state);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name())
                .description(description())
                .sysPrompt(sysPrompt)
                .model(resolvedModel);
        boolean hasTools = assembled.hasTools();
        if (hasTools) {
            Toolkit toolkit = AceToolCallbackAgentscopeBridge.toToolkit(assembled);
            builder.toolkit(toolkit);
            log.info("AGENTSCOPE ReActAgent 已挂 Toolkit, graphId={}, subName={}, tools={}",
                    parentGraphId, name(), assembled.totalToolCount());
        } else {
            log.info("AGENTSCOPE ReActAgent 无工具（与 1.1.21 无工具路径一致）, graphId={}, subName={}",
                    parentGraphId, name());
        }
        // 模型侧深思：仅 GenerateOptions；与 TokenChunk.attrs（bizParam）无关
        if (generateOptionsCustomizer != null) {
            GenerateOptions options = generateOptionsCustomizer.customize(deepThinking, state);
            if (options != null) {
                builder.generateOptions(options);
                log.info("AGENTSCOPE 已挂 GenerateOptions（模型深思定制）, graphId={}, subName={}, deepThinking={}",
                        parentGraphId, name(), deepThinking);
            } else {
                log.info("AGENTSCOPE GenerateOptionsCustomizer 返回 null（不附加）, graphId={}, subName={}, deepThinking={}",
                        parentGraphId, name(), deepThinking);
            }
        } else if (deepThinking) {
            log.warn("AGENTSCOPE deepThinking=true 但未注册 AgentScopeGenerateOptionsCustomizer，"
                            + "模型侧深思参数未写入（与 GENERIC 需 LlmChatOptionsCustomizer 同理）, "
                            + "graphId={}, subName={}",
                    parentGraphId, name());
        }
        return new ResolvedReAct(builder.build(), hasTools);
    }

    /**
     * 有效深思 =（父 SAA {@code applyDeepThinking} ∨ 子 GenericAgentSpec 同字段）
     * ∧ state {@link LlmRequestContext#ACE_DEEP_THINKING_KEY}。
     * 与 bizParam attrs / 前端思考气泡无关。
     */
    private boolean resolveDeepThinking(OverAllState state) {
        boolean applyGate = parentStream.applyDeepThinking()
                || (agentSpec != null && agentSpec.applyDeepThinking());
        if (!applyGate) {
            return false;
        }
        if (state == null) {
            return false;
        }
        try {
            Object v = state.value(LlmRequestContext.ACE_DEEP_THINKING_KEY).orElse(null);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                return Boolean.parseBoolean(s.trim());
            }
            return false;
        } catch (RuntimeException ex) {
            log.warn("AGENTSCOPE 读取 deepThinking state 失败, graphId={}, subName={}, err={}",
                    parentGraphId, name(), ex.toString());
            return false;
        }
    }

    private record ResolvedReAct(ReActAgent agent, boolean hasTools) {
    }

    /**
     * 内联模型用发布期 {@link #model}；modelConfigKey 用执行入口 agentCode 经 {@link #modelFactory} 挂载。
     */
    private Model resolveModel(OverAllState state) {
        if (model != null) {
            return model;
        }
        if (modelFactory == null) {
            throw new IllegalStateException("AGENTSCOPE 无固定 model 且无 modelFactory, subName=" + name());
        }
        String agentCode = readAgentCode(state);
        if (!StringUtils.hasText(agentCode)) {
            throw new IllegalStateException(
                    "AGENTSCOPE 运行时缺 agentCode，无法按 modelConfigKey 挂载模型: graphId="
                            + parentGraphId + ", subName=" + name()
                            + "（须由 Controller 执行入口写入 ace.graph.dsl.agentCode）");
        }
        String code = agentCode.trim();
        Model cached = cachedRuntimeModel;
        if (cached != null && code.equals(cachedRuntimeAgentCode)) {
            return cached;
        }
        synchronized (this) {
            if (cachedRuntimeModel != null && code.equals(cachedRuntimeAgentCode)) {
                return cachedRuntimeModel;
            }
            log.info("AGENTSCOPE 运行时解析模型, graphId={}, parentNodeId={}, subName={}, agentId={}, agentCode={}",
                    parentGraphId, parentNodeId, name(), agentId, code);
            Model created = modelFactory.create(parentGraphId, parentNodeId, agentId, agentSpec, code);
            cachedRuntimeModel = created;
            cachedRuntimeAgentCode = code;
            return created;
        }
    }

    /**
     * 文本 + media_refs → AS USER Msg（图 ImageBlock，xlsx/pdf 材料注记）。
     * Spec 缺 mediaInputKey 时仍读平台默认 {@code media_refs}。
     */
    private Msg buildUserMsgWithMedia(OverAllState state, String rendered, String runId) {
        String mediaKey = agentSpec != null ? agentSpec.mediaInputKey() : null;
        var binding = agentSpec != null ? ResourceBindings.fromSpec(agentSpec) : null;
        var ctx = AgentScopeMediaMsgSupport.mediaContext(
                readAgentCode(state), parentGraphId,
                parentNodeId != null ? parentNodeId : name(),
                runId, state, binding);
        return AgentScopeMediaMsgSupport.buildUserMsg(
                rendered, state, mediaKey, mediaResolver, ctx);
    }

    private static String readAgentCode(OverAllState state) {
        if (state == null) {
            return null;
        }
        Object v = state.value(LlmRequestContext.ACE_AGENT_CODE_KEY).orElse(null);
        return v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null;
    }

    private static String readRunId(OverAllState state) {
        if (state == null) {
            return null;
        }
        Object v = state.value(ModelOverrideSpec.ACE_RUN_ID_KEY).orElse(null);
        return v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null;
    }

    private static String readFallbackInput(OverAllState state) {
        if (state == null) {
            return "";
        }
        for (String key : List.of("user_query", "input", "query")) {
            Object v = state.value(key).orElse(null);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return "";
    }
}
