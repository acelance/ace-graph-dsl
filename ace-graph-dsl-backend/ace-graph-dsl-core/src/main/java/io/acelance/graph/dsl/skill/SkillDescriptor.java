package io.acelance.graph.dsl.skill;

/**
 * Skill L1 元数据（§6.4）：仅白名单内条目进 system / 工具描述。
 *
 * @param key              与 UI skillKeys 同一套 code
 * @param name             展示名
 * @param shortDescription 短描述（做什么 + 何时用）
 * @param triggerHint      可选触发提示
 */
public record SkillDescriptor(
        String key,
        String name,
        String shortDescription,
        String triggerHint
) {
    public SkillDescriptor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("skill key 不能为空");
        }
        name = (name == null || name.isBlank()) ? key : name;
        shortDescription = shortDescription == null ? "" : shortDescription;
        triggerHint = triggerHint == null ? "" : triggerHint;
    }
}
