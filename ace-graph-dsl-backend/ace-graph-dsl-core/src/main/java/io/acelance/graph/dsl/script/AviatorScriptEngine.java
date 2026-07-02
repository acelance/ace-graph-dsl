package io.acelance.graph.dsl.script;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Aviator 的脚本引擎实现。
 *
 * <p>脚本执行在共享线程池中进行并施加超时控制，避免每次调用 {@code new Thread} 的创建开销；
 * 通过 {@link #shutdown()}（Spring 会作为 {@code @Bean} 的推断销毁方法自动调用）优雅关闭。</p>
 */
public class AviatorScriptEngine implements ScriptEngine, AutoCloseable {

    private final AviatorEvaluatorInstance evaluator;
    private final long executionTimeoutMs;
    private final ExecutorService executor;
    private final boolean ownExecutor;

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
        this(executionTimeoutMs, defaultExecutor(poolSize), true);
    }

    /**
     * 使用外部提供的线程池（由调用方负责其生命周期，引擎不会关闭它）。
     */
    public AviatorScriptEngine(long executionTimeoutMs, ExecutorService executor) {
        this(executionTimeoutMs, executor, false);
    }

    private AviatorScriptEngine(long executionTimeoutMs, ExecutorService executor, boolean ownExecutor) {
        this.executionTimeoutMs = executionTimeoutMs;
        this.executor = executor;
        this.ownExecutor = ownExecutor;
        this.evaluator = AviatorEvaluator.newInstance();
        this.evaluator.setOption(Options.MAX_LOOP_COUNT, 10_000);
        this.evaluator.setOption(Options.OPTIMIZE_LEVEL, AviatorEvaluator.COMPILE);
    }

    private static ExecutorService defaultExecutor(int poolSize) {
        int max = poolSize > 0 ? poolSize : Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "aviator-script-exec-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // 使用 AbortPolicy：池满时拒绝而非在调用线程同步执行，从而保留每次执行的超时语义
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, max, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), factory,
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override
    public String engineId() {
        return "aviator";
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
    public Object execute(Object compiled, ScriptExecutionContext ctx) {
        Expression expression = (Expression) compiled;
        Map<String, Object> env = new HashMap<>();
        env.put("state", ctx.state());
        env.put("config", ctx.config());

        Future<Object> task;
        try {
            task = executor.submit(() -> expression.execute(env));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new IllegalStateException("脚本执行线程池繁忙，请稍后重试", e);
        }
        try {
            return task.get(executionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IllegalArgumentException("脚本执行超时（" + executionTimeoutMs + "ms）");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("脚本执行被中断", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalArgumentException("脚本执行失败: " + cause.getMessage(), cause);
        }
    }

    /** 关闭内部线程池（外部提供的线程池不受影响）。 */
    public void shutdown() {
        if (ownExecutor) {
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
