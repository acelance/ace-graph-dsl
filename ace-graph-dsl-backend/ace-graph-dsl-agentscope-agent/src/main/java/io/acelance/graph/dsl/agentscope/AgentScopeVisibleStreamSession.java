package io.acelance.graph.dsl.agentscope;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * G-STREAM-INNER §0.5：可见流会话（消费 AS {@code streamEvents} → {@link AgentEvent}）。
 *
 * <p>有无 Toolkit 均全推模型可见文本增量（TEXT / THINKING block delta）；工具相关事件仅日志。
 * 是否走前端「思考气泡」由 TokenChunk.attrs（bizParam）决定，与模型 deepThinking<strong>无关</strong>。</p>
 */
public final class AgentScopeVisibleStreamSession {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeVisibleStreamSession.class);

    private final String graphId;
    private final String parentNodeId;
    private final String subName;
    private final boolean hasTools;
    private final StringBuilder aggregated = new StringBuilder();
    private int emitCount;

    public AgentScopeVisibleStreamSession(String graphId,
                                          String parentNodeId,
                                          String subName,
                                          boolean hasTools) {
        this.graphId = graphId;
        this.parentNodeId = parentNodeId;
        this.subName = subName;
        this.hasTools = hasTools;
    }

    /**
     * 处理 {@link AgentEvent}；可见文本回调 {@code onVisibleDelta}。
     */
    public void accept(AgentEvent event, Consumer<String> onVisibleDelta) {
        Objects.requireNonNull(onVisibleDelta, "onVisibleDelta");
        if (event == null || event.getType() == null) {
            return;
        }
        AgentEventType type = event.getType();
        if (isToolOrHint(type)) {
            log.info("AGENTSCOPE 非可见流事件, bridge={}, graphId={}, parentNodeId={}, subName={}, type={}",
                    AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, graphId, parentNodeId, subName, type);
            return;
        }
        String delta = extractVisibleDelta(event);
        if (!StringUtils.hasText(delta)) {
            return;
        }
        aggregated.append(delta);
        emitCount++;
        onVisibleDelta.accept(delta);
        log.debug("AGENTSCOPE 可见 delta, graphId={}, parentNodeId={}, subName={}, type={}, deltaLen={}, hasTools={}",
                graphId, parentNodeId, subName, type, delta.length(), hasTools);
    }

    /** 流结束钩子：全推送口径下无需补推。 */
    public int finish(Consumer<String> onVisibleDelta) {
        Objects.requireNonNull(onVisibleDelta, "onVisibleDelta");
        return 0;
    }

    public String aggregatedText() {
        return aggregated.toString();
    }

    public int emitCount() {
        return emitCount;
    }

    public boolean hasTools() {
        return hasTools;
    }

    private static boolean isToolOrHint(AgentEventType type) {
        return type == AgentEventType.TOOL_CALL_START
                || type == AgentEventType.TOOL_CALL_DELTA
                || type == AgentEventType.TOOL_CALL_END
                || type == AgentEventType.TOOL_RESULT_START
                || type == AgentEventType.TOOL_RESULT_TEXT_DELTA
                || type == AgentEventType.TOOL_RESULT_DATA_DELTA
                || type == AgentEventType.TOOL_RESULT_END
                || type == AgentEventType.HINT_BLOCK;
    }

    private String extractVisibleDelta(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getDelta();
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            // 模型思考块增量：仍进 TokenChunk；前端是否显示为思考气泡由 bizParam attrs 决定
            return thinking.getDelta();
        }
        if (event instanceof AgentResultEvent result && aggregated.isEmpty()) {
            Msg msg = result.getResult();
            return msg != null ? msg.getTextContent() : null;
        }
        return null;
    }
}
