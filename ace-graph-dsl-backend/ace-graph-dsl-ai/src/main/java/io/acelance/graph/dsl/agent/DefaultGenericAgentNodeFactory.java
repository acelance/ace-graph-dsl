package io.acelance.graph.dsl.agent;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Objects;
import java.util.Set;

/**
 * 默认 GENERIC_AGENT 工厂：构造 {@link GenericAgentNode}。
 *
 * <p>由 ace-graph-dsl-ai 自动配置注册为 {@link GenericAgentNodeFactory} Bean。</p>
 */
public class DefaultGenericAgentNodeFactory implements GenericAgentNodeFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultGenericAgentNodeFactory.class);

    private final ApplicationContext applicationContext;

    public DefaultGenericAgentNodeFactory(ApplicationContext applicationContext) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
    }

    @Override
    public GraphBoundAgentNode create(String nodeId, String graphId, GenericAgentSpec spec) {
        log.debug("创建 GenericAgentNode: nodeId={}, graphId={}", nodeId, graphId);
        return new GenericAgentNode(nodeId, graphId, spec, applicationContext);
    }

    @Override
    public GraphBoundAgentNode create(String nodeId,
                                     String graphId,
                                     GenericAgentSpec spec,
                                     String displayName,
                                     String description,
                                     String version,
                                     Set<String> permissionTags) {
        log.debug("创建 GenericAgentNode(完整): nodeId={}, graphId={}, version={}", nodeId, graphId, version);
        return new GenericAgentNode(nodeId, graphId, spec, applicationContext,
                displayName, description, version, permissionTags);
    }
}
