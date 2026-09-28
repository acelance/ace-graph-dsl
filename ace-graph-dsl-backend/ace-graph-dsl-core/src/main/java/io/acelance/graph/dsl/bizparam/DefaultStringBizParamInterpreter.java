package io.acelance.graph.dsl.bizparam;

/**
 * 框架默认解释器：原样返回 String，供业务未实现时兜底。
 */
@NodeBizParamHandler(id = DefaultStringBizParamInterpreter.ID, displayName = "纯文本（默认）", order = 0)
public final class DefaultStringBizParamInterpreter implements NodeBizParamInterpreter {

    public static final String ID = "string";

    public static final DefaultStringBizParamInterpreter INSTANCE = new DefaultStringBizParamInterpreter();

    @Override
    public Object interpret(String raw) {
        return raw == null ? "" : raw;
    }
}
