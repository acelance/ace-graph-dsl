package io.acelance.graph.dsl.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill 路径穿越防护（§6.4.1）。
 */
public final class SkillPathSafety {

    private SkillPathSafety() {
    }

    /**
     * @return true 表示路径合法（相对、无 ..、无绝对盘符）
     */
    public static boolean isSafeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.trim().replace('\\', '/');
        if (p.startsWith("/") || p.contains("://")) {
            return false;
        }
        if (p.length() >= 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':') {
            return false;
        }
        String[] parts = p.split("/");
        for (String part : parts) {
            if ("..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    public static boolean inWhitelist(String code, List<String> skillKeys) {
        if (code == null || code.isBlank() || skillKeys == null) {
            return false;
        }
        Set<String> set = new HashSet<>(skillKeys);
        return set.contains(code.trim());
    }
}
