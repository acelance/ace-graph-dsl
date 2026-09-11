package io.acelance.graph.dsl.skill;

import io.acelance.graph.dsl.llm.LlmRequestContext;

import java.util.List;
import java.util.Optional;

/**
 * L3：按需加载脚本/附件；激活时只返回资源索引（§6.4）。
 */
public interface SkillResourceLoader {

    /**
     * 列出相对路径索引（不读内容）。
     */
    List<String> listResourceIndex(LlmRequestContext ctx, String skillKey);

    /**
     * 按相对路径读资源正文。
     */
    Optional<String> loadResource(LlmRequestContext ctx, String skillKey, String relativePath);
}
