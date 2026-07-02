package io.acelance.graph.dsl.script;

/**
 * 脚本引擎抽象。实现类负责编译与执行脚本。
 */
public interface ScriptEngine {

    /** 引擎标识，如 aviator */
    String engineId();

    /** 校验脚本语法 */
    void validate(String script);

    /** 预编译脚本，返回引擎内部 compiled 对象 */
    Object compile(String script);

    /** 执行已编译脚本 */
    Object execute(Object compiled, ScriptExecutionContext ctx);
}
