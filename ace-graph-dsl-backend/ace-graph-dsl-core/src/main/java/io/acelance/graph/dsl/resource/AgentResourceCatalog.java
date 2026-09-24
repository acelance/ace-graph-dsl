package io.acelance.graph.dsl.resource;

import java.util.List;

/**
 * 设计期资源 Catalog SPI（§7.2）：只按 agentCode 圈大盘，不传节点 keys。
 *
 * <p>MCP（{@link ResourceType#MCP}）：server 条目可通过 {@link ResourceItem#children()}
 * 返回工具子节点，供 UI 三级树勾选；不实现或返回空列表时 UI 显示空树并回落手填。</p>
 *
 * <p>{@code otherBizParams} 为嵌入透传的不透明字符串，业务可读可忽略；框架不解析。</p>
 */
public interface AgentResourceCatalog {

    ResourceType type();

    /**
     * @param agentCode  智能体产品编码（可空，业务自行决定空/全量）
     * @param graphId    可选
     * @param agentDefId 可选，注册式节点定义 id
     */
    List<ResourceItem> list(String agentCode, String graphId, String agentDefId);

    /**
     * 带嵌入透传参数的列表查询。默认忽略 {@code otherBizParams}，委托三参 {@link #list}。
     *
     * @param otherBizParams 可选；UTF-8 ≤ 4096；业务自定协议
     */
    default List<ResourceItem> list(String agentCode, String graphId, String agentDefId,
                                    String otherBizParams) {
        return list(agentCode, graphId, agentDefId);
    }
}
