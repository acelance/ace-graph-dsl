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
        if (DraftSaveValidator.unchanged(def, baseVersion, loadByVersion)) {
            String base = DraftSaveValidator.resolveBase(baseVersion, def.version());
            GraphDefinition baseDef = loadByVersion.apply(base);
            return SaveDraftResult.skip(baseDef);
        }
        DraftSaveValidator.requireInsertableVersion(
                def, baseVersion, loadByVersion, existingVersionStrings.get());
        insertAction.run();
        return SaveDraftResult.insert(def);
    }
}
