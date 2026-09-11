package io.acelance.graph.dsl.skill;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.List;

/**
 * L1：按节点白名单解析 skill 元数据（§6.4）。不加载 SKILL.md 正文。
 */
@FunctionalInterface
public interface SkillCatalogResolver {

    /**
     * @param ctx       请求上下文
     * @param skillKeys 本节点白名单（有序）
     * @return 仅成功解析的元数据；缺失项由实现自行记日志 / miss
     */
    List<SkillDescriptor> resolve(LlmRequestContext ctx, List<String> skillKeys);
}
