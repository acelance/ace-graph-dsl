package io.acelance.graph.dsl.definition;

import java.util.List;
import java.util.Map;

/**
 * 图定义 DSL 根模型，对应前端保存的 JSON。
 *
 * @param graphId        图定义业务标识，如 "cs-reply-m2"
 * @param displayName    显示名
 * @param version        版本号（semver）
 * @param description    描述
 * @param keyStrategies  state key → 策略名（REPLACE / APPEND）
 * @param nodes          节点引用列表
 * @param edges          边列表
 * @param compile        编译配置
 */
public record GraphDefinition(
        String graphId,
        String displayName,
        String version,
        String description,
        Map<String, String> keyStrategies,
        List<NodeRef> nodes,
        List<GraphEdge> edges,
        CompileConfigDto compile
) {

    /** 保留字：起点 */
    public static final String START = "__START__";
    /** 保留字：终点 */
    public static final String END = "__END__";
}
