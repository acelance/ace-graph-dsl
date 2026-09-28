package io.acelance.graph.dsl.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 在 {@link #emit} 时先同步通知 {@link TokenChunkObserver}，再委托底层桥接。
 */
public final class ObservingGraphStreamBridge implements GraphStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(ObservingGraphStreamBridge.class);

    private final GraphStreamBridge delegate;
    private final List<TokenChunkObserver> observers;

    public ObservingGraphStreamBridge(GraphStreamBridge delegate, List<TokenChunkObserver> observers) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observers = observers == null || observers.isEmpty()
                ? List.of()
                : List.copyOf(observers);
    }

    @Override
    public void emit(String runId, TokenChunk chunk) {
        for (TokenChunkObserver observer : observers) {
            try {
                observer.onEmit(runId, chunk);
            }
            catch (RuntimeException ex) {
                log.warn("TokenChunkObserver 失败 runId={}: {}", runId, ex.toString());
            }
        }
        delegate.emit(runId, chunk);
    }

    @Override
    public void complete(String runId) {
        delegate.complete(runId);
    }

    @Override
    public Flux<TokenChunk> register(String runId) {
        return delegate.register(runId);
    }

    public GraphStreamBridge getDelegate() {
        return delegate;
    }

    public List<TokenChunkObserver> getObservers() {
        return observers;
    }
}
