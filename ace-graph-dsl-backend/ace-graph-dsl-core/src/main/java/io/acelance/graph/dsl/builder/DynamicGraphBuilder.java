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
import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.acelance.graph.dsl.script.ScriptEdgeActionFactory;
import io.acelance.graph.dsl.script.ScriptNodeFactory;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 动态图构建器：将 {@link GraphDefinition} DSL 编译为 {@link CompiledGraph}。
 */
@Component
public class DynamicGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DynamicGraphBuilder.class);

    private final GraphNodeRegistry nodeRegistry;
    private final EdgeDispatcherRegistry dispatcherRegistry;
    private final GraphValidator validator;
    private final ApplicationContext applicationContext;
    private final CheckpointSaverRegistry saverRegistry;
    private final ScriptEdgeActionFactory scriptEdgeActionFactory;
    private final List<GraphExecutionListener> executionListeners;
    private final DynamicNodeDefinitionRepository nodeDefRepository;
    private final ScriptNodeFactory scriptNodeFactory;

    public DynamicGraphBuilder(GraphNodeRegistry nodeRegistry,
                               EdgeDispatcherRegistry dispatcherRegistry,
                               GraphValidator validator,
                               ApplicationContext applicationContext,
                               CheckpointSaverRegistry saverRegistry,
                               ScriptEdgeActionFactory scriptEdgeActionFactory,
                               List<GraphExecutionListener> executionListeners,
                               DynamicNodeDefinitionRepository nodeDefRepository,
                               ScriptNodeFactory scriptNodeFactory) {
        this.nodeRegistry = nodeRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.validator = validator;
        this.applicationContext = applicationContext;
        this.saverRegistry = saverRegistry != null ? saverRegistry : CheckpointSaverRegistry.defaults();
        this.scriptEdgeActionFactory = scriptEdgeActionFactory;
        this.executionListeners = executionListeners != null ? executionListeners : List.of();
        this.nodeDefRepository = nodeDefRepository;
        this.scriptNodeFactory = scriptNodeFactory;
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
        return doBuild(def);
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
        return doBuildStateGraph(def);
    }

    private CompiledGraph doBuild(GraphDefinition def) throws GraphStateException {
        StateGraph stateGraph = doBuildStateGraph(def);
        CompileConfig config = buildCompileConfig(def.compile());
        CompiledGraph compiled = stateGraph.compile(config);
        log.info("图定义编译成功, graphId={}, version={}, nodes={}, edges={}",
                def.graphId(), def.version(), def.nodes().size(), def.edges().size());
        return compiled;
    }

    private StateGraph doBuildStateGraph(GraphDefinition def) throws GraphStateException {
        // 编译前：按需加载缺失的脚本节点（多实例懒加载）
        ensureScriptNodesLoaded(def);
        KeyStrategyFactory keyStrategyFactory = createKeyStrategyFactory(def);
        StateGraph stateGraph = new StateGraph(keyStrategyFactory);
        NodeRuntimeContext ctx = NodeRuntimeContext.empty(applicationContext);

        // 1. 注册节点
        for (NodeRef ref : def.nodes()) {
            RegisteredGraphNode node = nodeRegistry.get(ref.nodeId());
            NodeRuntimeContext nodeCtx = ref.config() != null && !ref.config().isEmpty()
                    ? new NodeRuntimeContext(applicationContext, ref.config())
                    : ctx;
            stateGraph.addNode(ref.nodeId(), node_async(node.toAction(nodeCtx)));
        }

        // 2. 注册边
        for (GraphEdge edge : def.edges()) {
            applyEdge(stateGraph, edge, ctx);
        }

        return stateGraph;
    }

    private void applyEdge(StateGraph g, GraphEdge edge, NodeRuntimeContext ctx) throws GraphStateException {
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
     * 将 DSL 保留字 __START__ / __END__ 转为 StateGraph.START / StateGraph.END。
     */
    private String resolveToken(String token) {
        if (GraphDefinition.START.equals(token)) {
            return StateGraph.START;
        }
        if (GraphDefinition.END.equals(token)) {
            return StateGraph.END;
        }
        return token;
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

    private KeyStrategy toStrategy(String name) {
        return switch (name) {
            case "REPLACE" -> new ReplaceStrategy();
            case "APPEND" -> new AppendStrategy();
            default -> throw new IllegalArgumentException("未知 KeyStrategy: " + name);
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
