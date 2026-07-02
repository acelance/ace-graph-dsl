package io.acelance.graph.dsl.registry;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 条件边分发器注册中心：Spring 启动时自动收集所有 {@link RegisteredEdgeDispatcher} Bean。
 */
@Component
public class EdgeDispatcherRegistry {

    private final Map<String, RegisteredEdgeDispatcher> dispatchersById = new ConcurrentHashMap<>();

    public EdgeDispatcherRegistry(List<RegisteredEdgeDispatcher> all) {
        for (RegisteredEdgeDispatcher d : all) {
            RegisteredEdgeDispatcher prev = dispatchersById.putIfAbsent(d.dispatcherId(), d);
            if (prev != null) {
                throw new IllegalStateException(
                        "Dispatcher ID 冲突: " + d.dispatcherId()
                                + " (已注册: " + prev.getClass().getName()
                                + ", 新增: " + d.getClass().getName() + ")");
            }
        }
    }

    /** 列出所有已注册 dispatcher */
    public List<RegisteredEdgeDispatcher> list() {
        return dispatchersById.values().stream().toList();
    }

    /** 根据 dispatcherId 获取已注册 dispatcher */
    public RegisteredEdgeDispatcher get(String dispatcherId) {
        RegisteredEdgeDispatcher d = dispatchersById.get(dispatcherId);
        if (d == null) {
            throw new IllegalArgumentException("未注册 dispatcher: " + dispatcherId);
        }
        return d;
    }

    /** 判断 dispatcherId 是否已注册 */
    public boolean contains(String dispatcherId) {
        return dispatchersById.containsKey(dispatcherId);
    }
}
