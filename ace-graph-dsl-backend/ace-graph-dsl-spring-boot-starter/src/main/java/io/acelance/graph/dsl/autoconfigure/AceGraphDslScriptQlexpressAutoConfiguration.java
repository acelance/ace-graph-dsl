package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.qlexpress.QlExpressScriptEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * QLExpress 脚本引擎自动配置。
 *
 * <p>仅当 classpath 存在 {@code com.ql.util.express.ExpressRunner}（即宿主引入了
 * {@code ace-graph-dsl-script-qlexpress} 可选模块）且 {@code ace.graph.dsl.script.qlexpress-enabled}
 * 为 true（默认）时注册 Bean。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.ql.util.express.ExpressRunner")
@ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "qlexpress-enabled", havingValue = "true", matchIfMissing = true)
public class AceGraphDslScriptQlexpressAutoConfiguration {

    @Bean
    public ScriptEngine qlExpressScriptEngine(AceGraphDslProperties properties) {
        return new QlExpressScriptEngine(properties.getScript().getExecutionTimeoutMs());
    }
}
