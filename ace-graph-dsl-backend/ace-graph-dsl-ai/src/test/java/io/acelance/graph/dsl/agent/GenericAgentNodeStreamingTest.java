package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.ai.model.ChatModelFactory;
import io.acelance.graph.dsl.ai.model.StubChatModelFactory;
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

/**
 * GenericAgentNode 流式路径：经 StreamingLlmTemplate + ChatModelFactory。
 */
class GenericAgentNodeStreamingTest {

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
        ctx.registerBean("chatModels", ChatModelFactory.class, StubChatModelFactory::new);
        ctx.registerBean("bridge", GraphStreamBridge.class, CapturingBridge::new);
        ctx.refresh();

        GenericAgentSpec spec = new GenericAgentSpec("http://x", "k", "qwen-plus", "say hi", List.of());
        GenericAgentNode node = new GenericAgentNode("n1", "g1", spec, ctx);

        Map<String, Object> result = node.execute(Map.of(), "run-1", null);

        Object out = result.get("agent_result");
        assertTrue(out != null && out.toString().contains("【STUB】"), "应返回 Stub 终稿: " + out);

        CapturingBridge bridge = ctx.getBean(CapturingBridge.class);
        assertTrue(bridge.chunks.size() >= 2, "至少应有流式片段 + 结束标记, actual=" + bridge.chunks.size());
        assertEquals(OutputType.AGENT_MODEL_STREAMING, bridge.chunks.get(0).outputType());
        assertFalse(bridge.chunks.get(0).last());
        TokenChunk last = bridge.chunks.get(bridge.chunks.size() - 1);
        assertEquals(OutputType.AGENT_MODEL_FINISHED, last.outputType());
        assertTrue(last.last());
    }

    @Test
    void noBridgeAndNullRunIdFallsBackToBlockingCall() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("chatModels", ChatModelFactory.class, StubChatModelFactory::new);
        ctx.refresh();

        GenericAgentSpec spec = new GenericAgentSpec("http://x", "k", "qwen-plus", "say hi", List.of());
        GenericAgentNode node = new GenericAgentNode("n1", "g1", spec, ctx);

        Map<String, Object> result = node.execute(Map.of(), null, null);
        Object out = result.get("agent_result");
        assertTrue(out != null && out.toString().contains("【STUB】"), "无桥接时应阻塞返回 Stub: " + out);
    }
}
