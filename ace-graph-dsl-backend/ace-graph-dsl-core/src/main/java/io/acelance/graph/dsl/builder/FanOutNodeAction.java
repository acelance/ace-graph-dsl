package io.acelance.graph.dsl.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

/**
 * 异步扇出执行器（fan-out / scatter）。
 *
 * <p>spring-ai-alibaba-graph 1.1.0.0-M4 的 {@link com.alibaba.cloud.ai.graph.StateGraph}
 * 仅提供 {@code addEdge(String,String)} 与 {@code addConditionalEdges}，<b>没有原生并行边 API</b>。
 * 因此「并行扇出」由本执行器承载：构建器把某个源节点的多条 {@code parallel=true} 出边目标
 * 各自编译成独立的 {@link CompiledGraph}（{@code START → branch → END}），
 * 由本节点在其 {@link #apply(OverAllState)} 中以 {@link CompletableFuture#allOf} 并发
 * 调用各分支（交由独立线程池 {@link #FANOUT_EXECUTOR} 调度，避免与框架 commonPool 争用导致串行），
 * 再把所有分支产出的 state 合并写回，流向聚合（fan-in）节点。</p>
 *
 * <p>每个分支基于源节点 state 的<b>快照</b>独立运行，结果按分支返回的 key 合并；
 * 主图 {@code keyStrategies} 中对应 key 声明为 REPLACE 即可正确落回。</p>
 */
public class FanOutNodeAction implements AsyncNodeAction {

    /**
     * 专用线程池：保证各分支真正并发。框架自身的异步节点走 ForkJoinPool.commonPool，
     * 若其并行度受限（如测试/低核环境 = 1）会把分支串行化；独立池与之解耦，杜绝饿死。
     */
    private static final ExecutorService FANOUT_EXECUTOR = Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final ThreadFactory delegate = Executors.defaultThreadFactory();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = delegate.newThread(r);
                    t.setName("fanout-branch-" + t.getId());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final List<CompiledGraph> branches;

    public FanOutNodeAction(List<CompiledGraph> branches) {
        this.branches = List.copyOf(branches);
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        List<CompletableFuture<Map<String, Object>>> futures = branches.stream()
                .map(branch -> CompletableFuture.supplyAsync(() -> {
                    // 基于源 state 快照独立运行，避免并发读写共享 OverAllState
                    Map<String, Object> snapshot = new LinkedHashMap<>(state.data());
                    return branch.invoke(snapshot, RunnableConfig.builder().build())
                            .map(s -> (Map<String, Object>) new LinkedHashMap<>(s.data()))
                            .orElseGet(LinkedHashMap::new);
                }, FANOUT_EXECUTOR))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    for (CompletableFuture<Map<String, Object>> f : futures) {
                        merged.putAll(f.join());
                    }
                    return merged;
                });
    }
}
