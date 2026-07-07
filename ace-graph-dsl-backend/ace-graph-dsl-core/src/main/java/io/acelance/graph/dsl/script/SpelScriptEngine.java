package io.acelance.graph.dsl.script;

import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

/**
 * 基于 Spring SpEL（Spring Expression Language）的脚本引擎实现。
 *
 * <p>零额外依赖（Spring 内置），适合单行表达式场景，与 Aviator 形成互补。
 * 通过 {@link AbstractTimeoutScriptEngine} 获得超时隔离与共享线程池，并对上下文做安全加固：
 * 禁用 {@code T()} 类型引用、不注册 BeanFactoryResolver（禁止 {@code @bean} 引用），
 * 仅暴露 {@code #state}（输入 Map）与 {@code #config}（节点配置 Map）两个白名单变量。</p>
 */
public class SpelScriptEngine extends AbstractTimeoutScriptEngine {

    private final SpelExpressionParser parser;

    public SpelScriptEngine() {
        this(500L);
    }

    public SpelScriptEngine(long executionTimeoutMs) {
        super(executionTimeoutMs);
        this.parser = new SpelExpressionParser(
                new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, null));
    }

    @Override
    public String engineId() {
        return "spel";
    }

    @Override
    public ScriptEngineDescriptor descriptor() {
        return new ScriptEngineDescriptor("spel", "SpEL 表达式", false, 3, "engine.spel.hint");
    }

    @Override
    public void validate(String script) {
        try {
            parser.parseExpression(script);
        } catch (org.springframework.expression.ParseException e) {
            throw new IllegalArgumentException("SpEL 表达式语法错误: " + e.getMessage(), e);
        }
    }

    @Override
    public Expression compile(String script) {
        return parser.parseExpression(script);
    }

    @Override
    protected Object doExecute(Object compiled, ScriptExecutionContext ctx) {
        Expression expression = (Expression) compiled;
        StandardEvaluationContext ec = new StandardEvaluationContext();
        // 白名单上下文：仅暴露 state 和 config
        ec.setVariable("state", ctx.state() != null ? ctx.state() : Map.of());
        ec.setVariable("config", ctx.config() != null ? ctx.config() : Map.of());
        // 安全加固：禁用 T() 类型引用（如 T(java.lang.Runtime)）
        ec.setTypeLocator(typeName -> {
            throw new EvaluationException("SpEL 禁止引用类型: " + typeName);
        });
        // 不注册 BeanFactoryResolver，禁止 @bean 引用

        try {
            return expression.getValue(ec);
        } catch (org.springframework.expression.spel.SpelEvaluationException e) {
            throw new IllegalArgumentException("SpEL 表达式执行失败: " + e.getMessage(), e);
        }
    }
}
