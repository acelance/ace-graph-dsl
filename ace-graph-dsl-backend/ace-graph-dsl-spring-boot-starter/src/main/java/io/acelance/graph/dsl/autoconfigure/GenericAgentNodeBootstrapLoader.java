package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.service.GenericAgentNodeService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时从持久化层加载已启用的通用 agent 节点定义并注册到 GraphNodeRegistry。
 *
 * <p>与 {@link DynamicNodeBootstrapLoader}（脚本节点）对称，共用
 * {@code ace.graph.dsl.dynamic-nodes.*} 开关。</p>
 */
public class GenericAgentNodeBootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentNodeBootstrapLoader.class);

    private final GenericAgentNodeService agentNodeService;
    private final AceGraphDslProperties properties;

    public GenericAgentNodeBootstrapLoader(GenericAgentNodeService agentNodeService,
                                           AceGraphDslProperties properties) {
        this.agentNodeService = agentNodeService;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        if (!properties.getDynamicNodes().isEnabled()) {
            log.info("通用 agent 节点加载已禁用 (ace.graph.dsl.dynamic-nodes.enabled=false)");
            return;
        }
        if (!properties.getDynamicNodes().isAutoReloadOnStartup()) {
            return;
        }
        agentNodeService.reloadAllEnabled();
        log.info("已从持久化层加载通用 agent 节点到注册中心");
    }
}
