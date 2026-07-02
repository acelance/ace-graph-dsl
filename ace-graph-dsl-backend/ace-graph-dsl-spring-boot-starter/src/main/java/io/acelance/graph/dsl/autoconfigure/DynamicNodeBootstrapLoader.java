package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.service.ScriptNodeService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时从持久化层加载已启用的脚本节点并注册到 GraphNodeRegistry。
 */
public class DynamicNodeBootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(DynamicNodeBootstrapLoader.class);

    private final ScriptNodeService scriptNodeService;
    private final AceGraphDslProperties properties;

    public DynamicNodeBootstrapLoader(ScriptNodeService scriptNodeService, AceGraphDslProperties properties) {
        this.scriptNodeService = scriptNodeService;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        if (!properties.getDynamicNodes().isEnabled()) {
            log.info("动态节点加载已禁用 (ace.graph.dsl.dynamic-nodes.enabled=false)");
            return;
        }
        if (!properties.getDynamicNodes().isAutoReloadOnStartup()) {
            return;
        }
        scriptNodeService.reloadAllEnabled();
        log.info("已从持久化层加载脚本节点到注册中心");
    }
}
