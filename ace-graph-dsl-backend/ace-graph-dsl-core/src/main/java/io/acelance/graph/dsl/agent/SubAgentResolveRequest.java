package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.SaaSubAgentRef;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;

/**
 * 子 Agent 解析请求（编译期由 Factory 构造）。
 *
 * @param graphId      当前图 ID
 * @param parentNodeId 高阶节点 nodeId（日志）
 * @param ref          子 Agent 绑定
 * @param parent       父节点 saaSpec
 */
public record SubAgentResolveRequest(
        String graphId,
        String parentNodeId,
        SaaSubAgentRef ref,
        SaaWorkflowSpec parent
) {
}
