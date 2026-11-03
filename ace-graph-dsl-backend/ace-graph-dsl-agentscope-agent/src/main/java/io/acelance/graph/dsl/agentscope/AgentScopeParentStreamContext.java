package io.acelance.graph.dsl.agentscope;

import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.streaming.SaaStreamAttrs;

import java.util.Map;

/**
 * 父 SAA 上下文（G-STREAM-INNER）。
 *
 * <p>两条<strong>正交</strong>能力分字段存放，禁止混用：</p>
 * <ul>
 *   <li>{@code streamResponseKind} + {@code streamAttrs}：可见流挂父 + <strong>业务</strong> attrs（前端/解释器）</li>
 *   <li>{@code applyDeepThinking}：是否允许把请求级深思<strong>交给模型</strong>（∧ state）</li>
 * </ul>
 *
 * @param streamResponseKind TokenChunk.responseKind
 * @param streamAttrs        TokenChunk.attrs（仅 enableBizParams 时非空）
 * @param applyDeepThinking  父节点是否允许应用请求级深度思考（模型侧闸门）
 */
public record AgentScopeParentStreamContext(
        String streamResponseKind,
        Map<String, Object> streamAttrs,
        boolean applyDeepThinking
) {

    /** 空上下文（兼容旧构造 / 无父 Spec）。 */
    public static final AgentScopeParentStreamContext EMPTY =
            new AgentScopeParentStreamContext(null, Map.of(), false);

    public AgentScopeParentStreamContext {
        streamAttrs = (streamAttrs == null || streamAttrs.isEmpty()) ? Map.of() : Map.copyOf(streamAttrs);
    }

    /**
     * 从父 SAA Spec 构造；parent 为空时返回 {@link #EMPTY}。
     */
    public static AgentScopeParentStreamContext fromParent(SaaWorkflowSpec parent) {
        if (parent == null) {
            return EMPTY;
        }
        return new AgentScopeParentStreamContext(
                parent.streamResponseKind(),
                SaaStreamAttrs.fromSpec(parent),
                parent.applyDeepThinking());
    }
}
