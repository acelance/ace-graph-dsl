package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.acelance.graph.dsl.streaming.GraphStreamBridge;
import io.acelance.graph.dsl.streaming.TokenChunk;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

/**
 * AGENTSCOPE 内层流式辅助：StreamOptions、可见事件判定、TokenChunk emit（G-STREAM-INNER）。
 *
 * <p>§0.5：模型可见文本（REASONING/SUMMARY/AGENT_RESULT）全量进 bridge；TOOL/HINT 仅打日志。</p>
 */
public final class AgentScopeStreamSupport {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeStreamSupport.class);

    private AgentScopeStreamSupport() {
    }

    /**
     * 面向用户可见文本的 StreamOptions：开 reasoning + summary chunk（§0.5 全推）。
     */
    public static StreamOptions visibleAnswerOptions(boolean includeReasoningChunk) {
        return StreamOptions.builder()
                .incremental(true)
                .includeReasoningChunk(includeReasoningChunk)
                .includeReasoningResult(false)
                .includeActingChunk(false)
                .includeSummaryChunk(true)
                .includeSummaryResult(false)
                .build();
    }

    /** §0.5 默认：开 reasoning chunk，保证终答走 REASONING 时不空。 */
    public static StreamOptions visibleAnswerOptions() {
        return visibleAnswerOptions(true);
    }

    /**
     * 是否具备真正推流条件（非 NOOP bridge + 非空 runId）。
     */
    public static boolean canStream(GraphStreamBridge bridge, String runId) {
        return bridge != null
                && bridge != GraphStreamBridge.NOOP
                && StringUtils.hasText(runId);
    }

    /**
     * 该 AS Event 是否应打到用户可见 TokenChunk（M2 口径）。
     */
    public static boolean isUserVisibleTextEvent(Event event) {
        if (event == null || event.getType() == null) {
            return false;
        }
        EventType type = event.getType();
        if (type == EventType.TOOL_RESULT || type == EventType.HINT) {
            return false;
        }
        return type == EventType.REASONING
                || type == EventType.SUMMARY
                || type == EventType.AGENT_RESULT;
    }

    /**
     * 取 Event 文本；空则 null。
     */
    public static String eventText(Event event) {
        if (event == null) {
            return null;
        }
        Msg msg = event.getMessage();
        if (msg == null) {
            return null;
        }
        String text = msg.getTextContent();
        return StringUtils.hasText(text) ? text : null;
    }

    /**
     * 推送流式 token（父 nodeId / kind / attrs）。
     */
    public static void emitStreaming(GraphStreamBridge bridge,
                                     String runId,
                                     String parentNodeId,
                                     String responseKind,
                                     Map<String, Object> attrs,
                                     String token) {
        Objects.requireNonNull(bridge, "bridge");
        if (!StringUtils.hasText(token)) {
            return;
        }
        bridge.emit(runId, new TokenChunk(
                parentNodeId, token, OutputType.AGENT_MODEL_STREAMING, responseKind, false, attrs));
    }

    /**
     * 推送本段结束标记。
     */
    public static void emitFinished(GraphStreamBridge bridge,
                                    String runId,
                                    String parentNodeId,
                                    String responseKind,
                                    Map<String, Object> attrs) {
        Objects.requireNonNull(bridge, "bridge");
        bridge.emit(runId, new TokenChunk(
                parentNodeId, "", OutputType.AGENT_MODEL_FINISHED, responseKind, true, attrs));
    }

    /**
     * 工具/提示事件打关键日志（不进用户可见流）。
     */
    public static void logNonVisibleEvent(String graphId, String parentNodeId, String subName, Event event) {
        if (event == null) {
            return;
        }
        EventType type = event.getType();
        String text = eventText(event);
        int len = text != null ? text.length() : 0;
        log.info("AGENTSCOPE 非可见流事件, bridge={}, graphId={}, parentNodeId={}, subName={}, type={}, isLast={}, textLen={}",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, graphId, parentNodeId, subName,
                type, event.isLast(), len);
    }
}
