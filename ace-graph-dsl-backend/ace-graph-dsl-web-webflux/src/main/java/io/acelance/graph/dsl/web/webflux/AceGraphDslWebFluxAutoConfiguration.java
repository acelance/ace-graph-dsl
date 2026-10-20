package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.autoconfigure.AceGraphDslAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;

import jakarta.annotation.PostConstruct;

/**
 * WebFlux 宿主下的设计器 REST 自动配置。
 *
 * <p>与 Servlet 侧 {@code AceGraphDslWebConfiguration} 共用开关
 * {@code ace.graph.dsl.web.enabled}（默认 true），并按
 * {@link ConditionalOnWebApplication.Type#REACTIVE} 互斥注册，不会在
 * WebFlux 进程上硬塞 MVC Controllers。</p>
 *
 * <p>首期 Controllers 不接 {@code MenuPermissionGuard}（与业务镜像一致）；
 * 仍提供 {@code /permissions/menus} 供前端显隐。</p>
 */
@AutoConfiguration(after = AceGraphDslAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "ace.graph.dsl.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "io.acelance.graph.dsl.web.webflux")
public class AceGraphDslWebFluxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslWebFluxAutoConfiguration.class);

    /**
     * 启动时打印关键注册日志，便于确认 WebFlux 设计器 API 已生效。
     */
    @PostConstruct
    public void logEnabled() {
        log.info("[ace-graph-dsl-web-webflux] 设计器 WebFlux Controllers 已启用 "
                + "(ace.graph.dsl.web.enabled=true, WebApplicationType=REACTIVE)");
    }
}
