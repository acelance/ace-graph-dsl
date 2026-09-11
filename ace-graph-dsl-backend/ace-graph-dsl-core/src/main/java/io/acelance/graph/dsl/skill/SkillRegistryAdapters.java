package io.acelance.graph.dsl.skill;

import io.acelance.graph.dsl.agent.SkillRegistry;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 将遗留 {@link SkillRegistry} 适配为 L1/L2 SPI（过渡）：L1 不塞全文，仅生成短描述占位。
 */
public class SkillRegistryAdapters {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistryAdapters.class);

    private SkillRegistryAdapters() {
    }

    public static SkillCatalogResolver catalog(SkillRegistry registry) {
        return (ctx, skillKeys) -> {
            if (skillKeys == null || skillKeys.isEmpty() || registry == null) {
                return List.of();
            }
            List<SkillDescriptor> out = new ArrayList<>();
            for (String key : skillKeys) {
                if (registry.load(key).isPresent()) {
                    out.add(new SkillDescriptor(key, key, "Registered skill: " + key, ""));
                } else {
                    log.warn("节点 {} SkillRegistry 未找到 key={}，L1 剔除",
                            ctx.nodeId(), key);
                }
            }
            return List.copyOf(out);
        };
    }

    public static SkillContentLoader content(SkillRegistry registry) {
        return (ctx, skillKey) -> {
            if (registry == null) {
                return Optional.empty();
            }
            return registry.load(skillKey);
        };
    }

    public static SkillResourceLoader emptyResources() {
        return new SkillResourceLoader() {
            @Override
            public List<String> listResourceIndex(LlmRequestContext ctx, String skillKey) {
                return List.of();
            }

            @Override
            public Optional<String> loadResource(LlmRequestContext ctx, String skillKey, String relativePath) {
                return Optional.empty();
            }
        };
    }
}
