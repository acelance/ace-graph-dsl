package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内存版 mcp 工具提供者（默认实现）。未注册具体工具时返回空列表；
 * 可通过 Spring 容器替换为真实 MCP client 适配。
 */
@Component
public class InMemoryMcpToolProvider implements McpToolProvider {

    @Override
    public List<AgentTool> resolve(List<String> toolNames, GenericAgentSpec spec) {
        // 默认无具体工具实现：仅回显工具名，便于链路验证
        List<AgentTool> tools = new ArrayList<>();
        if (toolNames != null) {
            for (String name : toolNames) {
                tools.add(new AgentTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String call(Map<String, Object> args) {
                        return "【STUB-TOOL】" + name + " 被调用，参数=" + args;
                    }
                });
            }
        }
        return tools;
    }
}
