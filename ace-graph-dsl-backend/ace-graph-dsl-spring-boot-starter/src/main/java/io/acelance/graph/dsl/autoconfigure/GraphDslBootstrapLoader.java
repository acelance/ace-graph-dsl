package io.acelance.graph.dsl.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 启动时加载配置的 golden DSL 并发布（支持多个业务图）。
 */
public class GraphDslBootstrapLoader implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(GraphDslBootstrapLoader.class);

    private final GraphDefinitionRepository repository;
    private final GraphRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AceGraphDslProperties properties;
    private final ResourceLoader resourceLoader;

    public GraphDslBootstrapLoader(GraphDefinitionRepository repository,
                                   GraphRuntime runtime,
                                   ObjectMapper objectMapper,
                                   AceGraphDslProperties properties,
                                   ResourceLoader resourceLoader) {
        this.repository = repository;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
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
            GraphDefinition def = objectMapper.readValue(resource.getInputStream(), GraphDefinition.class);
            repository.saveDraft(def);
            GraphRuntime.PublishResult result = runtime.publish(def.graphId(), def.version(), "ace-graph-dsl-bootstrap");
            if (result.success()) {
                log.info("Golden DSL 加载并发布成功, graphId={}, version={}, location={}", def.graphId(), def.version(), location);
            } else {
                log.error("Golden DSL 发布失败, graphId={}, message={}", def.graphId(), result.message());
            }
        } catch (Exception e) {
            log.error("Golden DSL 加载失败, location={}", location, e);
        }
    }
}
