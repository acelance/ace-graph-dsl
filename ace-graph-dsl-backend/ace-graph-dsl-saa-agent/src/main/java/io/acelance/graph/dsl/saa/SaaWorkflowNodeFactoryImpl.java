package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LoopAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.CountLoopStrategy;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.LoopStrategy;
import io.acelance.graph.dsl.agent.SaaWorkflowNodeFactory;
import io.acelance.graph.dsl.agent.SubAgentBinding;
import io.acelance.graph.dsl.agent.SubAgentResolveRequest;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaWorkflowPattern;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;
import io.acelance.graph.dsl.saa.loop.StateKeyExitLoopStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * SAA 高阶节点工厂（M2：SEQUENTIAL / PARALLEL / ROUTING / LOOP，方式 A）。
 */
public class SaaWorkflowNodeFactoryImpl implements SaaWorkflowNodeFactory {

    private static final Logger log = LoggerFactory.getLogger(SaaWorkflowNodeFactoryImpl.class);

    private final List<SubAgentResolver> resolvers;
    /** ROUTING 路由器模型解析：modelConfigKey → ChatModel；可空则用 fallback */
    private final Function<String, ChatModel> routerModelResolver;
    private final ChatModel routerModelFallback;

    public SaaWorkflowNodeFactoryImpl(List<SubAgentResolver> resolvers) {
        this(resolvers, null, null);
    }

    /**
     * @param resolvers           子 Agent 解析器
     * @param routerModelResolver 按 modelConfigKey 解析路由器模型（可空）
     * @param routerModelFallback 解析失败时的兜底 ChatModel（可空；ROUTING 时必有其一）
     */
    public SaaWorkflowNodeFactoryImpl(List<SubAgentResolver> resolvers,
                                      Function<String, ChatModel> routerModelResolver,
                                      ChatModel routerModelFallback) {
        this.resolvers = resolvers != null ? List.copyOf(resolvers) : List.of();
        this.routerModelResolver = routerModelResolver;
        this.routerModelFallback = routerModelFallback;
    }

    @Override
    public NodeAction create(String graphId, String nodeId, SaaWorkflowSpec spec, NodeRuntimeContext ctx) {
        Objects.requireNonNull(spec, "saaSpec");
        SaaWorkflowPattern pattern = spec.resolvedPattern();
        if (pattern == null) {
            throw new IllegalArgumentException("SAA pattern 非法: " + spec.pattern()
                    + ", graphId=" + graphId + ", nodeId=" + nodeId);
        }

        List<SubAgentBinding> bindings = resolveAll(graphId, nodeId, spec);
        List<Agent> agents = toAgents(bindings);
        List<String> resultKeys = bindings.stream()
                .map(SubAgentBinding::outputKey)
                .filter(StringUtils::hasText)
                .toList();
        String[] subNames = bindings.stream()
                .map(SubAgentBinding::name)
                .toArray(String[]::new);

        Agent flow = switch (pattern) {
            case SEQUENTIAL -> buildSequential(nodeId, graphId, agents);
            case PARALLEL -> buildParallel(nodeId, graphId, agents, spec.outputKey());
            case ROUTING -> buildRouting(nodeId, graphId, agents, spec);
            case LOOP -> buildLoop(nodeId, graphId, agents, spec);
        };

        log.info("组装 SAA FlowAgent 完成, graphId={}, nodeId={}, pattern={}, subAgents={}, outputKey={}",
                graphId, nodeId, pattern, bindings.stream().map(SubAgentBinding::name).toList(),
                spec.outputKey());

        return new FlowAgentNodeAction(
                graphId,
                nodeId,
                pattern.name(),
                flow,
                spec.inputKeyList(),
                spec.outputKey(),
                resultKeys.toArray(String[]::new),
                subNames);
    }

    private SequentialAgent buildSequential(String nodeId, String graphId, List<Agent> agents) {
        return SequentialAgent.builder()
                .name(nodeId)
                .description("SAA SEQUENTIAL graphId=" + graphId + ", nodeId=" + nodeId)
                .subAgents(agents)
                .build();
    }

    private ParallelAgent buildParallel(String nodeId, String graphId, List<Agent> agents, String mergeKey) {
        ParallelAgent.ParallelAgentBuilder builder = ParallelAgent.builder()
                .name(nodeId)
                .description("SAA PARALLEL graphId=" + graphId + ", nodeId=" + nodeId)
                .subAgents(agents);
        if (StringUtils.hasText(mergeKey)) {
            builder.mergeOutputKey(mergeKey.trim());
        }
        return builder.build();
    }

