package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * 节点引用：DSL 中对已注册节点的引用，可携带配置属性与画布坐标。
 *
 * <p>除普通业务节点外，也支持两类结构性节点：
 * <ul>
 *   <li><b>SUBGRAPH</b>：通过 {@link #subgraph()} 内嵌一张 {@link GraphDefinition}，
 *       或 {@link #subgraphRef()} 引用目录中另一张图（graph-in-graph）。可视化上它是一个
 *       "可点击跳转到另一张图"的图节点，不直接在当前画布内联展开。</li>
 *   <li><b>AGENT</b>：通过 {@link #agent()} 携带 agent 循环配置（subagent 内核）。
 *       因 agent 的"智能"属于代码逻辑而非图结构，DSL 仅做"挂载 + 嵌套 + 标注"，
 *       循环体以脚本/已注册动作形式存在（代码岛），反向提取时可能失真。</li>
 * </ul>
 *
 * @param nodeId     已注册节点 ID（SUBGRAPH/AGENT 等结构节点也用此字段作为唯一标识）
 * @param category   节点类别：NORMAL/ROUTER/MERGE/HITL/SUBGRAPH/AGENT（可选；null 时由注册中心推导）
 * @param config     节点配置属性
 * @param x          画布横坐标（可选）
 * @param y          画布纵坐标（可选）
 * @param subgraph   内嵌子图定义（仅 SUBGRAPH 节点使用，可选）
 * @param subgraphRef 引用的目录图 ID（仅 SUBGRAPH 节点使用，可选；与 subgraph 二选一）
 * @param agent      agent 循环配置（仅 AGENT 节点使用，可选）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeRef(
        String nodeId,
        String category,
        Map<String, Object> config,
        Double x,
        Double y,
        GraphDefinition subgraph,
        String subgraphRef,
        AgentConfig agent
) {

    /** 向后兼容：仅含 nodeId/config/x/y 的构造（测试与旧 JSON 用） */
    public NodeRef(String nodeId, Map<String, Object> config, Double x, Double y) {
        this(nodeId, null, config, x, y, null, null, null);
    }

    /** 是否子图节点（类别或携带子图数据任一满足）。非 JSON 属性，避免与 subgraph 组件冲突。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSubgraph() {
        return "SUBGRAPH".equals(category) || subgraph != null || subgraphRef != null;
    }

    /** 是否 agent 节点（类别或携带 agent 配置任一满足）。非 JSON 属性，避免与 agent 组件冲突。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAgent() {
        return "AGENT".equals(category) || agent != null;
    }
}
