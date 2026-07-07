package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.groovy.GroovySandboxScriptEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Groovy 沙箱脚本引擎自动配置。
 *
 * <p>仅当 classpath 存在 {@code groovy.lang.GroovyShell}（即宿主引入了
 * {@code ace-graph-dsl-script-groovy} 可选模块）且 {@code ace.graph.dsl.script.groovy-enabled}
 * 为 true（<b>默认 false，必须显式开启</b>）时注册 Bean。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "groovy.lang.GroovyShell")
@ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "groovy-enabled", havingValue = "true", matchIfMissing = false)
public class AceGraphDslScriptGroovyAutoConfiguration {

    @Bean
    public ScriptEngine groovySandboxScriptEngine(AceGraphDslProperties properties) {
        AceGraphDslProperties.GroovyScript groovy = properties.getScript().getGroovy();
        return new GroovySandboxScriptEngine(
                properties.getScript().getExecutionTimeoutMs(),
                groovy.getMaxScriptCache(),
                groovy.getAllowedImports());
    }
}
