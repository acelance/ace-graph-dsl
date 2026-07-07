package io.acelance.graph.dsl.script;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 基于 Aviator 的脚本引擎实现。
 *
 * <p>复用 {@link AbstractTimeoutScriptEngine} 提供的超时隔离与共享线程池，
 * 避免每次调用 {@code new Thread} 的创建开销；通过 {@link #close()}（Spring 会作为
 * {@code @Bean} 的推断销毁方法自动调用）优雅关闭内部线程池。</p>
 */
public class AviatorScriptEngine extends AbstractTimeoutScriptEngine {

    private final AviatorEvaluatorInstance evaluator;

    public AviatorScriptEngine() {
        this(500L);
    }

    public AviatorScriptEngine(long executionTimeoutMs) {
        this(executionTimeoutMs, 0);
    }

    /**
     * @param executionTimeoutMs 单次脚本执行超时（毫秒）
     * @param poolSize           执行线程池最大线程数；{@code <= 0} 时按 CPU 核数自动取值（下限 2）
     */
    public AviatorScriptEngine(long executionTimeoutMs, int poolSize) {
        super(executionTimeoutMs, poolSize);
        this.evaluator = newEvaluator();
    }

    /**
     * 使用外部提供的线程池（由调用方负责其生命周期，引擎不会关闭它）。
     */
    public AviatorScriptEngine(long executionTimeoutMs, ExecutorService executor) {
        super(executionTimeoutMs, executor);
        this.evaluator = newEvaluator();
    }

    private static AviatorEvaluatorInstance newEvaluator() {
        AviatorEvaluatorInstance instance = AviatorEvaluator.newInstance();
        instance.setOption(Options.MAX_LOOP_COUNT, 10_000);
        instance.setOption(Options.OPTIMIZE_LEVEL, AviatorEvaluator.COMPILE);
        return instance;
    }

    @Override
    public String engineId() {
        return "aviator";
    }

    @Override
    public ScriptEngineDescriptor descriptor() {
        return new ScriptEngineDescriptor("aviator", "Aviator 表达式", false, 3, "engine.aviator.hint");
    }

    @Override
    public void validate(String script) {
        try {
            evaluator.validate(script);
        } catch (RuntimeException e) {
            // 包装 Aviator 内部异常为稳定的 API 契约（与 execute 一致）
            throw new IllegalArgumentException("脚本语法错误: " + e.getMessage(), e);
        }
    }

    @Override
    public Expression compile(String script) {
        return evaluator.compile(script, true);
    }

    @Override
    protected Object doExecute(Object compiled, ScriptExecutionContext ctx) {
        Expression expression = (Expression) compiled;
        Map<String, Object> env = new HashMap<>();
        env.put("state", ctx.state());
        env.put("config", ctx.config());
        return expression.execute(env);
    }
}
