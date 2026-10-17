package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.GraphDefinitionContentComparator;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 草稿保存校验：仅与 {@code baseVersion} 对比内容；有变更则必须新版本号且大于历史最大版本。
 */
public final class DraftSaveValidator {

    private DraftSaveValidator() {
    }

    public static boolean unchanged(GraphDefinition def, String baseVersion,
                                    Function<String, GraphDefinition> loadByVersion) {
        String base = resolveBase(baseVersion, def.version());
        GraphDefinition baseDef = loadByVersion.apply(base);
        return baseDef != null && GraphDefinitionContentComparator.sameContent(baseDef, def);
    }

    /** 图级元信息（displayName / description）是否一致。 */
    public static boolean sameMeta(GraphDefinition a, GraphDefinition b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(nullToEmpty(a.displayName()), nullToEmpty(b.displayName()))
                && Objects.equals(nullToEmpty(a.description()), nullToEmpty(b.description()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 内容相对 baseVersion 已变更时，校验目标版本号可插入；否则抛 {@link VersionConflictException}。
     */
    public static void requireInsertableVersion(GraphDefinition def, String baseVersion,
                                                Function<String, GraphDefinition> loadByVersion,
                                                List<String> existingVersions) {
        if (unchanged(def, baseVersion, loadByVersion)) {
            return;
        }
        GraphDefinition atTarget = loadByVersion.apply(def.version());
        if (atTarget != null) {
            throw new VersionConflictException(
                    VersionConflictException.CODE_VERSION_EXISTS,
                    "版本号 " + def.version() + " 已存在；内容变更须使用新版本号（不可覆盖历史版本）");
        }
        String maxVersion = GraphSemver.max(existingVersions);
        if (maxVersion != null && GraphSemver.compare(def.version(), maxVersion) <= 0) {
            throw new VersionConflictException(
                    VersionConflictException.CODE_VERSION_NOT_INCREASED,
                    "新版本号 " + def.version() + " 必须大于当前最大版本 " + maxVersion);
        }
    }

    public static String resolveBase(String baseVersion, String defVersion) {
        if (baseVersion == null || baseVersion.isBlank()) {
            return defVersion;
        }
        return baseVersion;
    }
}
