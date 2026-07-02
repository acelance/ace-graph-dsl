package io.acelance.graph.dsl.security;

import java.util.Optional;
import java.util.Set;

/**
 * 节点访问控制 SPI。宿主应用实现此接口以集成 Spring Security、Shiro 等。
 */
public interface GraphNodeAccessControl {

    /** 当前用户有权使用的 nodeId 白名单；empty 表示不限制 */
    default Optional<Set<String>> allowedNodeIds() {
        return Optional.empty();
    }

    /** 当前用户有权使用的 dispatcherId 白名单；empty 表示不限制 */
    default Optional<Set<String>> allowedDispatcherIds() {
        return Optional.empty();
    }

    /** 当前用户有权使用的 permissionTags；empty 表示不限制 */
    default Optional<Set<String>> allowedTags() {
        return Optional.empty();
    }

    default boolean canManageScriptNodes() {
        return true;
    }

    default boolean canDeleteScriptNodes() {
        return canManageScriptNodes();
    }
}
