package io.acelance.graph.dsl.agent;

/**
 * 子 Agent 解析 SPI：按 {@code impl} 把 {@code ref} 解析为可挂入 FlowAgent 的句柄。
 *
 * <p>多个实现以 Spring {@code List<SubAgentResolver>} 注入；无匹配器时编译失败。</p>
 */
public interface SubAgentResolver {

    /**
     * 是否支持该 impl（如 {@code GENERIC_AGENT} / {@code AGENTSCOPE}）。
     *
     * @param impl 子 Agent 实现类型
     * @return true 可解析
     */
    boolean supports(String impl);

    /**
     * 解析子 Agent。
     *
     * @param request 解析请求
     * @return 绑定结果（含可执行句柄）
     * @throws IllegalArgumentException 引用不存在或规格非法
     */
    SubAgentBinding resolve(SubAgentResolveRequest request);
}