    private LlmRoutingAgent buildRouting(String nodeId, String graphId, List<Agent> agents, SaaWorkflowSpec spec) {
        ChatModel model = resolveRouterModel(spec.modelConfigKey(), graphId, nodeId);
        return LlmRoutingAgent.builder()
                .name(nodeId)
                .description("SAA ROUTING graphId=" + graphId + ", nodeId=" + nodeId)
                .model(model)
                .subAgents(agents)
                .build();
    }

    private LoopAgent buildLoop(String nodeId, String graphId, List<Agent> agents, SaaWorkflowSpec spec) {
        Agent body;
        if (agents.size() == 1) {
            body = agents.get(0);
        } else {
            body = SequentialAgent.builder()
                    .name(nodeId + "_loop_body")
                    .description("LOOP body Sequential")
                    .subAgents(agents)
                    .build();
        }
        LoopStrategy strategy = buildLoopStrategy(spec);
        log.info("LOOP 策略就绪, graphId={}, nodeId={}, maxIterations={}, exitKey={}, op={}",
                graphId, nodeId, spec.effectiveMaxIterations(),
                spec.exitConditionKey(), spec.exitConditionOp());
        return LoopAgent.builder()
                .name(nodeId)
                .description("SAA LOOP graphId=" + graphId + ", nodeId=" + nodeId)
                .subAgent(body)
                .loopStrategy(strategy)
                .build();
    }

    private LoopStrategy buildLoopStrategy(SaaWorkflowSpec spec) {
        int max = spec.effectiveMaxIterations();
        if (StringUtils.hasText(spec.exitConditionKey())) {
            return new StateKeyExitLoopStrategy(
                    max,
                    spec.exitConditionKey(),
                    spec.exitConditionOp(),
                    spec.exitConditionValue());
        }
        return new CountLoopStrategy(max);
    }

    private ChatModel resolveRouterModel(String modelConfigKey, String graphId, String nodeId) {
        ChatModel model = null;
        if (routerModelResolver != null && StringUtils.hasText(modelConfigKey)) {
            try {
                model = routerModelResolver.apply(modelConfigKey.trim());
            } catch (Exception ex) {
                log.warn("ROUTING 模型解析失败, graphId={}, nodeId={}, modelConfigKey={}, err={}",
                        graphId, nodeId, modelConfigKey, ex.toString());
            }
        }
        if (model == null) {
            model = routerModelFallback;
        }
        if (model == null) {
            throw new IllegalStateException("ROUTING 需要路由器 ChatModel：请配置 modelConfigKey 与 ChatModelFactory，"
                    + "或注入 fallback。graphId=" + graphId + ", nodeId=" + nodeId);
        }
        log.info("ROUTING 路由器模型就绪, graphId={}, nodeId={}, modelConfigKey={}",
                graphId, nodeId, modelConfigKey);
        return model;
    }

    private List<Agent> toAgents(List<SubAgentBinding> bindings) {
        List<Agent> agents = new ArrayList<>(bindings.size());
        for (SubAgentBinding binding : bindings) {
            if (!(binding.executable() instanceof Agent agent)) {
                throw new IllegalStateException("子 Agent 句柄不是 SAA Agent: name=" + binding.name()
                        + ", type=" + (binding.executable() == null
                        ? "null" : binding.executable().getClass().getName()));
            }
            agents.add(agent);
        }
        return agents;
    }

    private List<SubAgentBinding> resolveAll(String graphId, String nodeId, SaaWorkflowSpec spec) {
        List<SubAgentBinding> bindings = new ArrayList<>();
        for (SaaSubAgentRef ref : spec.subAgents()) {
            SubAgentResolver resolver = findResolver(ref.effectiveImpl());
            if (resolver == null) {
                throw new IllegalArgumentException("无匹配 SubAgentResolver: impl=" + ref.effectiveImpl()
                        + ", graphId=" + graphId + ", nodeId=" + nodeId + ", subName=" + ref.name());
            }
            SubAgentBinding binding = resolver.resolve(
                    new SubAgentResolveRequest(graphId, nodeId, ref, spec));
            bindings.add(binding);
            log.info("子 Agent 已解析, graphId={}, nodeId={}, name={}, impl={}, ref={}, outputKey={}",
                    graphId, nodeId, binding.name(), binding.impl(), binding.ref(), binding.outputKey());
        }
        return bindings;
    }

    private SubAgentResolver findResolver(String impl) {
        String key = impl == null ? "" : impl.trim().toUpperCase(Locale.ROOT);
        for (SubAgentResolver resolver : resolvers) {
            if (resolver.supports(key) || resolver.supports(impl)) {
                return resolver;
            }
        }
        return null;
    }
}
