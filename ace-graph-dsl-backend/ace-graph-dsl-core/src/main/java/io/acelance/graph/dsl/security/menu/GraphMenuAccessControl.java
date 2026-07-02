package io.acelance.graph.dsl.security.menu;

import java.util.Optional;
import java.util.Set;

/**
 * 菜单 / 功能权限 SPI。
 *
 * <p>这是 ace-graph-dsl 对接外部权限体系的标准抽象。宿主应用可实现本接口，将自有权限框架
 * （Spring Security / Shiro / Sa-Token 等）或自定义接口返回的菜单权限映射到 ace-graph-dsl
 * 的标准菜单 key（见 {@link GraphMenuPermissions}）。</p>
 *
 * <p><b>默认行为</b>：未提供任何实现时，starter 会注册一个全放行实现
 * （{@link PermissiveGraphMenuAccessControl}），即所有菜单权限均为 {@code true}。</p>
 *
 * <h3>对接方式（任选其一）</h3>
 * <ul>
 *   <li>覆写 {@link #grantedMenus()} 返回当前用户被授予的菜单 key 集合（推荐，简单）；</li>
 *   <li>覆写 {@link #isMenuGranted(String)} 实现逐项判定（精细控制）；</li>
 *   <li>覆写 {@link #currentPrincipal()} 附带当前登录主体信息供 UI 展示。</li>
 * </ul>
 */
public interface GraphMenuAccessControl {

    /**
     * 当前用户被授予的菜单权限 key 集合。
     *
     * @return {@code Optional.empty()} 表示不限制（全部放行）；否则仅集合内的 key 视为已授权
     */
    default Optional<Set<String>> grantedMenus() {
        return Optional.empty();
    }

    /**
     * 判定某个菜单 key 是否被授权。默认基于 {@link #grantedMenus()} 推导。
     *
     * @param menuKey 菜单权限 key
     * @return 是否授权
     */
    default boolean isMenuGranted(String menuKey) {
        return grantedMenus().map(set -> set.contains(menuKey)).orElse(true);
    }

    /**
     * 当前登录主体信息，供设计器 UI 展示（可选）。
     *
     * @return 主体信息；默认无
     */
    default Optional<MenuPrincipal> currentPrincipal() {
        return Optional.empty();
    }
}
