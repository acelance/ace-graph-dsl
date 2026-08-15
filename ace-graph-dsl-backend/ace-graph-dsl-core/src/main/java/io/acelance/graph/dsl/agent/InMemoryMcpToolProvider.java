package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内存版 mcp 工具提供者（默认实现）。未注册具体工具时仅回显工具名，便于链路验证；
 * 可通过 Spring 容器替换为真实 MCP client 适配。
 */
@Component
public class InMemoryMcpToolProvider implements McpToolProvider {

    @Override
    public List<AgentTool> resolve(List<String> toolNames, GenericAgentSpec spec) {
        return resolve(toolNames, spec, null);
    }

    @Override
    public List<AgentTool> resolve(List<String> toolNames, GenericAgentSpec spec, McpServerConfig serverConfig) {
        List<String> names = effectiveToolNames(toolNames, serverConfig);
        List<AgentTool> tools = new ArrayList<>();
        String serverLabel = serverConfig != null ? serverConfig.key() : "inline";
        for (String name : names) {
            tools.add(new AgentTool() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public String call(Map<String, Object> args) {
                    return "【STUB-TOOL】" + name + "@" + serverLabel + " 被调用，参数=" + args;
                }
            });
        }
        return tools;
    }

    /** 节点声明的工具名与 server 白名单取交集；节点未声明时用 server 白名单全集。 */
    private static List<String> effectiveToolNames(List<String> toolNames, McpServerConfig serverConfig) {
        List<String> declared = toolNames != null ? toolNames : List.of();
        List<String> allowed = serverConfig != null ? serverConfig.tools() : List.of();
        if (declared.isEmpty()) {
            return allowed;
        }
        if (allowed.isEmpty()) {
            return declared;
        }
        return declared.stream().filter(allowed::contains).toList();
    }
}
