package io.acelance.graph.dsl.llm;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.resource.ResourceBinding;

import java.util.Objects;

/**
 * 一次 Agent / Template 调用的请求上下文。
 *
 * <p>{@code agentCode} 须由执行入口写入 state 保留键 {@link #ACE_AGENT_CODE_KEY}，
 * 节点只读不猜。</p>
 */
public record LlmRequestContext(
        String agentCode,
        String graphId,
        String nodeId,
        String runId,
        OverAllState state,
        ResourceBinding binding
) {
    /** state 保留键：智能体产品入口编码，整次 run 不变 */
    public static final String ACE_AGENT_CODE_KEY = "ace.graph.dsl.agentCode";

    /** state 保留键：强制预激活的 skill key 列表 */
    public static final String ACE_FORCE_SKILLS_KEY = "ace.graph.dsl.forceSkills";

    public LlmRequestContext {
        Objects.requireNonNull(binding, "ResourceBinding 不能为空");
        agentCode = agentCode == null ? "" : agentCode;
        graphId = graphId == null ? "" : graphId;
        nodeId = nodeId == null ? "" : nodeId;
        runId = runId == null ? "" : runId;
    }
}
