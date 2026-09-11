package io.acelance.graph.dsl.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.agent.DefaultGenericAgentNodeFactory;
import io.acelance.graph.dsl.agent.GenericAgentNodeFactory;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.ModelEndpoint;
import io.acelance.graph.dsl.definition.CompileConfigDto;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphEdge;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.registry.EdgeDispatcherRegistry;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.script.ScriptEdgeActionFactory;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.ScriptNodeFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用 Agent 节点图 + 异步扇出（parallel）端到端验证。
 *
 * <p>用带人工延迟的 {@link DelayChatModelFactory} 覆盖默认 Stub，验证扇出节点确实并发
 * （执行期同时活跃的分支数 &gt;= 2）。</p>
 */
class GenericAgentFanOutIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentFanOutIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void genericAgentFanOutRunsConcurrently() throws Exception {
        DelayChatModelFactory factory = new DelayChatModelFactory(150);
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean(ChatModelFactory.class, () -> factory);
        ctx.refresh();

        DynamicGraphBuilder builder = newBuilder(ctx);
        GraphDefinition def = sampleGraph();
        CompiledGraph compiled = builder.build(def);

        long start = System.nanoTime();
        OverAllState result = compiled.invoke(Map.of(), RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        Object source = result.data().get("agent_source_result");
        Object branchA = result.data().get("branchA_result");
        Object branchB = result.data().get("branchB_result");
        Object aggregated = result.data().get("agent_result");

        log.info("[result] elapsed={}ms, maxConcurrentBranches={}, source={}, branchA={}, branchB={}, aggregated={}",
                elapsedMs, factory.maxActiveBranches(), source, branchA, branchB, aggregated);

        assertNotNull(source, "源节点应输出 agent_source_result");
        assertNotNull(branchA, "扇出分支 A 应输出 branchA_result");
        assertNotNull(branchB, "扇出分支 B 应输出 branchB_result");
        assertNotNull(aggregated, "聚合节点应输出 agent_result");

        assertTrue(branchA.toString().contains("branchA"), "分支 A 结果应包含分支标识: " + branchA);
        assertTrue(branchB.toString().contains("branchB"), "分支 B 结果应包含分支标识: " + branchB);

        assertTrue(factory.maxActiveBranches() >= 2,
                "异步扇出应并发执行两分支（执行期同时活跃的分支数应 >= 2），实际: "
                        + factory.maxActiveBranches());
    }

    private static GraphDefinition sampleGraph() {
        NodeRef source = ref("agentSource", "你是调度器。请基于输入生成一句调度说明。", null, "agent_source_result");
        NodeRef branchA = ref("branchA", "【branchA】读取 agent_source_result 并给出分支 A 的分析。",
                "agent_source_result", "branchA_result");
        NodeRef branchB = ref("branchB", "【branchB】读取 agent_source_result 并给出分支 B 的分析。",
                "agent_source_result", "branchB_result");
        NodeRef aggregator = ref("aggregator", "汇总 branchA_result 与 branchB_result，输出最终结论。",
                "branchA_result,branchB_result", "agent_result");

        List<GraphEdge> edges = List.of(
                new GraphEdge("__START__", "agentSource", "normal", null, null, null, null, null, null),
                new GraphEdge("agentSource", "branchA", "normal", null, null, null, null, Boolean.TRUE, null),
                new GraphEdge("agentSource", "branchB", "normal", null, null, null, null, Boolean.TRUE, null),
                new GraphEdge("branchA", "aggregator", "normal", null, null, null, null, null, null),
                new GraphEdge("branchB", "aggregator", "normal", null, null, null, null, null, null),
                new GraphEdge("aggregator", "__END__", "normal", null, null, null, null, null, null)
        );

        return new GraphDefinition(
                "generic-agent-fanout",
                "通用 Agent 节点 + 异步扇出",
                "1.0.0",
                "端到端样例",
                Map.of("agent_source_result", "REPLACE",
                        "branchA_result", "REPLACE",
                        "branchB_result", "REPLACE",
                        "agent_result", "REPLACE"),
                List.of(source, branchA, branchB, aggregator),
                edges,
                CompileConfigDto.defaultConfig(),
                false
        );
    }

    private static NodeRef ref(String nodeId, String prompt, String inputKeys, String outputKey) {
        return new NodeRef(nodeId, "GENERIC_AGENT", null, 0.0, 0.0, null, null, null,
                spec(prompt, inputKeys, outputKey));
    }

    private static GenericAgentSpec spec(String prompt, String inputKeys, String outputKey) {
        return new GenericAgentSpec(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "sk-demo", false, "qwen-plus",
                prompt, inputKeys, outputKey, null, null,
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), false, List.of());
    }

    private static DynamicGraphBuilder newBuilder(GenericApplicationContext ctx) {
        GraphNodeRegistry nodeRegistry = new GraphNodeRegistry(List.of());
        EdgeDispatcherRegistry dispatcherRegistry = new EdgeDispatcherRegistry(List.of());
        ScriptEngineRegistry engineRegistry = new ScriptEngineRegistry(List.of());
        ScriptEdgeActionFactory scriptEdgeActionFactory = new ScriptEdgeActionFactory(engineRegistry);
        EdgeParamReachabilityValidator reachabilityValidator =
                new EdgeParamReachabilityValidator(nodeRegistry);
        GraphValidator validator = new GraphValidator(
                nodeRegistry, dispatcherRegistry, scriptEdgeActionFactory, reachabilityValidator);
        ScriptNodeFactory scriptNodeFactory = new ScriptNodeFactory(engineRegistry);
        GenericAgentNodeFactory agentFactory = new DefaultGenericAgentNodeFactory(ctx);
        ObjectProvider<GenericAgentNodeFactory> agentFactoryProvider = new ObjectProvider<>() {
            @Override
            public GenericAgentNodeFactory getObject() {
                return agentFactory;
            }

            @Override
            public GenericAgentNodeFactory getObject(Object... args) {
                return agentFactory;
            }

            @Override
            public GenericAgentNodeFactory getIfAvailable() {
                return agentFactory;
            }

            @Override
            public GenericAgentNodeFactory getIfUnique() {
                return agentFactory;
            }
        };
        return new DynamicGraphBuilder(
                nodeRegistry, dispatcherRegistry, validator, ctx,
                null, scriptEdgeActionFactory, null, null, scriptNodeFactory, null,
                agentFactoryProvider);
    }

    /**
     * 带人工延迟的 ChatModel 工厂：用于验证扇出并发。
     * 通过 {@link #maxActiveBranches()} 统计执行期间同时处于活跃态的调用数。
     */
    public static class DelayChatModelFactory implements ChatModelFactory {

        private final long delayMs;
        private final AtomicInteger activeBranches = new AtomicInteger();
        private final AtomicInteger maxActiveBranches = new AtomicInteger();

        public DelayChatModelFactory(long delayMs) {
            this.delayMs = delayMs;
        }

        public int maxActiveBranches() {
            return maxActiveBranches.get();
        }

        @Override
        public ChatModel create(ModelEndpoint endpoint) {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    int cur = activeBranches.incrementAndGet();
                    maxActiveBranches.accumulateAndGet(cur, Math::max);
                    try {
                        TimeUnit.MILLISECONDS.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        activeBranches.decrementAndGet();
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("model", endpoint == null ? null : endpoint.modelId());
                    result.put("reply", "【DELAYED】branch executed");
                    result.put("prompt", promptText(prompt));
                    String json;
                    try {
                        json = MAPPER.writeValueAsString(result);
                    } catch (Exception e) {
                        json = result.toString();
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(call(prompt));
                }
            };
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
    }
}
