package io.acelance.graph.dsl.persistence;

import java.util.Collection;
import java.util.Comparator;

/**
 * 简单 SemVer 比较（major.minor.patch 数字段）。
 */
public final class GraphSemver {

    private GraphSemver() {
    }

    public static int compare(String left, String right) {
        int[] l = parse(left);
        int[] r = parse(right);
        for (int i = 0; i < 3; i++) {
            int diff = l[i] - r[i];
            if (diff != 0) {
                return Integer.compare(l[i], r[i]);
            }
        }
        return 0;
    }

    public static String max(Collection<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.stream().max(Comparator.comparing(GraphSemver::parse, GraphSemver::compareArrays)).orElse(null);
    }

    public static String bumpPatch(String version) {
        int[] parts = parse(version);
        parts[2] += 1;
        return format(parts);
    }

    private static int[] parse(String version) {
        int[] parts = new int[] {0, 0, 0};
        if (version == null || version.isBlank()) {
            return parts;
        }
        String[] tokens = version.trim().split("\\.");
        for (int i = 0; i < Math.min(3, tokens.length); i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static int compareArrays(int[] left, int[] right) {
        for (int i = 0; i < 3; i++) {
            int diff = left[i] - right[i];
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    private static String format(int[] parts) {
        return parts[0] + "." + parts[1] + "." + parts[2];
    }
}
