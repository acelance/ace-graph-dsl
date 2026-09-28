package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 默认空实现：业务未提供 {@link McpToolResolver} 时不挂载任何 MCP 工具。
 *
 * <p>勾选了 mcpKeys 时打 warn，便于排查「设计器勾了但运行期空转」；真正 list_tools
 * 由业务 {@link McpToolResolver}（Biz.3）实现。</p>
 */
public final class EmptyMcpToolResolver implements McpToolResolver {

    private static final Logger log = LoggerFactory.getLogger(EmptyMcpToolResolver.class);

    public static final EmptyMcpToolResolver INSTANCE = new EmptyMcpToolResolver();

    private EmptyMcpToolResolver() {
    }

    @Override
    public List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> mcpKeys) {
        if (mcpKeys != null && !mcpKeys.isEmpty()) {
            log.warn("节点 {} 已勾选 mcpKeys={}，但使用 EmptyMcpToolResolver，返回空列表；"
                            + "请业务注册 McpToolResolver Bean",
                    ctx == null ? "?" : ctx.nodeId(), mcpKeys);
        }
        return List.of();
    }
}
