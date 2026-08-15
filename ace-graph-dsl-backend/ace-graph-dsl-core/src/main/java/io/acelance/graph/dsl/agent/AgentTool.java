package io.acelance.graph.dsl.agent;

import java.util.Map;

/**
 * 通用 agent 节点的工具抽象（core 本地接口，隔离 spring-ai ToolCallback 类型，
 * 避免 core 依赖具体模型实现）。
 */
public interface AgentTool {

    /** 工具名（与 spec.tools 中的名称对应） */
    String name();

    /** 以参数调用工具，返回结果字符串 */
    String call(Map<String, Object> args);
}
