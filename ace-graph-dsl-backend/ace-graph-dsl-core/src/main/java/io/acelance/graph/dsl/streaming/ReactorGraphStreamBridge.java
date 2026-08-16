package io.acelance.graph.dsl.streaming;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 reactor {@link Sinks} 的 {@link GraphStreamBridge} 实现。
 *
 * <p>每个 runId 持有一个 {@code unicast().onBackpressureBuffer()} 的 Sink：支持单订阅者
 * （控制器合并订阅）并<strong>缓冲订阅前的早期片段</strong>——即使节点在控制器订阅前就已开始 emit
 * （异步 LLM），片段也会被缓存并在订阅后回放，不会被丢弃。emit 失败（如已关闭 / 背压溢出）
 * 时静默丢弃该片段，不中断主流程。</p>
 */
public class ReactorGraphStreamBridge implements GraphStreamBridge {

    private final Map<String, Sinks.Many<TokenChunk>> sinks = new ConcurrentHashMap<>();

    @Override
    public Flux<TokenChunk> register(String runId) {
        Sinks.Many<TokenChunk> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(runId, sink);
        return sink.asFlux();
    }

    @Override
    public void emit(String runId, TokenChunk chunk) {
        Sinks.Many<TokenChunk> sink = sinks.get(runId);
        if (sink == null) {
            return;
        }
        // tryEmitNext 不抛异常；仅返回 EmitResult，失败则丢弃该片段
        sink.tryEmitNext(chunk);
    }

    @Override
    public void complete(String runId) {
        Sinks.Many<TokenChunk> sink = sinks.remove(runId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}
