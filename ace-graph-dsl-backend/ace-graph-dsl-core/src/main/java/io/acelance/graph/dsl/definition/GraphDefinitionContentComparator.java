package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;
import java.util.Map;

/**
 * 比较 Graph DSL 的「可执行内容」是否相同（不含 version / displayName / description 等元信息）。
 */
public final class GraphDefinitionContentComparator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private GraphDefinitionContentComparator() {
    }

    /**
     * 判断两份定义的可执行内容是否一致。
     */
    public static boolean sameContent(GraphDefinition a, GraphDefinition b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return canonical(a).equals(canonical(b));
    }

    static String canonical(GraphDefinition def) {
        try {
            return MAPPER.writeValueAsString(contentSnapshot(def));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("GraphDefinition 内容序列化失败", e);
        }
    }

    private static ContentSnapshot contentSnapshot(GraphDefinition def) {
        return new ContentSnapshot(
                def.keyStrategies(),
                def.nodes(),
                def.edges(),
                def.compile());
    }

    private record ContentSnapshot(
            Map<String, String> keyStrategies,
            List<NodeRef> nodes,
            List<GraphEdge> edges,
            CompileConfigDto compile
    ) {
    }
}
