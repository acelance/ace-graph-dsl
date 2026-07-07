package io.acelance.graph.dsl.script.qlexpress;

import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;
import com.ql.util.express.InstructionSet;
import com.ql.util.express.Operator;
import com.ql.util.express.config.QLExpressRunStrategy;
import io.acelance.graph.dsl.script.AbstractTimeoutScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineDescriptor;
import io.acelance.graph.dsl.script.ScriptExecutionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 QLExpress 的多行脚本引擎实现。
 *
 * <p>复用 {@link AbstractTimeoutScriptEngine} 获得超时隔离与共享线程池。安全策略：
 * 全局关闭静态方法调用与反射调用（阻止 {@code Runtime.getRuntime()}/{@code System.exit()}/反射等），
 * 并对脚本文本做 {@code import} 关键字检查，禁止导入任意类；仅暴露 {@code state}/{@code config}
 * 两个白名单变量；同时注册自定义函数 {@code map(k1,v1,...)} 简化 Map 返回（与 Aviator {@code seq.map} 对齐文档约定）。</p>
 *
 * <p>注意：QLExpress 的 {@link QLExpressRunStrategy} 为 JVM 级全局静态配置，多实例重复设置幂等，
 * 本库为 classpath 内主要使用者，可接受。</p>
 */
public class QlExpressScriptEngine extends AbstractTimeoutScriptEngine {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("\\bimport\\b", Pattern.CASE_INSENSITIVE);

    private final ExpressRunner runner;

    public QlExpressScriptEngine() {
        this(500L);
    }

    public QlExpressScriptEngine(long executionTimeoutMs) {
        super(executionTimeoutMs);
        // 全局安全策略：启用沙箱模式，禁止安全风险方法与构造器（拦截 Runtime/System/ProcessBuilder/反射等）。
        // 配合本类的 import 关键字检查，进一步禁止导入任意类。
        QLExpressRunStrategy.setSandBoxMode(true);
        QLExpressRunStrategy.setForbidInvokeSecurityRiskMethods(true);
        QLExpressRunStrategy.setForbidInvokeSecurityRiskConstructors(true);
        this.runner = new ExpressRunner(false, false);
        this.runner.addFunction("map", new MapOperator());
    }

    @Override
    public String engineId() {
        return "qlexpress";
    }

    @Override
    public ScriptEngineDescriptor descriptor() {
        return new ScriptEngineDescriptor("qlexpress", "QLExpress", true, 100, "engine.qlexpress.hint");
    }

    @Override
    public void validate(String script) {
        rejectImport(script);
        try {
            runner.parseInstructionSet(script);
        } catch (Exception e) {
            throw new IllegalArgumentException("QLExpress 脚本语法错误: " + e.getMessage(), e);
        }
    }

    @Override
    public InstructionSet compile(String script) {
        rejectImport(script);
        try {
            return runner.parseInstructionSet(script);
        } catch (Exception e) {
            throw new IllegalArgumentException("QLExpress 脚本编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    protected Object doExecute(Object compiled, ScriptExecutionContext ctx) throws Exception {
        InstructionSet instructionSet = (InstructionSet) compiled;
        DefaultContext<String, Object> context = new DefaultContext<>();
        context.put("state", ctx.state() != null ? ctx.state() : Map.of());
        context.put("config", ctx.config() != null ? ctx.config() : Map.of());
        List<String> errorList = new ArrayList<>();
        Object result = runner.execute(instructionSet, context, errorList, true, false);
        if (!errorList.isEmpty()) {
            throw new IllegalArgumentException("QLExpress 执行错误: " + errorList);
        }
        return result;
    }

    private static void rejectImport(String script) {
        if (IMPORT_PATTERN.matcher(script).find()) {
            throw new IllegalArgumentException("QLExpress 脚本禁止 import 语句（仅允许 state/config 白名单变量）");
        }
    }

    /** 自定义函数 map(k1, v1, k2, v2, ...) → LinkedHashMap，对齐 Aviator seq.map 文档约定 */
    private static class MapOperator extends Operator {
        @Override
        public Object executeInner(Object[] args) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                map.put(String.valueOf(args[i]), args[i + 1]);
            }
            return map;
        }
    }
}
