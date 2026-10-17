package io.acelance.graph.dsl.persistence.support;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.DraftSaveValidator;
import io.acelance.graph.dsl.persistence.SaveDraftResult;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 各持久化实现共用的草稿保存流程。
 */
public final class DraftSaveSupport {

    private DraftSaveSupport() {
    }

    public static SaveDraftResult saveDraft(GraphDefinition def,
                                            String baseVersion,
                                            Function<String, GraphDefinition> loadByVersion,
                                            Supplier<List<String>> existingVersionStrings,
                                            Runnable insertAction) {
        String base = DraftSaveValidator.resolveBase(baseVersion, def.version());
        GraphDefinition baseDef = loadByVersion.apply(base);
        if (DraftSaveValidator.unchanged(def, baseVersion, loadByVersion)) {
            // 可执行内容未变时：仅 displayName/description 变更仍应落库（同版本追加一行）
            if (baseDef != null && DraftSaveValidator.sameMeta(baseDef, def)) {
                return SaveDraftResult.skip(baseDef);
            }
            insertAction.run();
            return SaveDraftResult.insert(def);
        }
        DraftSaveValidator.requireInsertableVersion(
                def, baseVersion, loadByVersion, existingVersionStrings.get());
        insertAction.run();
        return SaveDraftResult.insert(def);
    }
}
