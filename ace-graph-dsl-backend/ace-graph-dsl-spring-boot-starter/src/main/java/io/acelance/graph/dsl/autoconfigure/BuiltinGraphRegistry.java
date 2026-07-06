package io.acelance.graph.dsl.autoconfigure;

import io.acelance.graph.dsl.definition.GraphDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存级的内置图注册中心。
 *
 * <p>宿主应用可在启动时通过 {@link #register(GraphDefinition)} 注册代码内建图，
 * 这些图仅存在于内存，不持久化到 SQLite / Redis / MySQL。</p>
 *
 * <p>{@link io.acelance.graph.dsl.web.GraphCatalogController} 会自动合并这些内置图到目录列表中。</p>
 */
@Component
public class BuiltinGraphRegistry {

    private final Map<String, GraphDefinition> builtins = new LinkedHashMap<>();

    /** 注册一个内置图（graphId 同名则覆盖） */
    public void register(GraphDefinition def) {
        builtins.put(def.graphId(), def);
    }

    /** 列出全部已注册的内置图 */
    public List<GraphDefinition> listAll() {
        return List.copyOf(builtins.values());
    }

    /** 按 graphId 获取内置图，不存在返回 null */
    public GraphDefinition get(String graphId) {
        return builtins.get(graphId);
    }

    /** 判断 graphId 是否为内置图 */
    public boolean contains(String graphId) {
        return builtins.containsKey(graphId);
    }
}
