package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.resource.ResourceBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按节点 {@code mcpToolWhitelist} 过滤 MCP 工具（§7.1.1 / D3）。
 *
 * <p>业务 Resolver 只负责「取」；框架统一「筛」，避免业务漏过滤形成安全绕过。</p>
 */
public final class McpToolFilter {

    private static final Logger log = LoggerFactory.getLogger(McpToolFilter.class);

    private McpToolFilter() {
    }

    /**
     * @param resolved 已解析的命名工具
     * @param binding  节点资源勾选
     * @param nodeId   节点 ID（日志）
     * @return 过滤后列表
     */
    public static List<NamedToolCallback> apply(List<NamedToolCallback> resolved,
                                                ResourceBinding binding,
                                                String nodeId) {
        Objects.requireNonNull(resolved, "resolved");
        if (binding == null) {
            return resolved;
        }
        Map<String, List<String>> wl = binding.mcpToolWhitelist();
        if (wl == null || wl.isEmpty()) {
            return resolved;
        }
        List<NamedToolCallback> kept = new ArrayList<>();
        for (NamedToolCallback t : resolved) {
            String server = t.serverKey() == null ? "" : t.serverKey();
            List<String> allow = wl.getOrDefault(server, List.of());
            // 该 server 未出现在白名单 map → 若 map 只声明了别的 server，本 server 工具是否放行？
            // 定案：whitelist 中未声明的 serverKey → 全放行该 server；声明了空列表 → 全放行；声明了非空 → 子集
            if (!wl.containsKey(server) || allow.isEmpty() || allow.contains(t.originalName())) {
                kept.add(t);
            }
        }
        if (kept.size() != resolved.size()) {
            log.info("节点 {} MCP 工具白名单过滤: {} → {} 个", nodeId, resolved.size(), kept.size());
        }
        Set<String> available = resolved.stream()
                .map(NamedToolCallback::originalName)
                .collect(Collectors.toCollection(HashSet::new));
        wl.forEach((server, names) -> {
            if (names == null) {
                return;
            }
            List<String> missing = names.stream().filter(n -> !available.contains(n)).toList();
            if (!missing.isEmpty()) {
                log.warn("节点 {} 的 MCP {} 白名单含未暴露的工具 {}，已忽略；请确认工具名拼写",
                        nodeId, server, missing);
            }
        });
        return List.copyOf(kept);
    }
}
