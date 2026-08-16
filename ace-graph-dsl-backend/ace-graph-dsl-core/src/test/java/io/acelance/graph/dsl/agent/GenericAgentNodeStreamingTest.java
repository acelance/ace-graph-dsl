package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericAgentNodeStreamingTest {

    /** 流式 stub：call 返回完整串，stream 拆成两个 token。 */
    static class StreamingStubChatClientFactory implements ChatClientFactory {
        @Override
        public AgentChatClient create(GenericAgentSpec spec, String graphId, String nodeId) {
            return new AgentChatClient() {
                @Override
                public String call(String promptTemplate, Map<String, Object> variables, GenericAgentSpec s) {
                    return "Hello world";
                }

                @Override
                public String call(String promptTemplate, Map<String, Object> variables,
                                  GenericAgentSpec s, List<AgentTool> tools) {
                    return "Hello world";
                }

                @Override
                public Flux<String> stream(String promptTemplate, Map<String, Object> variables, GenericAgentSpec s) {
                    return Flux.just("Hello", " world");
                }

                @Override
                public Flux<String> stream(String promptTemplate, Map<String, Object> variables,
                                          GenericAgentSpec s, List<AgentTool> tools) {
                    return Flux.just("Hello", " world");
                }
            };
        }
    }

    /** 记录所有被 emit 的 TokenChunk。 */
    static class CapturingBridge implements GraphStreamBridge {
        final List<TokenChunk> chunks = new ArrayList<>();

        @Override
        public void emit(String runId, TokenChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void complete(String runId) {
        }

        @Override
        public Flux<TokenChunk> register(String runId) {
            return Flux.empty();
        }
    }

    @Test
    void streamingPathEmitsTokenChunksAndReturnsFullMap() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("chatFactory", ChatClientFactory.class, StreamingStubChatClientFactory::new);
        ctx.registerBean("bridge", GraphStreamBridge.class, CapturingBridge::new);
        ctx.refresh();

        GenericAgentSpec spec = new GenericAgentSpec("http://x", "k", "qwen-plus", "say hi", List.of());
        GenericAgentNode node = new GenericAgentNode("n1", "g1", spec, ctx);

        Map<String, Object> result = node.execute(Map.of(), "run-1", null);

        // 1. 节点仍返回完整响应（图 state 正确）
        assertEquals("Hello world", result.get("agent_result"));

        // 2. 桥接器收到逐 token 片段 + 结束标记
        CapturingBridge bridge = ctx.getBean(CapturingBridge.class);
        assertEquals(3, bridge.chunks.size());
        assertEquals("Hello", bridge.chunks.get(0).token());
        assertEquals(OutputType.AGENT_MODEL_STREAMING, bridge.chunks.get(0).outputType());
        assertFalse(bridge.chunks.get(0).last());
        assertEquals(" world", bridge.chunks.get(1).token());
        assertEquals(OutputType.AGENT_MODEL_FINISHED, bridge.chunks.get(2).outputType());
        assertTrue(bridge.chunks.get(2).last());
    }

    @Test
    void noBridgeAndNullRunIdFallsBackToBlockingCall() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("chatFactory", ChatClientFactory.class, StreamingStubChatClientFactory::new);
        ctx.refresh(); // 不注册 GraphStreamBridge → 回落 NOOP + runId=null → 阻塞 call()

        GenericAgentSpec spec = new GenericAgentSpec("http://x", "k", "qwen-plus", "say hi", List.of());
        GenericAgentNode node = new GenericAgentNode("n1", "g1", spec, ctx);

        Map<String, Object> result = node.execute(Map.of(), null, null);
        assertEquals("Hello world", result.get("agent_result"));
    }
}
