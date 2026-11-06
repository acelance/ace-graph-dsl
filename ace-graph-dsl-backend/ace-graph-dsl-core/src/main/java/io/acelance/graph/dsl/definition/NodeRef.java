package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * 节点引用：DSL 中对已注册节点的引用，可携带配置属性与画布坐标。
 *
 * <p>除普通业务节点外，也支持结构性节点：
 * <ul>
 *   <li><b>SUBGRAPH</b>：通过 {@link #subgraph()} 内嵌或 {@link #subgraphRef()} 引用。</li>
 *   <li><b>AGENT</b>：通过 {@link #agent()} 携带 agent 循环配置。</li>
 *   <li><b>GENERIC_AGENT</b>：通过 {@link #agentSpec()} 携带声明式元数据。</li>
 *   <li><b>SAA_WORKFLOW</b>：通过 {@link #saaSpec()} 携带高阶模式与子 Agent 引用。</li>
 *   <li><b>HITL</b>：通过 {@link #hitlSpec()} 声明人工注入键；与 {@code interruptBefore} 正交但发布时自动并入。</li>
 * </ul>
 *
 * @param nodeId     画布节点唯一标识
 * @param category   节点类别：NORMAL/ROUTER/MERGE/HITL/SUBGRAPH/AGENT/GENERIC_AGENT/SAA_WORKFLOW
 * @param config     节点配置属性
 * @param x          画布横坐标（可选）
 * @param y          画布纵坐标（可选）
 * @param subgraph   内嵌子图定义（仅 SUBGRAPH）
 * @param subgraphRef 引用的目录图 ID（仅 SUBGRAPH）
 * @param agent      agent 循环配置（仅 AGENT）
 * @param agentSpec  通用 agent 元数据（仅 GENERIC_AGENT）
 * @param saaSpec    SAA 高阶工作流规格（仅 SAA_WORKFLOW）
 * @param hitlSpec   HITL 人工审核规格（仅 HITL）
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
        GenericAgentSpec agentSpec,
        SaaWorkflowSpec saaSpec,
        HitlNodeSpec hitlSpec
) {

    /** 向后兼容：仅含 nodeId/config/x/y 的构造（测试与旧 JSON 用） */
    public NodeRef(String nodeId, Map<String, Object> config, Double x, Double y) {
        this(nodeId, null, config, x, y, null, null, null, null, null, null);
    }

    /**
     * 兼容旧测试：不含 hitlSpec 的全参构造。
     */
    public NodeRef(String nodeId, String category, Map<String, Object> config, Double x, Double y,
                   GraphDefinition subgraph, String subgraphRef, AgentConfig agent,
                   GenericAgentSpec agentSpec, SaaWorkflowSpec saaSpec) {
        this(nodeId, category, config, x, y, subgraph, subgraphRef, agent, agentSpec, saaSpec, null);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasSubgraph() {
        return "SUBGRAPH".equals(category)
                || subgraph != null
                || (subgraphRef != null && !subgraphRef.isBlank());
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasAgent() {
        return "AGENT".equals(category) || agent != null;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasAgentSpec() {
        return "GENERIC_AGENT".equals(category) || agentSpec != null;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasSaaSpec() {
        return "SAA_WORKFLOW".equals(category) || saaSpec != null;
    }

    /**
     * 是否 HITL 节点（类别或携带 hitlSpec 任一满足）。
     * 命名为 hasHitlSpec()，避免与 record 组件访问器冲突。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasHitlSpec() {
        return "HITL".equals(category) || hitlSpec != null;
    }

    public static String graphIdOf(String subgraphRef) {
        if (subgraphRef == null) {
            return null;
        }
        int at = subgraphRef.indexOf('@');
        return at > 0 ? subgraphRef.substring(0, at) : subgraphRef;
    }

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
