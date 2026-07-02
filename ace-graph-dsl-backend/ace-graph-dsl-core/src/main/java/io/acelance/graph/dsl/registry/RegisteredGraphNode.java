package io.acelance.graph.dsl.registry;

import com.alibaba.cloud.ai.graph.action.NodeAction;

/**
 * 可注册的图节点接口。所有业务节点实现此接口并声明为 Spring {@code @Component}，
 * 由 {@link GraphNodeRegistry} 自动发现。
 */
public interface RegisteredGraphNode {

    /** 返回节点元数据描述 */
    GraphNodeDescriptor descriptor();

    /** 根据运行时上下文创建 NodeAction 实例 */
    NodeAction toAction(NodeRuntimeContext ctx);
}
