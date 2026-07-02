package io.acelance.graph.dsl.registry;

import com.alibaba.cloud.ai.graph.action.EdgeAction;

import java.util.Set;

/**
 * 可注册的条件边分发器接口。所有 Dispatcher 实现此接口并声明为 Spring {@code @Component}。
 */
public interface RegisteredEdgeDispatcher {

    /** 返回 dispatcher 唯一标识，如 "inquiryDispatcher" */
    String dispatcherId();

    /** 返回所有可能的目标节点 ID，用于前端 mapping 校验 */
    Set<String> possibleTargets();

    /** 根据运行时上下文创建 EdgeAction 实例 */
    EdgeAction toAction(NodeRuntimeContext ctx);
}
