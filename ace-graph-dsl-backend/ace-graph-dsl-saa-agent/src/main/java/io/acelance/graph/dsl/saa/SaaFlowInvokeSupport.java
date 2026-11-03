package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * SAA 内层 {@code Agent.invoke} 的 {@link RunnableConfig} 组装。
 *
 * <p>与父图 Generic / {@code AceGraphExecutionController} 对齐：
 * 父图 {@code RunnableConfig.threadId} ≡ {@code ace.graph.dsl.runId}（每轮请求 UUID）；
 * ChatMemory 仍用 {@code ace.graph.dsl.conversationId}（≡ sessionId）。
 * SAA FlowAgent 是独立 CompiledGraph，必须显式传 threadId，否则多会话会串 checkpoint / 子键（如 step2）。</p>
 */
final class SaaFlowInvokeSupport {

    private static final Logger log = LoggerFactory.getLogger(SaaFlowInvokeSupport.class);

    private SaaFlowInvokeSupport() {
    }

    /**
     * 解析 SAA 内层 invoke 用的 threadId，并组装 {@link RunnableConfig}。
     *
     * <ol>
     *   <li>优先 {@link ModelOverrideSpec#ACE_RUN_ID_KEY}（与父图 threadId 同值）</li>
     *   <li>否则 {@link LlmRequestContext#ACE_CONVERSATION_ID_KEY}（session 隔离兜底）</li>
     *   <li>再否则新建 UUID 并 WARN</li>
     * </ol>
     *
     * @param state   父图 OverAllState（可空）
     * @param graphId 图 ID（日志）
     * @param nodeId  节点 ID（日志）
     * @return 非空 config（必含 threadId）
     */
    static RunnableConfig buildInvokeConfig(OverAllState state, String graphId, String nodeId) {
        String runId = readStateString(state, ModelOverrideSpec.ACE_RUN_ID_KEY);
        String conversationId = readStateString(state, LlmRequestContext.ACE_CONVERSATION_ID_KEY);

        String threadId;
        String source;
        if (StringUtils.hasText(runId)) {
            threadId = runId.trim();
            source = "runId";
        } else if (StringUtils.hasText(conversationId)) {
            threadId = conversationId.trim();
            source = "conversationId";
            log.warn("SAA invoke threadId 回退 conversationId（父 state 缺 runId）: graphId={}, nodeId={}, "
                            + "threadId={}",
                    graphId, nodeId, threadId);
        } else {
            threadId = UUID.randomUUID().toString().replace("-", "");
            source = "generated";
            log.warn("SAA invoke threadId 已临时生成（缺 runId/conversationId）: graphId={}, nodeId={}, "
                            + "threadId={}",
                    graphId, nodeId, threadId);
        }

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        log.info("SAA invoke RunnableConfig: graphId={}, nodeId={}, threadId={}, source={}, "
                        + "runId={}, conversationId={}",
                graphId, nodeId, threadId, source, runId, conversationId);
        return config;
    }

    private static String readStateString(OverAllState state, String key) {
        if (state == null || key == null) {
            return null;
        }
        try {
            Object v = state.value(key).orElse(null);
            return v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null;
        } catch (RuntimeException ex) {
            log.warn("SAA 读取 state 键失败: key={}, err={}", key, ex.toString());
            return null;
        }
    }
}
