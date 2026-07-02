package io.acelance.graph.dsl.registry;

import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * 节点/Dispatcher 运行时上下文：提供 Spring 容器访问与节点配置属性。
 *
 * @param spring     Spring ApplicationContext，用于解析依赖 Bean
 * @param nodeConfig 来自 DSL 中 node.config 的配置属性
 */
public record NodeRuntimeContext(
        ApplicationContext spring,
        Map<String, Object> nodeConfig
) {

    /** 空上下文（无配置时使用） */
    public static NodeRuntimeContext empty(ApplicationContext spring) {
        return new NodeRuntimeContext(spring, Map.of());
    }
}
