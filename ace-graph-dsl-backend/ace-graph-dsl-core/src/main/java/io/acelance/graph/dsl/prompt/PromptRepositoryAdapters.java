package io.acelance.graph.dsl.prompt;

import io.acelance.graph.dsl.agent.InMemoryPromptRepository;
import io.acelance.graph.dsl.agent.PromptRepository;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 将遗留 {@link PromptRepository} 适配为 {@link PromptContentResolver}。
 */
public final class PromptRepositoryAdapters {

    private static final Logger log = LoggerFactory.getLogger(PromptRepositoryAdapters.class);

    private PromptRepositoryAdapters() {
    }

    public static PromptContentResolver from(PromptRepository repository) {
        PromptRepository repo = repository != null ? repository : new InMemoryPromptRepository();
        return (ctx, keys) -> {
            Objects.requireNonNull(ctx, "ctx");
            if (keys == null || keys.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                String k = key.trim();
                String loaded = repo.load(k).orElse(null);
                if (loaded == null) {
                    log.error("节点 {} Prompt 资源 miss: key={}", ctx.nodeId(), k);
                    throw new IllegalStateException(
                            "promptKeys 未找到: " + k + " (节点 " + ctx.nodeId() + ")");
                }
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(loaded);
                log.info("节点 {} 已加载 promptKey={}", ctx.nodeId(), k);
            }
            return sb.toString();
        };
    }
}
