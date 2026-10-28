package io.acelance.graph.dsl.agentscope;

import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §0.5：有无工具均推送模型可见文本（TEXT/THINKING delta）；TOOL/HINT 不进可见流。
 */
class AgentScopeVisibleStreamSessionTest {

    @Test
    void withToolsStillEmitsTextAndThinkingDeltas() {
        AgentScopeVisibleStreamSession session =
                new AgentScopeVisibleStreamSession("g", "n", "sub", true);
        List<String> visible = new ArrayList<>();

        session.accept(new ThinkingBlockDeltaEvent("r1", "b1", "think-1"), visible::add);
        session.accept(new ToolCallStartEvent("r1", "tc1", "search"), visible::add);
        session.accept(new ThinkingBlockDeltaEvent("r1", "b2", "think-2"), visible::add);
        session.accept(new TextBlockDeltaEvent("r1", "t1", "final"), visible::add);
        assertEquals(0, session.finish(visible::add));

        assertEquals(List.of("think-1", "think-2", "final"), visible);
        assertEquals("think-1think-2final", session.aggregatedText());
        assertTrue(session.emitCount() == 3);
    }

    @Test
    void toolAndHintDoNotEnterVisibleStream() {
        AgentScopeVisibleStreamSession session =
                new AgentScopeVisibleStreamSession("g", "n", "sub", true);
        List<String> visible = new ArrayList<>();

        session.accept(new ToolCallStartEvent("r1", "tc1", "search"), visible::add);
        session.accept(new HintBlockEvent("r1", "h1", "sys", "hint-text"), visible::add);
        session.accept(new TextBlockDeltaEvent("r1", "t1", "只有正文"), visible::add);
        session.finish(visible::add);

        assertEquals(List.of("只有正文"), visible);
        assertEquals("只有正文", session.aggregatedText());
    }

    @Test
    void withoutToolsEmitsTextDeltas() {
        AgentScopeVisibleStreamSession session =
                new AgentScopeVisibleStreamSession("g", "n", "sub", false);
        List<String> visible = new ArrayList<>();

        session.accept(new TextBlockDeltaEvent("r1", "t1", "Hello"), visible::add);
        session.accept(new TextBlockDeltaEvent("r1", "t1", " world"), visible::add);
        assertEquals(0, session.finish(visible::add));

        assertEquals(List.of("Hello", " world"), visible);
        assertEquals("Hello world", session.aggregatedText());
        assertEquals(2, session.emitCount());
    }

    @Test
    void agentResultFallbackWhenNoPriorDeltas() {
        AgentScopeVisibleStreamSession session =
                new AgentScopeVisibleStreamSession("g", "n", "sub", false);
        List<String> visible = new ArrayList<>();
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("整段").build();
        session.accept(new AgentResultEvent(msg), visible::add);
        assertEquals(List.of("整段"), visible);
        assertEquals("整段", session.aggregatedText());
    }
}
