package io.acelance.graph.dsl.bizparam;

/**
 * 节点业务附加参数解释器：将设计器文本框中的 raw（JSON 或纯文本）解析为业务对象。
 *
 * <p>框架只认 {@link #id()} 与 {@link #interpret(String)}；返回类型由业务自定。
 * 推荐配合 {@link NodeBizParamHandler} 注解声明 id / 展示名。</p>
 */
public interface NodeBizParamInterpreter {

    /**
     * 稳定编码。默认读 {@link NodeBizParamHandler#id()}；无注解时须覆盖。
     */
    default String id() {
        NodeBizParamHandler ann = getClass().getAnnotation(NodeBizParamHandler.class);
        if (ann == null || ann.id().isBlank()) {
            throw new IllegalStateException(getClass().getName()
                    + " 须标注 @NodeBizParamHandler(id=...) 或覆盖 id()");
        }
        return ann.id().trim();
    }

    default String displayName() {
        NodeBizParamHandler ann = getClass().getAnnotation(NodeBizParamHandler.class);
        if (ann != null && !ann.displayName().isBlank()) {
            return ann.displayName().trim();
        }
        return id();
    }

    default int order() {
        NodeBizParamHandler ann = getClass().getAnnotation(NodeBizParamHandler.class);
        return ann != null ? ann.order() : 100;
    }

    /**
     * @param raw 节点 {@code bizParamRaw}，可为 null/空白
     * @return 业务对象；null 表示无有效参数
     */
    Object interpret(String raw);
}
