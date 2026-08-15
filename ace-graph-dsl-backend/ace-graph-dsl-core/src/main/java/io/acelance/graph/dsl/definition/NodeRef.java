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
 *   <li><b>GENERIC_AGENT</b>：通过 {@link #agentSpec()} 携带声明式元数据
 *       （模型/prompt/skill/mcp/tools），后端据此动态装配模板节点，一般模型调用无需内嵌代码。</li>
 * </ul>
 *
 * @param nodeId     已注册节点 ID（SUBGRAPH/AGENT/GENERIC_AGENT 等结构节点也用此字段作为唯一标识）
 * @param category   节点类别：NORMAL/ROUTER/MERGE/HITL/SUBGRAPH/AGENT/GENERIC_AGENT（可选；null 时由注册中心推导）
 * @param config     节点配置属性
 * @param x          画布横坐标（可选）
 * @param y          画布纵坐标（可选）
 * @param subgraph   内嵌子图定义（仅 SUBGRAPH 节点使用，可选）
 * @param subgraphRef 引用的目录图 ID（仅 SUBGRAPH 节点使用，可选；与 subgraph 二选一）
 * @param agent      agent 循环配置（仅 AGENT 节点使用，可选）
 * @param agentSpec  通用 agent 节点元数据（仅 GENERIC_AGENT 节点使用，可选）
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
        AgentConfig agent,
        GenericAgentSpec agentSpec
) {

    /** 向后兼容：仅含 nodeId/config/x/y 的构造（测试与旧 JSON 用） */
    public NodeRef(String nodeId, Map<String, Object> config, Double x, Double y) {
        this(nodeId, null, config, x, y, null, null, null, null);
    }

    /**
     * 是否子图节点（类别或携带子图数据任一满足）。
     * 注意：方法名不能叫 isSubgraph()，否则会与 record 组件访问器 subgraph() 在
     * Jackson 中冲突为同一 JSON 属性 "subgraph"，导致 @JsonIgnore 把真正的
     * subgraph 字段一并丢弃（内联子图保存后丢失）。故命名为 hasSubgraph()。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasSubgraph() {
        return "SUBGRAPH".equals(category)
                || subgraph != null
                || (subgraphRef != null && !subgraphRef.isBlank());
    }

    /** 是否 agent 节点（类别或携带 agent 配置任一满足）。同理避免与 agent 组件冲突，命名为 hasAgent()。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasAgent() {
        return "AGENT".equals(category) || agent != null;
    }

    /** 是否通用 agent 节点（类别或携带 agentSpec 任一满足）。同理避免与 agentSpec 组件冲突，命名为 hasAgentSpec()。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasAgentSpec() {
        return "GENERIC_AGENT".equals(category) || agentSpec != null;
    }

    /**
     * 解析 {@code subgraphRef} 中的图 ID（剥离 {@code @version} 后缀）。
     * <p>支持两种格式：
     * <ul>
     *   <li>{@code "order-flow"} → 返回 {@code "order-flow"}（取最新版本）</li>
     *   <li>{@code "order-flow@1.2.0"} → 返回 {@code "order-flow"}（锁定 1.2.0）</li>
     * </ul>
     * 用于循环引用检测、仓库加载等需要纯 graphId 的场景。
     *
     * @param subgraphRef 子图引用字符串（可能含 {@code @version} 后缀）
     * @return 纯 graphId；入参为 null 返回 null
     */
    public static String graphIdOf(String subgraphRef) {
        if (subgraphRef == null) {
            return null;
        }
        int at = subgraphRef.indexOf('@');
        return at > 0 ? subgraphRef.substring(0, at) : subgraphRef;
    }

    /**
     * 解析 {@code subgraphRef} 中锁定的版本号。
     * <p>{@code "order-flow@1.2.0"} → {@code "1.2.0"}；
     * {@code "order-flow"} → {@code null}（表示取最新版本）。
     *
     * @param subgraphRef 子图引用字符串
     * @return 版本号；无 {@code @} 后缀或后缀为空时返回 {@code null}
     */
    public static String versionOf(String subgraphRef) {
        if (subgraphRef == null) {
            return null;
        }
        int at = subgraphRef.indexOf('@');
        if (at <= 0 || at >= subgraphRef.length() - 1) {
            return null;
        }
        return subgraphRef.substring(at + 1);
    }
}
