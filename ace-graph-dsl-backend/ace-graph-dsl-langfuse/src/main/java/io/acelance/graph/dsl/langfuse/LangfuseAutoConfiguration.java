package io.acelance.graph.dsl.langfuse;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Langfuse 接入自动装配（可选模块）。
 *
 * <p>仅当 {@code ace.graph.dsl.langfuse.enabled=true} 时生效；其余情形所有 bean 不注册，
 * 对图执行零侵入。宿主应用引入本模块依赖后，Spring Boot 自动扫描
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 加载本类。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ace.graph.dsl.langfuse", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseAutoConfiguration {

    @Bean
    public LangfuseClient langfuseClient(LangfuseProperties props) {
        return new LangfuseClient(props);
    }

    @Bean
    public LangfuseTraceContext langfuseTraceContext() {
        return new LangfuseTraceContext();
    }

    @Bean
    public LangfuseGraphExecutionListener langfuseGraphExecutionListener(
            LangfuseClient client, LangfuseTraceContext ctx, LangfuseProperties props) {
        return new LangfuseGraphExecutionListener(client, ctx, props);
    }

    @Bean
    public LangfuseTraceRecorder langfuseTraceRecorder(LangfuseClient client, LangfuseTraceContext ctx) {
        return new LangfuseTraceRecorder(client, ctx);
    }
}
