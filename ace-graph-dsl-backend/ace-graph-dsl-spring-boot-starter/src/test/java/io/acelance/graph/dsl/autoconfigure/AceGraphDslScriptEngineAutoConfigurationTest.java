package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.script.AviatorScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.SpelScriptEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证可选脚本引擎 AutoConfiguration 开关与 classpath 条件装配。
 *
 * <p>不加载完整 {@link AceGraphDslAutoConfiguration}，避免 sqlite/Redis 等基础设施干扰。</p>
 */
class AceGraphDslScriptEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BaseScriptEnginesConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    AceGraphDslScriptQlexpressAutoConfiguration.class,
                    AceGraphDslScriptGroovyAutoConfiguration.class));

    @Test
    void defaultRegistersAviatorSpelAndQlexpressButNotGroovy() {
        runner.run(ctx -> {
            ScriptEngineRegistry registry = ctx.getBean(ScriptEngineRegistry.class);
            assertTrue(registry.supports("aviator"));
            assertTrue(registry.supports("spel"));
            // starter 对本模块测试 classpath 已解析 optional 依赖
            assertTrue(registry.supports("qlexpress"));
            assertFalse(registry.supports("groovy"));
        });
    }

    @Test
    void groovyRegisteredWhenExplicitlyEnabled() {
        runner.withPropertyValues("ace.graph.dsl.script.groovy-enabled=true")
                .run(ctx -> assertTrue(ctx.getBean(ScriptEngineRegistry.class).supports("groovy")));
    }

    @Test
    void qlexpressCanBeDisabled() {
        runner.withPropertyValues("ace.graph.dsl.script.qlexpress-enabled=false")
                .run(ctx -> assertFalse(ctx.getBean(ScriptEngineRegistry.class).supports("qlexpress")));
    }

    @Configuration
    @EnableConfigurationProperties(AceGraphDslProperties.class)
    static class BaseScriptEnginesConfig {

        @Bean
        ScriptEngine aviatorScriptEngine() {
            return new AviatorScriptEngine(500);
        }

        @Bean
        ScriptEngine spelScriptEngine() {
            return new SpelScriptEngine(500);
        }

        @Bean
        ScriptEngineRegistry scriptEngineRegistry(List<ScriptEngine> engines) {
            return new ScriptEngineRegistry(engines);
        }
    }
}
