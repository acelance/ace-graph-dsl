package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * api-key 还原默认实现：掩码值经环境变量或配置 Map 还原为真实 key。
 *
 * <p>查找顺序：环境变量 {@code <nodeId>_API_KEY} → 配置 Map（{@link #put} 注入）。
 * 若 spec 未掩码（apiKeyMasked=false）则原样返回。查不到时返回掩码值本身
 * （上游调用会因 key 无效而失败，便于暴露配置缺失）。</p>
 */
@Component
public class EnvSecretResolver implements SecretResolver {

    private final Map<String, String> configStore = new ConcurrentHashMap<>();

    public void put(String nodeId, String realKey) {
        configStore.put(nodeId, realKey);
    }

    @Override
    public String resolveApiKey(String graphId, String nodeId, GenericAgentSpec spec) {
        if (!spec.apiKeyMasked() || spec.modelApiKey() == null) {
            return spec.modelApiKey();
        }
        String env = System.getenv(nodeId + "_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String cfg = configStore.get(nodeId);
        if (cfg != null && !cfg.isBlank()) {
            return cfg;
        }
        return spec.modelApiKey();
    }
}
