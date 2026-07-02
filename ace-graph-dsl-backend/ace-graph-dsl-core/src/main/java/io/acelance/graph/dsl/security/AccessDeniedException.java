package io.acelance.graph.dsl.security;

/**
 * 节点/Dispatcher 访问被拒绝时抛出。由 Web 层映射为 HTTP 403。
 *
 * <p>当 {@link GraphNodeAccessControl} 判定当前用户无权执行某操作
 * （如管理脚本节点）时抛出此异常。</p>
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }

    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
