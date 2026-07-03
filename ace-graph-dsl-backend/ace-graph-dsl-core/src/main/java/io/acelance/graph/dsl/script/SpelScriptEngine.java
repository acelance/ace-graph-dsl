package io.acelance.graph.dsl.script;

import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

/**
 * 基于 Spring SpEL（Spring Expression Language）的脚本引擎实现。
 *
 * <p>零额外依赖（Spring 内置），适合单行表达式场景，与 Aviator 形成互补。
 * SpEL 表达式执行通常在微秒级，直接同步执行，不使用独立线程池。</p>
 *
 * <p>安全限制：上下文仅暴露 {@code #state}（输入 Map）和 {@code #config}（节点配置 Map）。
 * 表达式来源为设计器内受信操作者，信任模型与 {@link AviatorScriptEngine} 保持一致。</p>
 */
public class SpelScriptEngine implements ScriptEngine {

    private final SpelExpressionParser parser;

    public SpelScriptEngine() {
        this.parser = new SpelExpressionParser();
    }

    @Override
    public String engineId() {
        return "spel";
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
    public Object execute(Object compiled, ScriptExecutionContext ctx) {
        Expression expression = (Expression) compiled;
        StandardEvaluationContext ec = new StandardEvaluationContext();
        // 白名单上下文：仅暴露 state 和 config
        ec.setVariable("state", ctx.state() != null ? ctx.state() : Map.of());
        ec.setVariable("config", ctx.config() != null ? ctx.config() : Map.of());

        try {
            return expression.getValue(ec);
        } catch (org.springframework.expression.spel.SpelEvaluationException e) {
            throw new IllegalArgumentException("SpEL 表达式执行失败: " + e.getMessage(), e);
        }
    }
}
