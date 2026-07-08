package io.acelance.graph.dsl.script;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带超时隔离与共享线程池的脚本引擎执行基类。
 *
 * <p>所有脚本引擎（Aviator / SpEL / QLExpress / Groovy）统一通过本基类执行脚本，
 * 避免恶意或死循环脚本阻塞调用线程；线程池满时采用 {@code AbortPolicy}（拒绝而非在
 * 调用线程同步执行），从而保留每次执行的超时语义。子类只需实现 {@link #doExecute}
 * 完成具体的编译产物执行逻辑，编译产物由 {@link #compile(String)} 产生。</p>
 */
public abstract class AbstractTimeoutScriptEngine implements ScriptEngine, AutoCloseable {

    protected final long executionTimeoutMs;
    protected final ExecutorService executor;
    private final boolean ownExecutor;

    protected AbstractTimeoutScriptEngine(long executionTimeoutMs) {
        this(executionTimeoutMs, 0);
    }

    protected AbstractTimeoutScriptEngine(long executionTimeoutMs, int poolSize) {
        this(executionTimeoutMs, defaultExecutor(poolSize), true);
    }

    /** 使用外部提供的线程池（由调用方负责其生命周期，引擎不会关闭它）。 */
    protected AbstractTimeoutScriptEngine(long executionTimeoutMs, ExecutorService executor) {
        this(executionTimeoutMs, executor, false);
    }

    private AbstractTimeoutScriptEngine(long executionTimeoutMs, ExecutorService executor, boolean ownExecutor) {
        this.executionTimeoutMs = executionTimeoutMs;
        this.executor = executor;
        this.ownExecutor = ownExecutor;
    }

    /** 在共享线程池内执行编译后的脚本；允许抛出受检异常，由基类统一包装为运行时契约。 */
    protected abstract Object doExecute(Object compiled, ScriptExecutionContext ctx) throws Exception;

    @Override
    public Object execute(Object compiled, ScriptExecutionContext ctx) {
        Future<Object> task;
        try {
            task = executor.submit(() -> {
                try {
                    return doExecute(compiled, ctx);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
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
        } catch (java.util.concurrent.ExecutionException e) {
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

    private static ExecutorService defaultExecutor(int poolSize) {
        int max = poolSize > 0 ? poolSize : Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "script-exec-" + seq.incrementAndGet());
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
}
