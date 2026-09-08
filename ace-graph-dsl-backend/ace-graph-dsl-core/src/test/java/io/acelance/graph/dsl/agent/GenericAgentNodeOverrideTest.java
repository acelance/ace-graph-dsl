package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.observability.TraceLLMEvent;
import io.acelance.graph.dsl.observability.TraceRecorder;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.runtime.ModelOverride;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 GenericAgentNode 能：
 * 1）从 state 保留键读取请求级模型覆盖并应用到实际模型；
 * 2）在 LLM 调用边界通过 TraceRecorder 推送实际模型等细节（runId 对齐）。
 */
class GenericAgentNodeOverrideTest {

    @Configuration
    static class Cfg {
        @Bean
        TraceRecorder recorder() {
            return new CapturingRecorder();
        }

        @Bean
        StubChatClientFactory stub() {
            return new StubChatClientFactory();
        }
    }

    /** 捕获事件的 TraceRecorder 实现（不依赖 Langfuse）。 */
    static class CapturingRecorder implements TraceRecorder {
        final List<TraceLLMEvent> events = new ArrayList<>();

        @Override
        public void recordLLM(TraceLLMEvent event) {
            events.add(event);
        }
    }

    private GenericAgentNode node(AnnotationConfigApplicationContext ctx) {
        GenericAgentSpec spec = new GenericAgentSpec(
                "https://base", "sk-demo", false, "qwen-plus", "你好 {{x}}", null,
                null, null, null, null, List.of(), null, "out");
        return new GenericAgentNode("nodeA", "g-test", spec, ctx);
    }

    private OverAllState stateWith(Map<String, Object> extra) {
        return new OverAllState(extra);
    }

    @Test
    void appliesRequestLevelModelOverrideAndRecordsActualModel() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            CapturingRecorder recorder = (CapturingRecorder) ctx.getBean(TraceRecorder.class);
            GenericAgentNode node = node(ctx);

            Map<String, Object> extra = Map.of(
                    ModelOverrideSpec.ACE_RUN_ID_KEY, "run-1",
                    ModelOverrideSpec.ACE_MODEL_OVERRIDES_KEY,
                    new ModelOverrideSpec(new ModelOverride("gpt-4o-dynamic", null, null), Map.of()));
            Map<String, Object> result = node.toAction(null).apply(stateWith(extra));

            assertTrue(result.containsKey("out"));
            assertEquals(1, recorder.events.size());
            assertEquals("gpt-4o-dynamic", recorder.events.get(0).modelId(), "记录的是请求级动态模型");
            assertEquals("run-1", recorder.events.get(0).runId());
        }
    }

    @Test
    void noOverrideRecordsStaticModel() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            CapturingRecorder recorder = (CapturingRecorder) ctx.getBean(TraceRecorder.class);
            GenericAgentNode node = node(ctx);

            Map<String, Object> extra = Map.of(ModelOverrideSpec.ACE_RUN_ID_KEY, "run-2");
            node.toAction(null).apply(stateWith(extra));

            assertEquals(1, recorder.events.size());
            assertEquals("qwen-plus", recorder.events.get(0).modelId(), "无覆盖时记录静态模型");
        }
    }

    @Test
    void noRunIdSkipsRecording() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            CapturingRecorder recorder = (CapturingRecorder) ctx.getBean(TraceRecorder.class);
            GenericAgentNode node = node(ctx);

            // 无 ACE_RUN_ID_KEY → 不观测
            node.toAction(null).apply(stateWith(Map.of()));
            assertTrue(recorder.events.isEmpty());
        }
    }
}
