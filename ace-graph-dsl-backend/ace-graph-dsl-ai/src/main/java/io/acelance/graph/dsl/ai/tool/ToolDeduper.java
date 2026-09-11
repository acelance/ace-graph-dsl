package io.acelance.graph.dsl.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具去重与挂载前断言（§5 / D1 / D2 / P3.4）。
 *
 * <p>顺序：uniqueName 去重（BUILTIN 永留、其余先到保留）→ LOCAL/MCP originalName 策略 →
 * 冲突组 description 消歧。</p>
 */
public final class ToolDeduper {

    private static final Logger log = LoggerFactory.getLogger(ToolDeduper.class);

    /** 默认：本地优先于 MCP */
    public static final ToolConflictPolicy DEFAULT_POLICY = ToolConflictPolicy.LOCAL_FIRST;

    private ToolDeduper() {
    }

    /** 使用默认 {@link #DEFAULT_POLICY} */
    public static List<ToolCallback> toModelCallbacks(List<NamedToolCallback> named) {
        return toModelCallbacks(named, DEFAULT_POLICY);
    }

    /**
     * @param policy LOCAL vs MCP 同 originalName 策略；null 时用默认
     * @return 可挂到 ChatClient 的 ToolCallback 列表（已改名为 uniqueName）
     */
    public static List<ToolCallback> toModelCallbacks(List<NamedToolCallback> named,
                                                      ToolConflictPolicy policy) {
        Objects.requireNonNull(named, "named");
        ToolConflictPolicy p = policy == null ? DEFAULT_POLICY : policy;
        List<NamedToolCallback> resolved = applyLocalMcpPolicy(resolveByUniqueName(named), p);
        Set<String> conflictedOriginals = conflictedOriginalNames(resolved);
        List<ToolCallback> out = new ArrayList<>(resolved.size());
        Set<String> seenNames = new HashSet<>();
        for (NamedToolCallback n : resolved) {
            ToolNames.assertLegal(n.uniqueName());
            if (!seenNames.add(n.uniqueName())) {
                throw new IllegalStateException("工具 uniqueName 重复，拒绝挂载: " + n.uniqueName());
            }
            boolean conflicted = conflictedOriginals.contains(n.originalName());
            log.info("挂载工具: {} -> uniqueName={}, source={}, conflicted={}",
                    n.originalName(), n.uniqueName(), n.source(), conflicted);
            out.add(n.toModelCallback(conflicted));
        }
        return out;
    }

    /**
     * uniqueName 去重：BUILTIN 永不被覆盖；其余先到保留 + warn（§5.2，避免热刷新抖动）。
     */
    static List<NamedToolCallback> resolveByUniqueName(List<NamedToolCallback> named) {
        Map<String, NamedToolCallback> byUnique = new LinkedHashMap<>();
        for (NamedToolCallback n : named) {
            if (n == null) {
                continue;
            }
            NamedToolCallback existing = byUnique.get(n.uniqueName());
            if (existing == null) {
                byUnique.put(n.uniqueName(), n);
                continue;
            }
            if (existing.source() == ToolSource.BUILTIN) {
                log.warn("工具 uniqueName={} 冲突，保留 BUILTIN，丢弃 {}", n.uniqueName(), n.source());
                continue;
            }
            if (n.source() == ToolSource.BUILTIN) {
                log.warn("工具 uniqueName={} 冲突，BUILTIN 覆盖 {}", n.uniqueName(), existing.source());
                byUnique.put(n.uniqueName(), n);
                continue;
            }
            // 先到保留
            log.warn("工具 uniqueName={} 冲突，先到保留 {}，丢弃 {}",
                    n.uniqueName(), existing.source(), n.source());
        }
        return List.copyOf(byUnique.values());
    }

    /**
     * 同 originalName 下 LOCAL 与 MCP 互斥策略；BUILTIN 始终保留。
     */
    static List<NamedToolCallback> applyLocalMcpPolicy(List<NamedToolCallback> named,
                                                       ToolConflictPolicy policy) {
        Map<String, List<NamedToolCallback>> byOriginal = new LinkedHashMap<>();
        for (NamedToolCallback n : named) {
            byOriginal.computeIfAbsent(n.originalName(), k -> new ArrayList<>()).add(n);
        }
        List<NamedToolCallback> out = new ArrayList<>();
        for (Map.Entry<String, List<NamedToolCallback>> e : byOriginal.entrySet()) {
            List<NamedToolCallback> group = e.getValue();
            boolean hasLocal = group.stream().anyMatch(t -> t.source() == ToolSource.LOCAL);
            boolean hasMcp = group.stream().anyMatch(t -> t.source() == ToolSource.MCP);
            if (hasLocal && hasMcp) {
                if (policy == ToolConflictPolicy.FAIL) {
                    throw new IllegalStateException(
                            "工具 originalName 同时存在 LOCAL 与 MCP，策略 FAIL: " + e.getKey()
                                    + "（node 侧请调整 keys 或改用 LOCAL_FIRST/MCP_FIRST）");
                }
                ToolSource keep = policy == ToolConflictPolicy.MCP_FIRST
                        ? ToolSource.MCP
                        : ToolSource.LOCAL;
                ToolSource drop = keep == ToolSource.LOCAL ? ToolSource.MCP : ToolSource.LOCAL;
                log.warn("工具 originalName={} LOCAL/MCP 冲突，策略 {}：保留 {}，丢弃 {}",
                        e.getKey(), policy, keep, drop);
                for (NamedToolCallback t : group) {
                    if (t.source() == ToolSource.BUILTIN || t.source() == keep) {
                        out.add(t);
                    } else if (t.source() == drop) {
                        log.info("已丢弃冲突工具: uniqueName={}, source={}", t.uniqueName(), t.source());
                    } else {
                        out.add(t);
                    }
                }
            } else {
                out.addAll(group);
            }
        }
        return List.copyOf(out);
    }

    /** @deprecated 使用 {@link #resolveByUniqueName(List)} */
    @Deprecated
    static List<NamedToolCallback> resolve(List<NamedToolCallback> named) {
        return resolveByUniqueName(named);
    }

    static Set<String> conflictedOriginalNames(List<NamedToolCallback> named) {
        Map<String, Integer> counts = new HashMap<>();
        for (NamedToolCallback n : named) {
            counts.merge(n.originalName(), 1, Integer::sum);
        }
        Set<String> conflicted = new HashSet<>();
        counts.forEach((k, c) -> {
            if (c > 1) {
                conflicted.add(k);
            }
        });
        return conflicted;
    }
}
