package io.acelance.graph.dsl.llm;

/**
 * 执行期临时持有入口 {@code agentCode}（ThreadLocal）。
 *
 * <p>用于 SAA ROUTING 等在<strong>编译期</strong>已烘焙 {@code ChatModel} 句柄、
 * 但真正 ModelMount 必须等到执行入口写入身份的场景：
 * 调用方在 {@code FlowAgent.invoke} 前 {@link #set}，
 * 延迟 {@code ChatModel} 在 {@code call}/{@code stream} 时 {@link #current}。</p>
 *
 * <p>与 state 保留键 {@link LlmRequestContext#ACE_AGENT_CODE_KEY} 同源；
 * Holder 仅作跨框架边界（SAA RoutingNode 拿不到 OverAllState）的桥梁。</p>
 */
public final class AceGraphAgentCodeHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private AceGraphAgentCodeHolder() {
    }

    /**
     * 绑定当前线程的 agentCode（空/空白视为清除）。
     *
     * @param agentCode 执行入口身份；可空
     */
    public static void set(String agentCode) {
        if (agentCode == null || agentCode.isBlank()) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(agentCode.trim());
    }

    /**
     * @return 当前线程 agentCode；未绑定则 null
     */
    public static String current() {
        return HOLDER.get();
    }

    /** 清除当前线程绑定（须在 finally 调用）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
