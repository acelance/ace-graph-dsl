package io.acelance.graph.dsl.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.VersionConflictException;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 启动时加载配置的 golden DSL：注册到 {@link BuiltinGraphRegistry}（设计器优先读 classpath），
 * 并尝试落库发布（内容变更须递增 version，否则仅告警不覆盖历史版本）。
 */
public class GraphDslBootstrapLoader implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(GraphDslBootstrapLoader.class);

    private final GraphDefinitionRepository repository;
    private final GraphRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AceGraphDslProperties properties;
    private final ResourceLoader resourceLoader;
    private final BuiltinGraphRegistry builtinRegistry;

    public GraphDslBootstrapLoader(GraphDefinitionRepository repository,
                                   GraphRuntime runtime,
                                   ObjectMapper objectMapper,
                                   AceGraphDslProperties properties,
                                   ResourceLoader resourceLoader,
                                   BuiltinGraphRegistry builtinRegistry) {
        this.repository = repository;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.builtinRegistry = builtinRegistry;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        for (String location : properties.getBootstrap().getGoldenDefinitions()) {
            loadAndPublish(location);
        }
    }

    private void loadAndPublish(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("Golden DSL 资源不存在: {}", location);
                return;
            }
            GraphDefinition raw = objectMapper.readValue(resource.getInputStream(), GraphDefinition.class);
            // 标记为 golden DSL，前端只读渲染
            GraphDefinition def = new GraphDefinition(raw.graphId(), raw.displayName(), raw.version(), raw.description(),
                    raw.keyStrategies(), raw.nodes(), raw.edges(), raw.compile(), true);

            // 设计器 / AceGraphNodeHelper 优先读 Builtin，保证与 classpath JSON 一致
            if (builtinRegistry != null) {
                builtinRegistry.register(def);
                log.info("Golden DSL 已注册为内置图, graphId={}, version={}, location={}",
                        def.graphId(), def.version(), location);
            }

            try {
                repository.saveDraft(def);
                GraphRuntime.PublishResult result = runtime.publish(def.graphId(), def.version(), "ace-graph-dsl-bootstrap");
                if (result.success()) {
                    log.info("Golden DSL 落库并发布成功, graphId={}, version={}, location={}",
                            def.graphId(), def.version(), location);
                } else {
                    log.error("Golden DSL 发布失败, graphId={}, message={}", def.graphId(), result.message());
                }
            } catch (VersionConflictException ex) {
                log.warn("Golden DSL 内容相对已存版本有变更，但 version={} 不可覆盖历史。"
                                + " 请递增 JSON 中的 version 后重启。graphId={}, location={}, detail={}",
                        def.version(), def.graphId(), location, ex.getMessage());
            }
        } catch (Exception e) {
            log.error("Golden DSL 加载失败, location={}", location, e);
        }
    }
}
