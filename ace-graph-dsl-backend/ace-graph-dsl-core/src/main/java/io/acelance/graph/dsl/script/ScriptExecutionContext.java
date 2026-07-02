package io.acelance.graph.dsl.script;

import java.util.Map;

/**
 * 脚本执行上下文（白名单暴露给脚本引擎）。
 *
 * @param state  当前 state 快照（仅含 inputKeys）
 * @param config 节点实例配置（来自 NodeRef.config）
 */
public record ScriptExecutionContext(
        Map<String, Object> state,
        Map<String, Object> config
) {
}
