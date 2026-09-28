package io.acelance.graph.dsl.bizparam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 汇聚所有 {@link NodeBizParamInterpreter}，并实现 {@link NodeBizParamCatalog}。
 */
public final class NodeBizParamInterpreterRegistry implements NodeBizParamCatalog {

    private static final Logger log = LoggerFactory.getLogger(NodeBizParamInterpreterRegistry.class);

    private final Map<String, NodeBizParamInterpreter> byId;

    public NodeBizParamInterpreterRegistry(List<NodeBizParamInterpreter> interpreters) {
        Map<String, NodeBizParamInterpreter> map = new LinkedHashMap<>();
        map.put(DefaultStringBizParamInterpreter.ID, DefaultStringBizParamInterpreter.INSTANCE);
        if (interpreters != null) {
            for (NodeBizParamInterpreter it : interpreters) {
                if (it == null) {
                    continue;
                }
                String id = it.id();
                NodeBizParamInterpreter prev = map.put(id, it);
                if (prev != null && prev != it) {
                    log.warn("NodeBizParamInterpreter id={} 被 {} 覆盖（原 {}）",
                            id, it.getClass().getName(), prev.getClass().getName());
                }
            }
        }
        this.byId = Map.copyOf(map);
        log.info("NodeBizParamInterpreter 已注册: {}", byId.keySet());
    }

    @Override
    public List<NodeBizParamItem> list(String graphId) {
        List<NodeBizParamItem> items = new ArrayList<>(byId.size());
        for (NodeBizParamInterpreter it : byId.values()) {
            items.add(new NodeBizParamItem(it.id(), it.displayName(), it.order()));
        }
        items.sort(ORDER);
        return List.copyOf(items);
    }

    public Optional<NodeBizParamInterpreter> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim()));
    }

    /** 找不到时回落 {@link DefaultStringBizParamInterpreter} */
    public NodeBizParamInterpreter resolveOrDefault(String id) {
        return find(id).orElse(DefaultStringBizParamInterpreter.INSTANCE);
    }
}
