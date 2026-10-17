package io.acelance.graph.dsl.agent;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.definition.SaaWorkflowSpec;
import io.acelance.graph.dsl.registry.NodeRuntimeContext;

/**
 * SAA 高阶工作流节点工厂 SPI（core 定义；实现在可选模块 ace-graph-dsl-saa-agent）。
 *
 * <p>未引入实现模块时，{@code DynamicGraphBuilder} 遇 {@code SAA_WORKFLOW} 给出可操作报错。</p>
 */
public interface SaaWorkflowNodeFactory {

    /**
     * 按规格组装 FlowAgent，并以方式 A（{@link NodeAction}）返回可挂载动作。
     *
     * @param graphId 图 ID
     * @param nodeId  节点 ID
     * @param spec    高阶规格
     * @param ctx     运行时上下文（Spring / node.config）
     * @return 可挂入 StateGraph 的 NodeAction
     */
    NodeAction create(String graphId, String nodeId, SaaWorkflowSpec spec, NodeRuntimeContext ctx);
}
