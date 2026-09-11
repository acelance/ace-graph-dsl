package io.acelance.graph.dsl.ai.skill;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.ai.model.CachingChatModelFactory;
import io.acelance.graph.dsl.ai.model.ModelEndpointResolver;
import io.acelance.graph.dsl.ai.model.StubChatModelFactory;
import io.acelance.graph.dsl.ai.template.LlmCallRequest;
import io.acelance.graph.dsl.ai.template.StreamingLlmTemplate;
import io.acelance.graph.dsl.ai.model.InlineModel;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.prompt.PromptRenderer;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.skill.InMemorySkillStore;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Template：L1 追加 + forceSkills 预激活不失败。
 */
class SkillForceActivationTemplateTest {

    @Test
    void forceSkillsInWhitelist_preActivatesWithoutFailingCall() {
        InMemorySkillStore store = new InMemorySkillStore();
        store.put("skill.refund", "Refund", "refund help", "L2-REFUND-BODY");

        ResourceBinding binding = new ResourceBinding(
                true, List.of(), false, null, false, List.of(),
                false, List.of(), Map.of(), true, List.of("skill.refund"));
        OverAllState state = new OverAllState(Map.of(
                LlmRequestContext.ACE_FORCE_SKILLS_KEY, List.of("skill.refund", "skill.other")));
        LlmRequestContext ctx = new LlmRequestContext("agent", "g1", "n1", "run-1", state, binding);

        StreamingLlmTemplate template = new StreamingLlmTemplate(
                new PromptRenderer(),
                new ModelEndpointResolver(null, null),
                new CachingChatModelFactory(new StubChatModelFactory()),
                GraphStreamBridge.NOOP,
                store, store, store);

        Map<String, Object> out = template.execute(LlmCallRequest.builder()
                .context(ctx)
                .systemTemplate("You are helper")
                .userMessage("hi")
                .variables(Map.of())
                .outputKey("out")
                .streaming(false)
                .inlineModel(new InlineModel("http://x", "k", false, "m1"))
                .build());

        assertTrue(out.containsKey("out"));
        // Stub 回复应含提示或任意非空；关键是 force 路径不抛错
        assertTrue(String.valueOf(out.get("out")).length() > 0);
    }
}
