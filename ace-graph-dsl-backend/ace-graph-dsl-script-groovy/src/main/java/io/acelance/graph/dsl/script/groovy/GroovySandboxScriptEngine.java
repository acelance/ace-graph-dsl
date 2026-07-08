package io.acelance.graph.dsl.script.groovy;

import groovy.lang.Binding;
import groovy.lang.Script;
import io.acelance.graph.dsl.script.AbstractTimeoutScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineDescriptor;
import io.acelance.graph.dsl.script.ScriptExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * 基于 Groovy 的安全沙箱脚本引擎。
 *
 * <p><b>默认关闭</b>（需 {@code ace.graph.dsl.script.groovy-enabled=true} 显式开启）。
 * 复用 {@link AbstractTimeoutScriptEngine} 获得超时隔离；脚本经 {@link GroovyScriptCompiler} 沙箱编译
 * （黑名单危险类 + 导入白名单），执行时仅暴露 {@code state}/{@code config} 两个白名单变量。
 * 编译产物缓存受 {@code groovyMaxScriptCache} 约束。</p>
 */
public class GroovySandboxScriptEngine extends AbstractTimeoutScriptEngine {

    private final GroovyScriptCompiler compiler;

    public GroovySandboxScriptEngine() {
        this(500L, 200, List.of("java.util.*", "java.math.*", "java.time.*"));
    }

    public GroovySandboxScriptEngine(long executionTimeoutMs, int maxCache, List<String> allowedImports) {
        super(executionTimeoutMs);
        this.compiler = new GroovyScriptCompiler(allowedImports, maxCache);
    }

    @Override
    public String engineId() {
        return "groovy";
    }

    @Override
    public ScriptEngineDescriptor descriptor() {
        return new ScriptEngineDescriptor("groovy", "Groovy", true, 200, "engine.groovy.hint");
    }

    @Override
    public void validate(String script) {
        try {
            compiler.compile(script);
        } catch (Exception e) {
            throw new IllegalArgumentException("Groovy 脚本编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Class<? extends Script> compile(String script) {
        try {
            return compiler.compile(script);
        } catch (Exception e) {
            throw new IllegalArgumentException("Groovy 脚本编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    protected Object doExecute(Object compiled, ScriptExecutionContext ctx) throws Exception {
        Class<? extends Script> scriptClass = (Class<? extends Script>) compiled;
        Script scriptObj = scriptClass.getDeclaredConstructor().newInstance();
        Binding binding = new Binding();
        binding.setVariable("state", ctx.state() != null ? ctx.state() : Map.of());
        binding.setVariable("config", ctx.config() != null ? ctx.config() : Map.of());
        scriptObj.setBinding(binding);
        return scriptObj.run();
    }

    @Override
    public void close() {
        super.close();
        compiler.close();
    }
}
