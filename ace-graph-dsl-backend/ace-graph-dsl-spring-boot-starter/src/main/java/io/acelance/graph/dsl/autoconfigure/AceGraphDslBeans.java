package io.acelance.graph.dsl.autoconfigure;

/**
 * Ace Graph DSL 库内部 Bean 的限定名常量。
 *
 * <p>用于在多 Bean 共存（如宿主自带 {@code ObjectMapper}）时进行精确注入，避免冲突。</p>
 */
public final class AceGraphDslBeans {

    private AceGraphDslBeans() {
    }

    /** 库内部专用 ObjectMapper 的 Bean 名称。 */
    public static final String OBJECT_MAPPER = "aceGraphDslObjectMapper";

    /** 菜单权限 SPI 真实实现（非请求级缓存装饰器）。 */
    public static final String GRAPH_MENU_ACCESS_CONTROL_DELEGATE = "graphMenuAccessControlDelegate";
}
