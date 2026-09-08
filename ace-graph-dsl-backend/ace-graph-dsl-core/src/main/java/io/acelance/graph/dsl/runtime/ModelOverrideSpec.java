package io.acelance.graph.dsl.runtime;

import java.util.Map;

/**
 * 请求级模型覆盖规格：全局覆盖 + 按 {@code nodeId} 精确覆盖。
 *
 * <p>由各执行入口（如 {@code GraphExecutionController}）在组装初始 {@code OverAllState} 时，
 * 以保留键 {@link #ACE_MODEL_OVERRIDES_KEY} 注入；随后由
 * {@link io.acelance.graph.dsl.agent.GenericAgentNode} 从 state 保留键读取并在每次 LLM
 * 调用前解析生效。走 state 而非 {@code RunnableConfig.metadata} 是为了让异步扇出分支
 * （其 RunnableConfig 不携带 threadId）也能延续同一覆盖。
 * 优先级：nodeId 精确覆盖 &gt; 全局覆盖 &gt; 图定义静态值。</p>
 */
public record ModelOverrideSpec(
        ModelOverride global,
        Map<String, ModelOverride> nodeOverrides
) {

    /** metadata 透传键（{@code RunnableConfig.metadata} 中的固定 key） */
    public static final String ACE_MODEL_OVERRIDES_KEY = "ace.graph.dsl.modelOverrides";

    /** 运行态保留键：本次执行 runId（注入初始 state，用于与 Langfuse trace 对齐） */
    public static final String ACE_RUN_ID_KEY = "ace.graph.dsl.runId";

    /** 上述两个保留键的前缀，便于在输出中剔除（避免泄漏到最终结果） */
    public static final String ACE_RESERVED_PREFIX = "ace.graph.dsl.";

    public ModelOverrideSpec {
        if (nodeOverrides == null) {
            nodeOverrides = Map.of();
        }
    }

    /**
     * 解析某节点的最终覆盖。
     * @param nodeId 节点 ID
     * @return 生效的覆盖（node 级优先，否则全局），都没有返回 {@code null}
     */
    public ModelOverride effectiveFor(String nodeId) {
        if (nodeOverrides != null && nodeId != null) {
            ModelOverride nv = nodeOverrides.get(nodeId);
            if (nv != null && !nv.isEmpty()) {
                return nv;
            }
        }
        return (global != null && !global.isEmpty()) ? global : null;
    }

    /** 是否携带任何有效覆盖 */
    public boolean hasAny() {
        if (global != null && !global.isEmpty()) {
            return true;
        }
        if (nodeOverrides != null) {
            for (ModelOverride v : nodeOverrides.values()) {
                if (v != null && !v.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
