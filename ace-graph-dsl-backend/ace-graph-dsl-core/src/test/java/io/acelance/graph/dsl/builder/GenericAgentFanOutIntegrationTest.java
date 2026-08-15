package io.acelance.graph.dsl.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import io.acelance.graph.dsl.agent.AgentChatClient;
import io.acelance.graph.dsl.agent.AgentTool;
import io.acelance.graph.dsl.agent.ChatClientFactory;
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
import org.springframework.context.support.GenericApplicationContext;

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
 * <p>对应方案 {@code GENERIC_AGENT_NODE_PLAN.md} 第 8 步「demo 端到端验证」，并覆盖
 * DynamicGraphBuilder 对 {@code edge.parallel=true} 的真并发处理：</p>
 * <ul>
 *   <li>图结构：一个 GENERIC_AGENT 源节点 → 两条 {@code parallel=true} 边 → 两个 GENERIC_AGENT 分支
 *       → 聚合 GENERIC_AGENT 节点 → END。</li>
 *   <li>构建器会把两条 parallel 边重写为一个内部扇出节点（{@link FanOutNodeAction}），
 *       并发执行两个分支子图，再把结果合并写回 state。</li>
 *   <li>本测试用带人工延迟的 {@link DelayChatClientFactory} 覆盖内置 Stub，验证扇出节点确实<b>并发</b>
 *       （总耗时显著小于各分支延迟之和）。</li>
 * </ul>
 */
class GenericAgentFanOutIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentFanOutIntegrationTest.class);

    @Test
    void genericAgentFanOutRunsConcurrently() throws Exception {
        // 带 150ms 延迟的 ChatClient 工厂，覆盖内置 Stub，用于证明并发
        DelayChatClientFactory factory = new DelayChatClientFactory(150);
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean(DelayChatClientFactory.class, () -> factory);
        ctx.refresh();

        DynamicGraphBuilder builder = newBuilder(ctx);

        GraphDefinition def = sampleGraph();

        CompiledGraph compiled = builder.build(def);

        long start = System.nanoTime();
        OverAllState result = compiled.invoke(Map.of(), RunnableConfig.builder().build())
                .orElseThrow(() -> new AssertionError("invoke returned empty"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 各节点输出 key 均应写回
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

        // 并发验证（主证据，确定性）：两个分支在执行窗口内应同时处于活跃态 → maxActiveBranches>=2
        // 用并发计数器而非绝对计时，避免受 JVM 预热/测试套件负载导致的计时抖动影响。
        assertTrue(factory.maxActiveBranches() >= 2,
                "异步扇出应并发执行两分支（执行期同时活跃的分支数应 >= 2），实际: "
                        + factory.maxActiveBranches());
    }

    // ── 被测图定义 ──

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
                prompt, null, null, null, null, null,
                List.of(), inputKeys, outputKey);
    }

    // ── 最小 DynamicGraphBuilder 装配 ──

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
        return new DynamicGraphBuilder(
                nodeRegistry, dispatcherRegistry, validator, ctx,
                null, scriptEdgeActionFactory, null, null, scriptNodeFactory, null);
    }

    /**
     * 带人工延迟的 ChatClient 工厂：覆盖内置 StubChatClientFactory，用于验证扇出并发。
     * 实现 {@link ChatClientFactory} + {@link AgentChatClient} 两个 SPI 接口。
     * 通过 {@link #maxActiveBranches()} 统计执行期间同时处于活跃态的分支数，作为并发的主证据。
     */
    public static class DelayChatClientFactory implements ChatClientFactory, AgentChatClient {

        private final long delayMs;
        private final AtomicInteger activeBranches = new AtomicInteger();
        private final AtomicInteger maxActiveBranches = new AtomicInteger();

        public DelayChatClientFactory(long delayMs) {
            this.delayMs = delayMs;
        }

        /** 执行期间同时活跃的分支峰值（>=2 即证明两分支并发）。 */
        public int maxActiveBranches() {
            return maxActiveBranches.get();
        }

        @Override
        public AgentChatClient create(GenericAgentSpec spec, String graphId, String nodeId) {
            return this;
        }

        @Override
        public String call(String promptTemplate, Map<String, Object> variables, GenericAgentSpec spec) {
            return call(promptTemplate, variables, spec, List.of());
        }

        @Override
        public String call(String promptTemplate, Map<String, Object> variables,
                           GenericAgentSpec spec, List<AgentTool> tools) {
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
            result.put("model", spec.modelId());
            result.put("reply", "【DELAYED】branch executed for " + spec.modelId());
            result.put("prompt", promptTemplate);
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
            } catch (Exception e) {
                return result.toString();
            }
        }
    }
}
