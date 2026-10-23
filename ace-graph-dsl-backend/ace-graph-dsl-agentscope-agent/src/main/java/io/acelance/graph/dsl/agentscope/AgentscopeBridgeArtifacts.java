package io.acelance.graph.dsl.agentscope;

/**
 * 路径 B 桥接坐标（探测字段 / 日志）。当前是什么就写什么，禁止沿用旧 starter 文案。
 */
public final class AgentscopeBridgeArtifacts {

    /**
     * 自研 Adapter 模块 + 运行时 agentscope-core GAV（与父 POM {@code agentscope.version} 对齐）。
     */
    public static final String BRIDGE_ARTIFACT =
            "ace-graph-dsl-agentscope-agent@io.agentscope:agentscope-core:2.0.4";

    private AgentscopeBridgeArtifacts() {
    }
}
