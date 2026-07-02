package io.acelance.graph.dsl.definition;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * 图边定义。
 * <p>
 * 普通边：{@code type="normal"}，使用 from/to。
 * <p>
 * 条件边：{@code type="conditional"}，使用 from/mapping，to 留空，路由来源二选一：
 * <ul>
 *   <li><b>Java Dispatcher</b>：填 {@code dispatcher}（已注册的 {@code RegisteredEdgeDispatcher} ID）。</li>
 *   <li><b>脚本路由</b>：填 {@code condition}（脚本路由表达式，返回 mapping 的 key），无需 Java 发版。</li>
 * </ul>
 * 两者同时存在时以 {@code dispatcher} 优先；脚本引擎由 {@code conditionEngine} 指定（默认 aviator）。
 *
 * @param from            源节点 ID 或保留字 __START__
 * @param to              目标节点 ID 或保留字 __END__（条件边时留空）
 * @param type            边类型：normal / conditional
 * @param dispatcher      条件边引用的 dispatcher ID（Java 路由）
 * @param mapping         条件边映射：路由输出值 → 目标节点 ID
 * @param condition       条件边脚本路由表达式（返回 mapping 的 key），与 dispatcher 二选一
 * @param conditionEngine 脚本路由引擎 ID，缺省为 aviator
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE
)
public record GraphEdge(
        String from,
        String to,
        String type,
        String dispatcher,
        Map<String, String> mapping,
        String condition,
        String conditionEngine
) {

    /** 普通边类型 */
    public static final String TYPE_NORMAL = "normal";
    /** 条件边类型 */
    public static final String TYPE_CONDITIONAL = "conditional";
    /** 脚本路由默认引擎 */
    public static final String DEFAULT_CONDITION_ENGINE = "aviator";

    /** 判断是否为条件边（不参与 JSON 序列化，避免产生 {@code conditional} 冗余字段） */
    @JsonIgnore
    public boolean isConditional() {
        return TYPE_CONDITIONAL.equals(type);
    }

    /** 判断条件边是否为脚本路由（无 dispatcher 但有 condition 表达式） */
    @JsonIgnore
    public boolean isScriptRouting() {
        boolean noDispatcher = dispatcher == null || dispatcher.isBlank();
        boolean hasCondition = condition != null && !condition.isBlank();
        return noDispatcher && hasCondition;
    }

    /** 脚本路由引擎 ID，缺省 aviator */
    @JsonIgnore
    public String resolvedConditionEngine() {
        return conditionEngine != null && !conditionEngine.isBlank()
                ? conditionEngine
                : DEFAULT_CONDITION_ENGINE;
    }
}
