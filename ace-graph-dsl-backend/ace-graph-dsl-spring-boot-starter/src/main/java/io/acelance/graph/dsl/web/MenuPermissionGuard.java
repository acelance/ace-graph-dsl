package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.security.AccessDeniedException;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;

/**
 * 写操作的菜单权限服务端兜底校验工具。
 *
 * <p>与前端显隐（{@code GraphMenuAccessControl} → UI）形成「前端显隐 + 后端拦截」的双层纵深，
 * 防止绕过前端直接调用写接口。判定委托给 {@link GraphMenuPermissionResolver}，未授权时抛出
 * {@link AccessDeniedException}，由 {@code ApiExceptionAdvice} 统一映射为 HTTP 403。</p>
 */
final class MenuPermissionGuard {

    private MenuPermissionGuard() {
    }

    static void require(GraphMenuPermissionResolver resolver, String menuKey, String message) {
        if (resolver == null) {
            return;
        }
        if (!resolver.isGranted(menuKey)) {
            throw new AccessDeniedException(message + " (menu=" + menuKey + ")");
        }
    }
}
