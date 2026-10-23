package io.acelance.graph.dsl.agentscope;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

/**
 * G-MCP：一次工具装配结果（ACE {@link ToolCallback} 列表 + 可选 sysPrompt 后缀）。
 *
 * @param callbacks       已去重、模型可见名的回调
 * @param sysPromptSuffix Skill L1 等追加文案（可空）
 * @param skillToolCount  来自 Skill 的工具数（诊断）
 * @param mcpToolCount    来自 MCP 的工具数
 * @param localToolCount  来自 localTools 的工具数
 */
public record AgentScopeToolAssemblyResult(
        List<ToolCallback> callbacks,
        String sysPromptSuffix,
        int skillToolCount,
        int mcpToolCount,
        int localToolCount
) {
    public AgentScopeToolAssemblyResult {
        callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
        sysPromptSuffix = sysPromptSuffix == null ? "" : sysPromptSuffix;
    }

    /** 无工具空结果。 */
    public static AgentScopeToolAssemblyResult empty() {
        return new AgentScopeToolAssemblyResult(List.of(), "", 0, 0, 0);
    }

    public int totalToolCount() {
        return callbacks.size();
    }

    public boolean hasTools() {
        return !callbacks.isEmpty();
    }

    @Override
    public String toString() {
        return "AgentScopeToolAssemblyResult{total=" + totalToolCount()
                + ", skill=" + skillToolCount
                + ", mcp=" + mcpToolCount
                + ", local=" + localToolCount
                + ", suffixLen=" + Objects.requireNonNullElse(sysPromptSuffix, "").length()
                + "}";
    }
}
