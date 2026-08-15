package io.acelance.graph.dsl.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 skill 仓库（默认实现）。skill 内容作为附加系统指令使用。
 */
@Component
public class InMemorySkillRegistry implements SkillRegistry {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    public void put(String key, String content) {
        store.put(key, content);
    }

    @Override
    public Optional<String> load(String key) {
        return Optional.ofNullable(store.get(key));
    }
}
