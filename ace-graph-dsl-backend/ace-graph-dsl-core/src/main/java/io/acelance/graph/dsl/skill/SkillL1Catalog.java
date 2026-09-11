package io.acelance.graph.dsl.skill;

import java.util.List;

/**
 * 将 L1 元数据拼成 system 追加段（不经 {{}} 渲染，§6 / §5.3）。
 */
public final class SkillL1Catalog {

    private SkillL1Catalog() {
    }

    /**
     * @return 空列表时返回空串；否则返回带标题的目录文本
     */
    public static String format(List<SkillDescriptor> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available Skills (L1)\n");
        sb.append("Use tool ace__skill__load_skill with {\"code\":\"<key>\"} to load full instructions.\n");
        for (SkillDescriptor s : skills) {
            sb.append("- key=`").append(s.key()).append("`");
            sb.append(" name=").append(s.name());
            if (s.shortDescription() != null && !s.shortDescription().isBlank()) {
                sb.append(" — ").append(s.shortDescription().trim());
            }
            if (s.triggerHint() != null && !s.triggerHint().isBlank()) {
                sb.append(" (when: ").append(s.triggerHint().trim()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
