package io.acelance.graph.dsl.skill;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.Optional;

/**
 * L2：激活时加载 SKILL.md 正文（§6.4）。
 */
@FunctionalInterface
public interface SkillContentLoader {

    /**
     * @param ctx      请求上下文
     * @param skillKey skill code
     * @return 正文；不存在则 empty
     */
    Optional<String> loadBody(LlmRequestContext ctx, String skillKey);
}
