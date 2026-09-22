package io.acelance.graph.dsl.ai.memory;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按业务声明的 key 顺序，取 variables 中第一个非空字符串作为记忆 USER 展示正文。
 */
public final class KeyListMemoryDisplayUserTextResolver implements MemoryDisplayUserTextResolver {

    private final List<String> keys;

    public KeyListMemoryDisplayUserTextResolver(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("memory display user keys 不能为空");
        }
        this.keys = List.copyOf(keys);
    }

    public KeyListMemoryDisplayUserTextResolver(String... keys) {
        this(keys == null ? List.of() : List.of(keys));
    }

    public List<String> keys() {
        return keys;
    }

    @Override
    public String resolve(MemoryDisplayUserTextRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> variables = request.variables();
        if (variables == null || variables.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object v = variables.get(key.trim());
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
