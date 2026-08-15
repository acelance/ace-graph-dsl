package io.acelance.graph.dsl.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 prompt 仓库（默认实现）。可通过 Spring 容器替换。
 */
@Component
public class InMemoryPromptRepository implements PromptRepository {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    public void put(String key, String template) {
        store.put(key, template);
    }

    @Override
    public Optional<String> load(String key) {
        return Optional.ofNullable(store.get(key));
    }
}
