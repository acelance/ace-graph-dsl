package io.acelance.graph.dsl.script;

/**
 * 脚本引擎元数据，供前端引擎下拉与编辑器行为决策。
 *
 * @param id             引擎标识（aviator / spel / qlexpress / groovy）
 * @param label          显示名
 * @param multiLine     是否推荐多行编辑器（如 QLExpress / Groovy）
 * @param maxScriptLines 建议行数上限（软提示，硬限制仍由 maxScriptSizeBytes 约束）
 * @param hintKey        i18n 提示文案 key（前端通过 {@code t(hintKey)} 解析）
 */
public record ScriptEngineDescriptor(
        String id,
        String label,
        boolean multiLine,
        int maxScriptLines,
        String hintKey
) {
}
