package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.security.menu.GraphMenuAccessControl;
import io.acelance.graph.dsl.security.menu.MenuPrincipal;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级缓存的 {@link GraphMenuAccessControl} 装饰器。
 *
 * <p>注册为 {@code @RequestScope}，因此同一次 HTTP 请求内对同一菜单 key 的判定、
 * 以及 {@link #grantedMenus()} / {@link #currentPrincipal()} 仅向真实 SPI 委托一次，
 * 之后命中本地缓存。这在宿主 SPI 实现需要远程 RPC 时可显著降低重复调用与延迟。</p>
 *
 * <p>委托对象通过 {@link BeanFactory} 按限定名惰性解析，避免 {@code @RequestScope}
 * 代理与 {@code @Primary} 组合时构造器注入误解析为自身而导致栈溢出。</p>
 */
public class CachingGraphMenuAccessControl implements GraphMenuAccessControl {

    private final BeanFactory beanFactory;
    private final Map<String, Boolean> grantedCache = new ConcurrentHashMap<>();

    private volatile GraphMenuAccessControl delegate;
    private volatile Optional<Set<String>> grantedMenusCache;
    private volatile Optional<MenuPrincipal> principalCache;

    public CachingGraphMenuAccessControl(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    private GraphMenuAccessControl delegate() {
        GraphMenuAccessControl resolved = delegate;
        if (resolved == null) {
            synchronized (this) {
                resolved = delegate;
                if (resolved == null) {
                    resolved = beanFactory.getBean(
                            AceGraphDslBeans.GRAPH_MENU_ACCESS_CONTROL_DELEGATE,
                            GraphMenuAccessControl.class);
                    assertNotCachingProxy(resolved);
                    delegate = resolved;
                }
            }
        }
        return resolved;
    }

    private static void assertNotCachingProxy(GraphMenuAccessControl target) {
        if (target instanceof CachingGraphMenuAccessControl) {
            throw new IllegalStateException(
                    "菜单权限缓存装饰器不能委托自身，请检查 Bean "
                            + AceGraphDslBeans.GRAPH_MENU_ACCESS_CONTROL_DELEGATE);
        }
        if (AopUtils.isAopProxy(target)
                && CachingGraphMenuAccessControl.class.isAssignableFrom(AopUtils.getTargetClass(target))) {
            throw new IllegalStateException(
                    "菜单权限缓存装饰器不能委托自身的 RequestScope 代理，请检查 Bean "
                            + AceGraphDslBeans.GRAPH_MENU_ACCESS_CONTROL_DELEGATE);
        }
    }

    @Override
    public boolean isMenuGranted(String menuKey) {
        Boolean cached = grantedCache.get(menuKey);
        if (cached != null) {
            return cached;
        }
        boolean granted = delegate().isMenuGranted(menuKey);
        grantedCache.put(menuKey, granted);
        return granted;
    }

    @Override
    public Optional<Set<String>> grantedMenus() {
        Optional<Set<String>> cached = grantedMenusCache;
        if (cached == null) {
            cached = delegate().grantedMenus();
            grantedMenusCache = cached;
        }
        return cached;
    }

    @Override
    public Optional<MenuPrincipal> currentPrincipal() {
        Optional<MenuPrincipal> cached = principalCache;
        if (cached == null) {
            cached = delegate().currentPrincipal();
            principalCache = cached;
        }
        return cached;
    }
}
