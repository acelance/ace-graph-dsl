package io.acelance.graph.dsl.agentscope;

import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

/**
 * G-MCP：{@link ToolCallback} 列表 → AS 2.0.4 {@link Toolkit}（{@code registerAgentTool}）。
 */
public final class AceToolCallbackAgentscopeBridge {

    private static final Logger log = LoggerFactory.getLogger(AceToolCallbackAgentscopeBridge.class);

    private AceToolCallbackAgentscopeBridge() {
    }

    /**
     * @param callbacks ACE 侧工具回调（已去重）；空则返回空 Toolkit
     */
    public static Toolkit toToolkit(List<ToolCallback> callbacks) {
        Toolkit toolkit = new Toolkit();
        if (callbacks == null || callbacks.isEmpty()) {
            log.info("AGENTSCOPE Toolkit 为空（无 ToolCallback）");
            return toolkit;
        }
        int n = 0;
        for (ToolCallback cb : callbacks) {
            if (cb == null) {
                continue;
            }
            AceToolCallbackAgentTool tool = new AceToolCallbackAgentTool(cb);
            toolkit.registerAgentTool(tool);
            n++;
            log.info("AGENTSCOPE Toolkit 已注册工具, name={}, descLen={}",
                    tool.getName(),
                    tool.getDescription() != null ? tool.getDescription().length() : 0);
        }
        log.info("AGENTSCOPE Toolkit 装配完成, registered={}", n);
        return toolkit;
    }

    /**
     * 便捷：装配结果 → Toolkit。
     */
    public static Toolkit toToolkit(AgentScopeToolAssemblyResult result) {
        Objects.requireNonNull(result, "result");
        return toToolkit(result.callbacks());
    }
}
