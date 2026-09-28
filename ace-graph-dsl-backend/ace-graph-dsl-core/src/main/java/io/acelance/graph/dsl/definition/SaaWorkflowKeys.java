package io.acelance.graph.dsl.definition;

/**
 * SAA 高阶节点运行时保留键（试运行子步骤轨迹等）。
 *
 * <p>不扩展 SSE（Q6）：轨迹写入 OverAllState，由 dry-run / debug_node.data 展示。</p>
 */
public final class SaaWorkflowKeys {

    /**
     * 父节点写回的子步骤摘要列表（List&lt;Map&gt;）。
     * 元素字段：name / outputKey / status / costMs / preview / error（可选）。
     */
    public static final String SUB_STEPS_KEY = "ace.graph.dsl.saa.subSteps";

    /**
     * 父节点元信息（Map）：pattern / costMs / nodeId。
     */
    public static final String SUB_STEPS_META_KEY = "ace.graph.dsl.saa.subStepsMeta";

    private SaaWorkflowKeys() {
    }
}
