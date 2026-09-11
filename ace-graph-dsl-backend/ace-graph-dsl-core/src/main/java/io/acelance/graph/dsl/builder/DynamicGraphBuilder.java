package io.acelance.graph.dsl.builder;

import io.acelance.graph.dsl.checkpoint.CheckpointSaverRegistry;
import io.acelance.graph.dsl.definition.CompileConfigDto;
import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphEdge;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.observability.GraphExecutionListener;
import io.acelance.graph.dsl.observability.GraphLifecycleListenerBridge;
import io.acelance.graph.dsl.persistence.DynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredAgentNode;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.acelance.graph.dsl.script.ScriptEdgeActionFactory;
import io.acelance.graph.dsl.script.ScriptNodeFactory;
import io.acelance.graph.dsl.agent.GenericAgentNodeFactory;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.streamkind.StreamResponseKind;
import io.acelance.graph.dsl.streamkind.StreamResponseKindResolver;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.action.AsyncCommandAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.Command;
import com.alibaba.cloud.ai.graph.action.CommandAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.MergeStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import java.util.Set;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 动态图构建器：将 {@link GraphDefinition} DSL 编译为 {@link CompiledGraph}。
 */
@Component
public class DynamicGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DynamicGraphBuilder.class);

    /** 子图最大嵌套深度（与 GraphValidator.MAX_SUBGRAPH_DEPTH 保持一致） */
    static final int MAX_SUBGRAPH_DEPTH = 3;

    private final GraphNodeRegistry nodeRegistry;
    private final EdgeDispatcherRegistry dispatcherRegistry;
    private final GraphValidator validator;
    private final ApplicationContext applicationContext;
    private final CheckpointSaverRegistry saverRegistry;
    private final ScriptEdgeActionFactory scriptEdgeActionFactory;
    private final List<GraphExecutionListener> executionListeners;
    private final DynamicNodeDefinitionRepository nodeDefRepository;
    private final ScriptNodeFactory scriptNodeFactory;
    private final GraphDefinitionRepository definitionRepository;
    /** 可选：未引入 ace-graph-dsl-ai 时为空，遇 GENERIC_AGENT 给出可操作报错 */
    private final ObjectProvider<GenericAgentNodeFactory> agentNodeFactory;

    public DynamicGraphBuilder(GraphNodeRegistry nodeRegistry,
                               EdgeDispatcherRegistry dispatcherRegistry,
                               GraphValidator validator,
                               ApplicationContext applicationContext,
                               CheckpointSaverRegistry saverRegistry,
                               ScriptEdgeActionFactory scriptEdgeActionFactory,
                               List<GraphExecutionListener> executionListeners,
                               DynamicNodeDefinitionRepository nodeDefRepository,
                               ScriptNodeFactory scriptNodeFactory,
                               GraphDefinitionRepository definitionRepository,
                               ObjectProvider<GenericAgentNodeFactory> agentNodeFactory) {
        this.nodeRegistry = nodeRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.validator = validator;
        this.applicationContext = applicationContext;
        this.saverRegistry = saverRegistry != null ? saverRegistry : CheckpointSaverRegistry.defaults();
        this.scriptEdgeActionFactory = scriptEdgeActionFactory;
        this.executionListeners = executionListeners != null ? executionListeners : List.of();
        this.nodeDefRepository = nodeDefRepository;
        this.scriptNodeFactory = scriptNodeFactory;
        this.definitionRepository = definitionRepository;
        this.agentNodeFactory = agentNodeFactory;
    }

    /**
     * 编译图定义为 CompiledGraph。先校验，再构建。
     *
     * @param def 图定义
     * @return 编译后的 CompiledGraph
     * @throws IllegalArgumentException 校验失败时
     * @throws GraphStateException StateGraph 构建或编译异常时
     */
    public CompiledGraph build(GraphDefinition def) throws GraphStateException {
        ValidationResult validation = validator.validate(def);
        if (!validation.ok()) {
            throw new IllegalArgumentException("图定义校验失败: " + String.join("; ", validation.errors()));
        }
        return doBuild(def, new HashSet<>(), 0);
    }

    /**
     * 校验图定义（不构建）。
     *
     * @param def 图定义
     * @return 校验结果
     */
    public ValidationResult validate(GraphDefinition def) {
        return validator.validate(def);
    }

    /**
     * 仅构建 StateGraph（不编译），用于 PlantUML 预览。
     *
     * @throws GraphStateException StateGraph 构建异常时
     */
    public StateGraph buildStateGraph(GraphDefinition def) throws GraphStateException {
        ValidationResult validation = validator.validate(def);
        if (!validation.ok()) {
            throw new IllegalArgumentException("图定义校验失败: " + String.join("; ", validation.errors()));
        }
        return doBuildStateGraph(def, new HashSet<>(), 0);
    }

    private CompiledGraph doBuild(GraphDefinition def, Set<String> visiting, int depth) throws GraphStateException {
        StateGraph stateGraph = doBuildStateGraph(def, visiting, depth);
        CompileConfig config = buildCompileConfig(def.compile());
        CompiledGraph compiled = stateGraph.compile(config);
        log.info("图定义编译成功, graphId={}, version={}, nodes={}, edges={}, depth={}",
                def.graphId(), def.version(), def.nodes().size(), def.edges().size(), depth);
        return compiled;
    }

    private StateGraph doBuildStateGraph(GraphDefinition def, Set<String> visiting, int depth) throws GraphStateException {
        // 子图嵌套深度限制
        if (depth > MAX_SUBGRAPH_DEPTH) {
            throw new GraphStateException("子图嵌套层级超过限制（最多" + MAX_SUBGRAPH_DEPTH + "层）: "
                    + (def.graphId() != null ? def.graphId() : "(内联子图)"));
        }

        // 循环引用检测：当前 graphId 已在访问栈中 → 环
        String currentGraphId = def.graphId();
        if (currentGraphId != null && !currentGraphId.isBlank() && visiting.contains(currentGraphId)) {
            throw new GraphStateException("检测到子图循环引用: " + currentGraphId
                    + "（子图引用链形成环，请检查 subgraphRef）");
        }
        Set<String> nextVisiting = new HashSet<>(visiting);
        if (currentGraphId != null && !currentGraphId.isBlank()) {
            nextVisiting.add(currentGraphId);
        }

        // 编译前：按需加载缺失的脚本节点（多实例懒加载）
        ensureScriptNodesLoaded(def);
        KeyStrategyFactory keyStrategyFactory = createKeyStrategyFactory(def);
        StateGraph stateGraph = new StateGraph(keyStrategyFactory);
        NodeRuntimeContext ctx = NodeRuntimeContext.empty(applicationContext);

        // 计算并行扇出分组：同一源节点的多条 parallel=true 普通出边 → 目标集合
        Map<String, List<String>> fanOutBySource = new LinkedHashMap<>();
        for (GraphEdge edge : def.edges()) {
            if (edge.parallel() != null && edge.parallel() && !edge.isConditional()
                    && !StateGraph.START.equals(resolveToken(edge.from()))) {
                fanOutBySource.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            }
        }
        Set<String> fanOutTargets = fanOutBySource.values().stream()
                .flatMap(List::stream).collect(Collectors.toSet());

        // 1. 注册节点（并行扇出目标节点已内联进各自的扇出分支，不在主图直接注册）
        for (NodeRef ref : def.nodes()) {
            if (fanOutTargets.contains(ref.nodeId())) {
                continue;
            }
            // 子图节点：递归编译为 CompiledGraph 并挂载（graph-in-graph）
            // 走 SubCompiledGraphNode 路径（而非 SubStateGraphNode），支持子图内 HITL（G4）
            if (ref.hasSubgraph()) {
                // 引用型子图：编译期解析 subgraphRef，检测循环引用（基于剥离 @version 的 graphId）
                if (ref.subgraphRef() != null && !ref.subgraphRef().isBlank()) {
                    String refGraphId = NodeRef.graphIdOf(ref.subgraphRef());
                    if (nextVisiting.contains(refGraphId)) {
                        throw new GraphStateException("检测到子图循环引用: "
                                + currentGraphId + " → " + refGraphId
                                + "（子图引用链形成环，请检查 subgraphRef）");
                    }
                }
                GraphDefinition subDef = resolveSubgraph(ref);
                if (subDef == null) {
                    throw new GraphStateException("子图未定义（subgraph 与 subgraphRef 均为空）: " + ref.nodeId());
                }
                CompiledGraph subCompiled = doBuild(subDef, nextVisiting, depth + 1);
                stateGraph.addNode(ref.nodeId(), subCompiled);
                continue;
            }

            NodeRuntimeContext nodeCtx = ref.config() != null && !ref.config().isEmpty()
                    ? new NodeRuntimeContext(applicationContext, ref.config())
                    : ctx;

            if (ref.hasAgent() || "AGENT".equals(ref.category())) {
                RegisteredAgentNode agentNode = nodeRegistry.getAgentNode();
                if (agentNode == null) {
                    throw new GraphStateException("未注册 AGENT 节点实现（需要 agent:script）: " + ref.nodeId());
                }
                CommandAction raw = agentNode.toCommandAction(nodeCtx);
                String nodeId = ref.nodeId();
                CommandAction resolved = (state, rc) -> {
                    Command c = raw.apply(state, rc);
                    String gotoNode = c.gotoNode();
                    if (GraphDefinition.START.equals(gotoNode) || GraphDefinition.END.equals(gotoNode)
                            || GraphDefinition.ERROR.equals(gotoNode)) {
                        return c;
                    }
                    if ("__SELF__".equals(gotoNode)) {
                        return new Command(nodeId, c.update());
                    }
                    return c;
                };
                stateGraph.addNode(ref.nodeId(), AsyncCommandAction.node_async(resolved), Map.of());
                continue;
            }
            stateGraph.addNode(ref.nodeId(), buildSingleNodeAction(def, ref, nodeCtx, nextVisiting, depth));
        }

        // 并行扇出：每个分组生成一个内部扇出节点，并发执行各分支子图，结果合并写回
        Map<String, String> targetToFanoutId = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fanOutBySource.entrySet()) {
            String source = entry.getKey();
            List<String> targets = entry.getValue();
            if (targets.size() < 2) {
                continue; // 单条 parallel 边：无扇出语义，退化为普通边
            }
            List<CompiledGraph> branches = new ArrayList<>();
            for (String t : targets) {
                NodeRef tRef = findNode(def, t);
                if (tRef == null) {
                    throw new GraphStateException("并行扇出目标节点不存在: " + t);
                }
                if (tRef.hasAgent() || "AGENT".equals(tRef.category())) {
                    throw new GraphStateException("AGENT 循环节点不支持作为并行扇出分支: " + t);
                }
                branches.add(buildBranchCompiled(def, tRef, ctx, keyStrategyFactory, nextVisiting, depth));
                targetToFanoutId.put(t, source + "__fanout");
            }
            String fanoutId = source + "__fanout";
            stateGraph.addNode(fanoutId, new FanOutNodeAction(branches));
            stateGraph.addEdge(resolveToken(source), fanoutId);
            log.debug("并行扇出节点已挂载, source={}, targets={}, fanoutId={}", source, targets, fanoutId);
        }

        // 扇出节点的 fan-in 出边：分支节点已内联进各自的扇出子图、不在主图注册，
        // 故各分支原有出边目标需去重后汇总为扇出节点的出边（避免同一目标产生重复边）。
        Map<String, Set<String>> fanoutOutgoing = new LinkedHashMap<>();
        for (GraphEdge edge : def.edges()) {
            if (targetToFanoutId.containsKey(edge.from())) {
                String fanoutId = targetToFanoutId.get(edge.from());
                // 分支出边目标若本身是另一扇出分支，则交由对应扇出层处理，跳过
                if (!targetToFanoutId.containsKey(edge.to())) {
                    fanoutOutgoing.computeIfAbsent(fanoutId, k -> new LinkedHashSet<>()).add(edge.to());
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : fanoutOutgoing.entrySet()) {
            for (String to : e.getValue()) {
                stateGraph.addEdge(resolveToken(e.getKey()), resolveToken(to));
                log.debug("扇出节点 fan-in 出边已挂载, fanoutId={}, to={}", e.getKey(), to);
            }
        }

        // 2. 注册边（先合并同一源节点的条件边；parallel 扇出边已由扇出节点处理，跳过）
        for (GraphEdge edge : mergeConditionalEdges(def.edges())) {
            applyEdge(stateGraph, edge, ctx, targetToFanoutId);
        }

        return stateGraph;
    }

    /**
     * 解析通用 agent 节点（双通道）。
     *
     * <p><b>通道一 · 内联</b>：{@code NodeRef.agentSpec} 自带完整元数据，编译期即时装配，
     * 节点只属于当前图，不进注册中心（避免与同名的可复用定义互相覆盖）。</p>
     *
     * <p><b>通道二 · 注册式</b>：图里只留 {@code nodeId}，元数据来自已入库并注册的
     * {@code GenericAgentDefinition}。注册实例是「无图归属」的共享定义，此处
     * {@link GraphBoundAgentNode#withGraphId(String)} 克隆出绑定当前图的副本再执行，
     * 保证 {@code SecretResolver} 拿到正确的图命名空间，杜绝跨图串用。</p>
     */
    private GraphBoundAgentNode resolveGenericAgent(GraphDefinition def, NodeRef ref) throws GraphStateException {
        GenericAgentNodeFactory factory = agentNodeFactory == null ? null : agentNodeFactory.getIfAvailable();
        if (factory == null) {
            throw new GraphStateException("图 " + def.graphId() + " 含 GENERIC_AGENT 节点 " + ref.nodeId()
                    + "，但未找到 GenericAgentNodeFactory；请引入 ace-graph-dsl-ai 依赖");
        }
        if (ref.agentSpec() != null) {
            GenericAgentSpec normalized = normalizeStreamKind(def.graphId(), ref.nodeId(), ref.agentSpec());
            log.info("内联装配 GenericAgent: graphId={}, nodeId={}, streamResponseKind={}",
                    def.graphId(), ref.nodeId(), normalized.streamResponseKind());
            return factory.create(ref.nodeId(), def.graphId(), normalized);
        }
        if (!nodeRegistry.contains(ref.nodeId())) {
            throw new GraphStateException("通用 agent 节点既无内联 agentSpec，也未在注册中心找到已入库定义: "
                    + ref.nodeId() + "（请先在设计器创建 agent 节点定义，或为该节点补内联元数据）");
        }
        RegisteredGraphNode registered = nodeRegistry.get(ref.nodeId());
        if (!(registered instanceof GraphBoundAgentNode agentNode)) {
            throw new GraphStateException("节点 " + ref.nodeId() + " 已注册但并非通用 agent 节点: "
                    + registered.getClass().getName());
        }
        // 注册式：编译期 normalize kind 后经工厂重建绑定副本
        GenericAgentSpec registeredSpec = agentNode.agentSpec();
        if (registeredSpec != null) {
            GenericAgentSpec normalized = normalizeStreamKind(def.graphId(), ref.nodeId(), registeredSpec);
            if (!java.util.Objects.equals(normalized.streamResponseKind(), registeredSpec.streamResponseKind())) {
                log.info("注册式 GenericAgent 编译期归一化 streamResponseKind: graphId={}, nodeId={}, {} -> {}",
                        def.graphId(), ref.nodeId(),
                        registeredSpec.streamResponseKind(), normalized.streamResponseKind());
                return factory.create(ref.nodeId(), def.graphId(), normalized);
            }
        }
        return agentNode.withGraphId(def.graphId());
    }

    /**
     * 编译期归一化 {@code streamResponseKind}（空值回落目录首位，§9.7 / P0.4）。
     */
    private GenericAgentSpec normalizeStreamKind(String graphId, String nodeId, GenericAgentSpec spec) {
        if (spec == null) {
            return null;
        }
        StreamResponseKindResolver resolver = findStreamKindResolver();
        if (resolver == null) {
            log.warn("未找到 StreamResponseKindResolver，跳过编译期 normalize: graphId={}, nodeId={}",
                    graphId, nodeId);
            return spec;
        }
        StreamResponseKind kind = resolver.resolveOrDefault(graphId, spec.streamResponseKind());
        if (java.util.Objects.equals(kind.code(), spec.streamResponseKind())) {
            return spec;
        }
        log.info("编译期 normalize streamResponseKind: graphId={}, nodeId={}, raw={} -> {}",
                graphId, nodeId, spec.streamResponseKind(), kind.code());
        return spec.withStreamResponseKind(kind.code());
    }

    private StreamResponseKindResolver findStreamKindResolver() {
        try {
            return applicationContext.getBean(StreamResponseKindResolver.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并同一源节点的多条条件边。
     *
     * <p>{@code StateGraph} 对同一个 {@code from} 只允许调用一次
     * {@code addConditionalEdges}，因此设计器里从同一节点拉出的多条条件边
     * （dispatcher/condition/engine 一致）需合并 mapping 为一条；若路由来源不一致
     * 则给出明确错误，而非底层的 "already exist"。</p>
     */
    private List<GraphEdge> mergeConditionalEdges(List<GraphEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        Map<String, GraphEdge> conditionalByFrom = new HashMap<>();
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (!edge.isConditional()) {
                result.add(edge);
                continue;
            }
            String from = edge.from();
            GraphEdge existing = conditionalByFrom.get(from);
            if (existing == null) {
                conditionalByFrom.put(from, edge);
                // 占位，保持声明顺序；实际内容在收尾阶段回填
                result.add(edge);
            } else {
                if (!sameConditionalSource(existing, edge)) {
                    throw new IllegalArgumentException(
                            "节点 " + from + " 存在多条条件边，且 dispatcher/condition 不一致，无法合并");
                }
                Map<String, String> merged = new HashMap<>(
                        existing.mapping() != null ? existing.mapping() : Map.of());
                if (edge.mapping() != null) {
                    merged.putAll(edge.mapping());
                }
                GraphEdge mergedEdge = new GraphEdge(
                        existing.from(), existing.to(), existing.type(),
                        existing.dispatcher(), merged,
                        existing.condition(), existing.conditionEngine());
                conditionalByFrom.put(from, mergedEdge);
                // 用合并后的边替换 result 中的占位
                result.replaceAll(e -> e == existing ? mergedEdge : e);
            }
        }
        return result;
    }

    private boolean sameConditionalSource(GraphEdge a, GraphEdge b) {
        return Objects.equals(a.dispatcher(), b.dispatcher())
                && Objects.equals(a.condition(), b.condition())
                && Objects.equals(a.conditionEngine(), b.conditionEngine());
    }

    private void applyEdge(StateGraph g, GraphEdge edge, NodeRuntimeContext ctx,
            Map<String, String> targetToFanoutId) throws GraphStateException {
        if (edge.parallel() != null && edge.parallel() && !edge.isConditional()) {
            return; // 并行扇出边已由扇出节点处理
        }
        // 扇出分支的出边已由扇出节点的 fan-in 出边统一挂载，主图不再重复注册
        if (targetToFanoutId.containsKey(edge.from())) {
            return;
        }
        String from = resolveToken(edge.from());
        if (edge.isConditional()) {
            Map<String, String> resolvedMapping = new HashMap<>();
            for (Map.Entry<String, String> entry : edge.mapping().entrySet()) {
                resolvedMapping.put(entry.getKey(), resolveToken(entry.getValue()));
            }
            EdgeAction action = edge.isScriptRouting()
                    ? scriptEdgeActionFactory.create(edge.resolvedConditionEngine(), edge.condition())
                    : dispatcherRegistry.get(edge.dispatcher()).toAction(ctx);
            g.addConditionalEdges(from, AsyncEdgeAction.edge_async(action), resolvedMapping);
        } else {
            String to = resolveToken(edge.to());
            g.addEdge(from, to);
        }
    }

    /**
     * 将 DSL 保留字 __START__ / __END__ / __ERROR__ 转为 StateGraph 对应常量。
     */
    private String resolveToken(String token) {
        if (GraphDefinition.START.equals(token)) {
            return StateGraph.START;
        }
        if (GraphDefinition.END.equals(token)) {
            return StateGraph.END;
        }
        if (GraphDefinition.ERROR.equals(token)) {
            return StateGraph.ERROR;
        }
        return token;
    }

    /**
     * 解析子图节点指向的 {@link GraphDefinition}：优先内嵌 {@code ref.subgraph()}，
     * 其次按 {@code ref.subgraphRef()} 从仓库加载。两者皆空返回 {@code null}。
     *
     * <p>{@code subgraphRef} 支持两种格式（P2 版本锁定）：
     * <ul>
     *   <li>{@code "order-flow"} → 加载最新版本（{@link #loadLatest}）</li>
     *   <li>{@code "order-flow@1.2.0"} → 加载指定版本（{@link #loadVersion}）</li>
     * </ul>
     */
    private GraphDefinition resolveSubgraph(NodeRef ref) {
        if (ref.subgraph() != null) {
            return ref.subgraph();
        }
        String raw = ref.subgraphRef();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String graphId = NodeRef.graphIdOf(raw);
        String version = NodeRef.versionOf(raw);
        try {
            GraphDefinition loaded = version != null
                    ? definitionRepository.loadVersion(graphId, version)
                    : definitionRepository.loadLatest(graphId);
            if (loaded != null) {
                return loaded;
            }
            log.warn("子图引用未找到, subgraphRef={}, graphId={}, version={}", raw, graphId, version);
        } catch (Exception e) {
            log.warn("子图引用加载失败, subgraphRef={}, error={}", raw, e.getMessage());
        }
        return null;
    }

    private KeyStrategyFactory createKeyStrategyFactory(GraphDefinition def) {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            if (def.keyStrategies() != null) {
                def.keyStrategies().forEach((k, v) -> strategies.put(k, toStrategy(v)));
            }
            return strategies;
        };
    }

    /**
     * 为单个节点构造 {@link AsyncNodeAction}（不含 subgraph 类型）。
     * 提取自 {@link #doBuildStateGraph} 的节点注册循环，供主图节点与并行扇出分支共用。
     */
    private AsyncNodeAction buildSingleNodeAction(GraphDefinition def, NodeRef ref,
            NodeRuntimeContext nodeCtx, Set<String> visiting, int depth) throws GraphStateException {
        if (ref.hasAgentSpec() || GraphNodeDescriptor.CATEGORY_GENERIC_AGENT.equals(ref.category())) {
            return node_async(resolveGenericAgent(def, ref).toAction(nodeCtx));
        }
        RegisteredGraphNode node = nodeRegistry.get(ref.nodeId());
        return node_async(node.toAction(nodeCtx));
    }

    /**
     * 将单个节点编译为可独立执行的 {@link CompiledGraph} 分支（{@code START → node → END}）。
     * 供并行扇出节点并发调用。subgraph 类型直接复用其已编译子图。
     */
    private CompiledGraph buildBranchCompiled(GraphDefinition def, NodeRef ref,
            NodeRuntimeContext ctx, KeyStrategyFactory keyStrategyFactory,
            Set<String> visiting, int depth) throws GraphStateException {
        if (ref.hasSubgraph()) {
            if (ref.subgraphRef() != null && !ref.subgraphRef().isBlank()) {
                String refGraphId = NodeRef.graphIdOf(ref.subgraphRef());
                if (visiting.contains(refGraphId)) {
                    throw new GraphStateException("检测到子图循环引用: " + refGraphId);
                }
            }
            GraphDefinition subDef = resolveSubgraph(ref);
            if (subDef == null) {
                throw new GraphStateException("子图未定义（并行扇出分支）: " + ref.nodeId());
            }
            return doBuild(subDef, visiting, depth + 1);
        }
        StateGraph sub = new StateGraph(keyStrategyFactory);
        sub.addNode(ref.nodeId(), buildSingleNodeAction(def, ref, ctx, visiting, depth));
        sub.addEdge(StateGraph.START, ref.nodeId());
        sub.addEdge(ref.nodeId(), StateGraph.END);
        return sub.compile();
    }

    /** 在图定义中按 nodeId 查找节点引用。 */
    private static NodeRef findNode(GraphDefinition def, String nodeId) {
        if (def.nodes() == null) {
            return null;
        }
        return def.nodes().stream()
                .filter(n -> nodeId.equals(n.nodeId()))
                .findFirst().orElse(null);
    }

    private KeyStrategy toStrategy(String name) {
        return switch (name) {
            case "REPLACE" -> new ReplaceStrategy();
            case "APPEND" -> new AppendStrategy();
            case "MERGE" -> new MergeStrategy();
            default -> {
                log.warn("未知 KeyStrategy: {}, 回退为 REPLACE", name);
                yield new ReplaceStrategy();
            }
        };
    }

    private CompileConfig buildCompileConfig(CompileConfigDto dto) {
        CompileConfig.Builder builder = CompileConfig.builder();
        if (dto != null) {
            String saver = dto.saver() != null ? dto.saver() : "memory";
            builder.saverConfig(buildSaverConfig(saver));
            if (dto.interruptBefore() != null && !dto.interruptBefore().isEmpty()) {
                builder.interruptBefore(dto.interruptBefore().toArray(new String[0]));
            }
        } else {
            builder.saverConfig(buildSaverConfig("memory"));
        }
        if (!executionListeners.isEmpty()) {
            builder.withLifecycleListener(new GraphLifecycleListenerBridge(executionListeners));
        }
        return builder.build();
    }

    private SaverConfig buildSaverConfig(String saver) {
        return SaverConfig.builder().register(saverRegistry.resolve(saver)).build();
    }

    /**
     * 编译前检查图引用的脚本节点，从 DB 重新加载以确保多实例部署下获得最新定义。
     *
     * <p>每次编译都会从 DB 加载脚本节点定义并覆盖注册中心中的实例。
     * 编译操作本身频率低（启动、发布、回滚、多实例懒刷新），因此无性能顾虑。</p>
     */
    private void ensureScriptNodesLoaded(GraphDefinition def) {
        for (NodeRef ref : def.nodes()) {
            String nodeId = ref.nodeId();
            if (!nodeId.startsWith("script:")) continue;

            try {
                DynamicNodeDefinition nodeDef = nodeDefRepository.findById(nodeId).orElse(null);
                if (nodeDef != null) {
                    RegisteredGraphNode rn = scriptNodeFactory.create(nodeDef);
                    nodeRegistry.registerDynamic(rn);
                    log.debug("编译前刷新脚本节点, nodeId={}", nodeId);
                }
            } catch (Exception e) {
                log.warn("脚本节点加载失败, nodeId={}, error={}", nodeId, e.getMessage());
            }
        }
    }
}
