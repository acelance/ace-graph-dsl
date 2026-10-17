package io.acelance.graph.dsl.llm;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 可组合的记忆写意图（ace-graph 多节点即时落盘）。
 *
 * <ul>
 *   <li>{@link #WRITE_USER}：节点 before 立刻 remote add USER</li>
 *   <li>{@link #WRITE_ASSISTANT_THINKING}：SSE/Buffer 累积思考；<b>不</b>单独 add ASSISTANT</li>
 *   <li>{@link #WRITE_ASSISTANT_MAIN_TEXT}：节点 after 立刻 remote add ASSISTANT
 *       （content=正文，并 drain Buffer → thinking_content）</li>
 * </ul>
 *
 * <p>Remote 仅支持 add（同条 content+thinking_content），故 THINKING 与 MAIN_TEXT
 * 分节点时：思考暂存 Buffer，由 MAIN_TEXT 节点合并为 <b>1</b> 条 ASSISTANT。</p>
 */
public enum MemoryWriteFlag {

    WRITE_USER,
    WRITE_ASSISTANT_THINKING,
    WRITE_ASSISTANT_MAIN_TEXT;

    /**
     * 解析 Spec：{@code memoryWrites} 非 null（含空列表）时以显式列表为准；
     * 否则由旧 {@link MemoryMode} 推导。
     */
    public static Set<MemoryWriteFlag> resolve(Collection<String> memoryWrites, MemoryMode mode) {
        if (memoryWrites != null) {
            return parseAll(memoryWrites);
        }
        return fromLegacyMode(mode);
    }

    /** 旧三态 → flags（单节点 READ_WRITE = 三种全开）。 */
    public static Set<MemoryWriteFlag> fromLegacyMode(MemoryMode mode) {
        MemoryMode m = mode == null ? MemoryMode.NONE : mode;
        return switch (m) {
            case READ_WRITE -> EnumSet.of(
                    WRITE_USER, WRITE_ASSISTANT_THINKING, WRITE_ASSISTANT_MAIN_TEXT);
            case READ_ONLY, NONE -> EnumSet.noneOf(MemoryWriteFlag.class);
        };
    }

    public static Set<MemoryWriteFlag> parseAll(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.noneOf(MemoryWriteFlag.class);
        }
        EnumSet<MemoryWriteFlag> out = EnumSet.noneOf(MemoryWriteFlag.class);
        for (String s : raw) {
            MemoryWriteFlag f = parseOne(s);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 支持 {@code WRITE_USER}、{@code write_user}，以及 {@code +} / {@code ,} 拼接串
     * （单元素里带多 flag 时拆开）。
     */
    public static MemoryWriteFlag parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        if (t.contains("+") || t.contains(",")) {
            return null;
        }
        String key = t.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return MemoryWriteFlag.valueOf(key);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 将可能含 {@code A+B} 的列表展平解析。 */
    public static Set<MemoryWriteFlag> parseFlexible(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.noneOf(MemoryWriteFlag.class);
        }
        EnumSet<MemoryWriteFlag> out = EnumSet.noneOf(MemoryWriteFlag.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            for (String part : s.split("[+,]")) {
                MemoryWriteFlag f = parseOne(part);
                if (f != null) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Spec 推荐入口：支持列表项内 {@code A+B}。 */
    public static Set<MemoryWriteFlag> resolveFlexible(Collection<String> memoryWrites, MemoryMode mode) {
        if (memoryWrites != null) {
            return parseFlexible(memoryWrites);
        }
        return fromLegacyMode(mode);
    }

    public static List<String> toNames(Set<MemoryWriteFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            return List.of();
        }
        return flags.stream().map(Enum::name).toList();
    }
}
