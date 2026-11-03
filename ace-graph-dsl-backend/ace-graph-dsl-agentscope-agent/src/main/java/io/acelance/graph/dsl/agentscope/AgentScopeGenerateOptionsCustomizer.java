package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.agentscope.core.model.GenerateOptions;

/**
 * AS 模型 {@link GenerateOptions} 定制 SPI（与 {@code LlmChatOptionsCustomizer} 正交）。
 *
 * <p><strong>仅</strong>负责把「深度思考」等<strong>请求模型</strong>参数写入 GenerateOptions
 * （如 extraBody {@code enable_thinking}）。<strong>禁止</strong>在此根据 deepThinking 决定
 * TokenChunk / SSE 是否走思考气泡——那是 bizParam attrs + 业务解释器的职责。</p>
 *
 * @see io.acelance.graph.dsl.ai.options.LlmChatOptionsCustomizer GENERIC 路径对等物
 */
@FunctionalInterface
public interface AgentScopeGenerateOptionsCustomizer {

    /**
     * @param deepThinking 有效深思：节点 {@code applyDeepThinking} ∧ state {@code ace.graph.dsl.deepThinking}
     * @param state        当前图 state，可空
     * @return 挂到 {@code ReActAgent.generateOptions}；null = 不附加
     */
    GenerateOptions customize(boolean deepThinking, OverAllState state);
}
