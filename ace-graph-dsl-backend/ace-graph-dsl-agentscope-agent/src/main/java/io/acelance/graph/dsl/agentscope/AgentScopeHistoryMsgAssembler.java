package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * P0：AGENTSCOPE 子 Agent 入模前，只读组装跨会话历史多轮 {@link Msg}（方案①）。
 *
 * <p>框架中性钩子：不硬编码业务 extras 键；无 Bean 时 Adapter 仅传本轮 USER + WARN。
 * 宿主（Lesso）实现从 ChatMemory 取历史并做材料 enrich。</p>
 *
 * <p><b>不写</b> ChatMemory（保持 Q5）；System 仍走子 Agent sysPrompt，不进入返回的 historyMsgs。</p>
 */
@FunctionalInterface
public interface AgentScopeHistoryMsgAssembler {

    /**
     * 组装历史轮 Msg + 对本轮用户文本做材料 enrich。
     *
     * @param request 会话与本轮渲染文本
     * @return 历史列表（旧→新）与 enrich 后的本轮文本；不可用时返回 {@link Result#passthrough(String)}
     */
    Result assemble(Request request);

    /**
     * @param conversationId           会话 id（通常 ≡ sessionId）
     * @param graphId                  父图
     * @param parentNodeId             父 SAA 节点
     * @param agentId                  子 Agent 注册 id
     * @param agentCode                执行入口 agentCode
     * @param state                    当前图/Flow state（可读 media_refs 等）
     * @param renderedCurrentUserText  instruction 渲染后的本轮用户文本
     */
    record Request(String conversationId,
                   String graphId,
                   String parentNodeId,
                   String agentId,
                   String agentCode,
                   OverAllState state,
                   String renderedCurrentUserText) {
        public Request {
            renderedCurrentUserText = renderedCurrentUserText == null ? "" : renderedCurrentUserText;
        }
    }

    /**
     * @param historyMsgs               历史 USER/ASSISTANT Msg（不含本轮）；不可变
     * @param enrichedCurrentUserText   本轮用户文本（可含材料注记）
     * @param historyCount              原始历史条数（截断前）
     * @param materialNotes             本轮 enrich 追加的材料注记条数（估）
     */
    record Result(List<Msg> historyMsgs,
                  String enrichedCurrentUserText,
                  int historyCount,
                  int materialNotes) {

        public Result {
            historyMsgs = historyMsgs == null ? List.of() : List.copyOf(historyMsgs);
            enrichedCurrentUserText = enrichedCurrentUserText == null ? "" : enrichedCurrentUserText;
        }

        /** 无历史 / 跳过：原样本轮文本。 */
        public static Result passthrough(String renderedCurrentUserText) {
            return new Result(List.of(),
                    renderedCurrentUserText == null ? "" : renderedCurrentUserText,
                    0, 0);
        }

        public int historyMsgSize() {
            return historyMsgs.size();
        }
    }
}
