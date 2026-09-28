package io.acelance.graph.dsl.skill;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Skill 元数据 + 正文 + 资源索引（默认 / 测试用）。
 *
 * <p>同时实现 Catalog / Content / Resource 三个 SPI，便于单测与无业务 Bean 时兜底。</p>
 */
public class InMemorySkillStore implements SkillCatalogResolver, SkillContentLoader, SkillResourceLoader {

    private static final Logger log = LoggerFactory.getLogger(InMemorySkillStore.class);

    private final Map<String, SkillDescriptor> meta = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> resources = new ConcurrentHashMap<>();

    /** 注册完整 skill（L1 + L2） */
    public void put(String key, String name, String shortDescription, String body) {
        put(key, name, shortDescription, "", body);
    }

    public void put(String key, String name, String shortDescription, String triggerHint, String body) {
        meta.put(key, new SkillDescriptor(key, name, shortDescription, triggerHint));
        if (body != null) {
            bodies.put(key, body);
        }
        log.info("InMemorySkillStore 注册 skill: key={}, name={}", key, name);
    }

    /** 仅注册正文时自动生成简陋 L1 */
    public void putBody(String key, String body) {
        meta.putIfAbsent(key, new SkillDescriptor(key, key, "skill:" + key, ""));
        bodies.put(key, body);
    }

    /** 注册 L3 资源 */
    public void putResource(String skillKey, String relativePath, String content) {
        resources.computeIfAbsent(skillKey, k -> new ConcurrentHashMap<>())
                .put(relativePath, content);
    }

    @Override
    public List<SkillDescriptor> resolve(LlmRequestContext ctx, List<String> skillKeys) {
        if (skillKeys == null || skillKeys.isEmpty()) {
            return List.of();
        }
        List<SkillDescriptor> out = new ArrayList<>();
        for (String key : skillKeys) {
            SkillDescriptor d = meta.get(key);
            if (d == null) {
                log.warn("节点 {} Skill L1 未找到: key={}，已从本节点目录剔除",
                        ctx != null ? ctx.nodeId() : "?", key);
                continue;
            }
            out.add(d);
        }
        log.info("节点 {} Skill L1 解析完成: requested={}, resolved={}",
                ctx != null ? ctx.nodeId() : "?", skillKeys.size(), out.size());
        return List.copyOf(out);
    }

    @Override
    public Optional<String> loadBody(LlmRequestContext ctx, String skillKey) {
        String body = bodies.get(skillKey);
        if (body == null) {
            log.warn("节点 {} Skill L2 未找到: key={}",
                    ctx != null ? ctx.nodeId() : "?", skillKey);
            return Optional.empty();
        }
        return Optional.of(body);
    }

    @Override
    public List<String> listResourceIndex(LlmRequestContext ctx, String skillKey) {
        Map<String, String> map = resources.get(skillKey);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        return List.copyOf(map.keySet());
    }

    @Override
    public Optional<String> loadResource(LlmRequestContext ctx, String skillKey, String relativePath) {
        Map<String, String> map = resources.get(skillKey);
        if (map == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(relativePath));
    }
}
