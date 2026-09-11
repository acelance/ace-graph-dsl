package io.acelance.graph.dsl.ai.skill;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.skill.ForceSkills;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillPathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * forceSkills 预激活：按序加载 L2 正文，供 Template 在进模型前注入 messages（§6.3.1）。
 */
public final class ForceSkillActivator {

    private static final Logger log = LoggerFactory.getLogger(ForceSkillActivator.class);

    private ForceSkillActivator() {
    }

    /**
     * @return 已强制激活的正文块（有序）；不在白名单的跳过并打 info
     */
    public static List<ActivatedSkill> activate(LlmRequestContext ctx,
                                                SkillContentLoader contentLoader,
                                                Set<String> activated) {
        ResourceBinding binding = ctx.binding();
        if (binding == null || !binding.enableSkill() || binding.skillKeys().isEmpty()) {
            return List.of();
        }
        List<String> force = ForceSkills.read(ctx.state(), ctx.nodeId());
        if (force.isEmpty()) {
            return List.of();
        }
        Set<String> activatedSafe = activated != null ? activated : new LinkedHashSet<>();
        List<ActivatedSkill> out = new ArrayList<>();
        for (String code : force) {
            if (!SkillPathSafety.inWhitelist(code, binding.skillKeys())) {
                log.info("节点 {} 的 forceSkills 含 {}，但不在本节点白名单，跳过加载（保留键仍留给后续节点）",
                        ctx.nodeId(), code);
                continue;
            }
            if (!activatedSafe.add(code)) {
                log.info("节点 {} forceSkills 中 skill={} 已激活，跳过", ctx.nodeId(), code);
                continue;
            }
            String body = contentLoader == null
                    ? null
                    : contentLoader.loadBody(ctx, code).orElse(null);
            if (body == null || body.isBlank()) {
                log.warn("节点 {} 强制激活失败，正文缺失: skill={}", ctx.nodeId(), code);
                continue;
            }
            log.info("节点 {} 强制激活 skill={}", ctx.nodeId(), code);
            out.add(new ActivatedSkill(code, body));
        }
        return List.copyOf(out);
    }

    /** 预激活结果 */
    public record ActivatedSkill(String code, String body) {
    }
}
