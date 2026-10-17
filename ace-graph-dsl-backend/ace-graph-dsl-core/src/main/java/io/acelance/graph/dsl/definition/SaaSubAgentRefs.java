package io.acelance.graph.dsl.definition;

/**
 * 子 Agent {@code impl} / {@code ref} 约定常量与解析工具（避免魔法值）。
 */
public final class SaaSubAgentRefs {

    /** 默认实现：包装 ACE GenericAgent */
    public static final String IMPL_GENERIC_AGENT = "GENERIC_AGENT";
    /** 可选实现：AgentScope（M3） */
    public static final String IMPL_AGENTSCOPE = "AGENTSCOPE";

    /** GENERIC_AGENT 引用前缀 */
    public static final String PREFIX_GENERIC = "generic:";
    /** AGENTSCOPE 引用前缀 */
    public static final String PREFIX_AGENTSCOPE = "agentscope:";

    private SaaSubAgentRefs() {
    }

    /**
     * 从 {@code generic:{id}} 解析注册节点 ID；格式非法返回 null。
     *
     * @param ref 子 Agent ref
     * @return 节点 ID；非 generic 前缀或空 id 时 null
     */
    public static String parseGenericNodeId(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX_GENERIC, 0, PREFIX_GENERIC.length())) {
            return null;
        }
        String id = trimmed.substring(PREFIX_GENERIC.length()).trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * 从 {@code agentscope:{id}} 解析 id；格式非法返回 null。
     *
     * @param ref 子 Agent ref
     * @return id；非前缀或空时 null
     */
    public static String parseAgentscopeId(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX_AGENTSCOPE, 0, PREFIX_AGENTSCOPE.length())) {
            return null;
        }
        String id = trimmed.substring(PREFIX_AGENTSCOPE.length()).trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * 校验 ref 前缀与 impl 是否一致。
     *
     * @param impl 实现类型
     * @param ref  引用
     * @return true 一致
     */
    public static boolean refMatchesImpl(String impl, String ref) {
        String effective = (impl == null || impl.isBlank()) ? IMPL_GENERIC_AGENT : impl.trim();
        if (IMPL_GENERIC_AGENT.equalsIgnoreCase(effective)) {
            return parseGenericNodeId(ref) != null;
        }
        if (IMPL_AGENTSCOPE.equalsIgnoreCase(effective)) {
            return parseAgentscopeId(ref) != null;
        }
        return false;
    }
}
