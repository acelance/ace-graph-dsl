package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.security.menu.GraphMenuAccessControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 设计器 Web 层（REST Controller）自动配置。
 *
 * <p>受 {@code ace.graph.dsl.web.enabled} 控制，可整体关闭以实现「内嵌运行时、外置设计器」
 * 部署形态。所有 Controller 使用相对路径，统一前缀由 {@code ace.graph.dsl.web.base-path}
 * 注入（默认 {@code /api/graph}），便于宿主自定义、避免路由冲突。</p>
 */
@AutoConfiguration(after = AceGraphDslAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ace.graph.dsl.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "io.acelance.graph.dsl.web")
public class AceGraphDslWebConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AceGraphDslWebConfiguration.class);
    private static final String WEB_PACKAGE = "io.acelance.graph.dsl.web";

    private final String basePath;
    private final AceGraphDslProperties.Cors cors;

    public AceGraphDslWebConfiguration(AceGraphDslProperties properties) {
        this.basePath = normalize(properties.getWeb().getBasePath());
        this.cors = properties.getWeb().getCors();
        log.info("Ace Graph DSL 设计器 REST API 基础路径: {}", basePath);
    }

    /**
     * 请求级菜单权限缓存装饰器。
     *
     * <p>标记 {@code @Primary}，因此 {@link io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver}
     * 将优先注入本缓存装饰器；其内部通过 {@link BeanFactory} 按
     * {@link AceGraphDslBeans#GRAPH_MENU_ACCESS_CONTROL_DELEGATE} 惰性解析真实 SPI。</p>
     */
    @Bean
    @Primary
    @RequestScope
    @ConditionalOnProperty(prefix = "ace.graph.dsl.access-control", name = "cache-enabled", havingValue = "true", matchIfMissing = true)
    public GraphMenuAccessControl cachingGraphMenuAccessControl(BeanFactory beanFactory) {
        return new CachingGraphMenuAccessControl(beanFactory);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        if (basePath.isEmpty()) {
            return;
        }
        configurer.addPathPrefix(basePath,
                clazz -> clazz.getPackageName().startsWith(WEB_PACKAGE));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (cors == null || !cors.isEnabled()) {
            return;
        }
        String pathPattern = (basePath.isEmpty() ? "" : basePath) + "/**";
        registry.addMapping(pathPattern)
                .allowedOriginPatterns(cors.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods(cors.getAllowedMethods().toArray(new String[0]))
                .allowedHeaders(cors.getAllowedHeaders().toArray(new String[0]))
                .exposedHeaders(cors.getExposedHeaders().toArray(new String[0]))
                .allowCredentials(cors.isAllowCredentials())
                .maxAge(cors.getMaxAgeSeconds());
        log.info("Ace Graph DSL 设计器 CORS 已启用, pattern={}", pathPattern);
    }

    /** 规整前缀：确保以 '/' 开头、不以 '/' 结尾；空或 "/" 视为无前缀。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "";
        }
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
