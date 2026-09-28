package io.acelance.graph.dsl.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * MCP server 连接配置（按 key 解析得到）。
 *
 * <p>字段覆盖主流传输方式：远程 {@code sse} / {@code streamable-http} 用 {@link #endpoint}，
 * 本地 {@code stdio} 用 {@link #command} + {@link #args}。若接入方的配置中心结构自定义，
 * 可只填 {@link #raw} 原样透传，由业务 {@code McpToolResolver} 实现自行解释。</p>
 *
 * @param key       资源 key（与 {@code GenericAgentSpec.mcpKey()} 对应）
 * @param name      展示名
 * @param transport 传输类型：sse / streamable-http / stdio（大小写不敏感）
 * @param endpoint  远程端点 URL（sse / streamable-http）
 * @param command   本地进程命令（stdio）
 * @param args      本地进程参数（stdio）
 * @param headers   附加请求头（如鉴权）
 * @param tools     该 server 暴露的工具名白名单；为空表示不限制
 * @param raw       原始配置文本（自定义结构兜底透传）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(
        String key,
        String name,
        String transport,
        String endpoint,
        String command,
        List<String> args,
        Map<String, String> headers,
        List<String> tools,
        String raw
) {

    public static final String TRANSPORT_SSE = "sse";
    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable-http";
    public static final String TRANSPORT_STDIO = "stdio";

    public McpServerConfig {
        if (args == null) {
            args = List.of();
        }
        if (headers == null) {
            headers = Map.of();
        }
        if (tools == null) {
            tools = List.of();
        }
        if (transport == null || transport.isBlank()) {
            transport = (endpoint != null && !endpoint.isBlank()) ? TRANSPORT_SSE : TRANSPORT_STDIO;
        }
    }

    /** 仅携带原始配置文本的兜底构造（接入方自定义结构时使用） */
    public static McpServerConfig ofRaw(String key, String raw) {
        return new McpServerConfig(key, key, null, null, null, List.of(), Map.of(), List.of(), raw);
    }

    /** 远程端点快捷构造 */
    public static McpServerConfig ofEndpoint(String key, String endpoint) {
        return new McpServerConfig(key, key, TRANSPORT_SSE, endpoint, null, List.of(), Map.of(), List.of(), null);
    }

    /** 是否为远程传输 */
    public boolean isRemote() {
        return TRANSPORT_SSE.equalsIgnoreCase(transport) || TRANSPORT_STREAMABLE_HTTP.equalsIgnoreCase(transport);
    }
}
