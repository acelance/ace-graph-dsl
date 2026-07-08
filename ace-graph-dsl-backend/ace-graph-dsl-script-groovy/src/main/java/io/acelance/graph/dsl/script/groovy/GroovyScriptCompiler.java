package io.acelance.graph.dsl.script.groovy;

import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带沙箱编译配置的 Groovy 脚本编译器。
 *
 * <p>通过 {@link SecureASTCustomizer} 黑名单危险类（System / Runtime / ProcessBuilder / 反射 / GroovyShell 等）、
 * 禁止方法定义与包导入；通过 {@link ImportCustomizer} 白名单导入指定包（默认 java.util / java.math / java.time）。
 * 编译产物（{@code Class<? extends Script>}）按脚本文本缓存，受 {@code maxCache} 上限约束（超出时淘汰首个条目）。</p>
 */
public class GroovyScriptCompiler {

    private final GroovyClassLoader classLoader;
    private final Map<String, Class<? extends Script>> cache = new ConcurrentHashMap<>();
    private final int maxCache;

    public GroovyScriptCompiler(List<String> allowedImports, int maxCache) {
        this.maxCache = maxCache;
        CompilerConfiguration config = new CompilerConfiguration();
        config.setSourceEncoding("UTF-8");

        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setMethodDefinitionAllowed(false);
        secure.setPackageAllowed(false);
        secure.setDisallowedReceivers(Arrays.asList(
                "java.lang.System",
                "java.lang.Runtime",
                "java.lang.ProcessBuilder",
                "java.lang.Thread",
                "java.lang.Class",
                "java.lang.reflect",
                "groovy.lang.GroovyShell",
                "groovy.util.GroovyScriptEngine",
                "groovy.lang.GroovyClassLoader"));
        secure.setDisallowedImports(Arrays.asList(
                "java.lang.System",
                "java.lang.Runtime",
                "java.lang.ProcessBuilder",
                "java.lang.reflect"));

        ImportCustomizer imports = new ImportCustomizer();
        if (allowedImports != null && !allowedImports.isEmpty()) {
            // 配置项形如 "java.util.*"，ImportCustomizer 接受包名（去掉末尾 .*）
            String[] packages = allowedImports.stream()
                    .map(p -> p.endsWith(".*") ? p.substring(0, p.length() - 2) : p)
                    .toArray(String[]::new);
            imports.addStarImports(packages);
        }

        config.addCompilationCustomizers(imports, secure);
        this.classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config);
    }

    @SuppressWarnings("unchecked")
    public Class<? extends Script> compile(String script) {
        Class<? extends Script> cached = cache.get(script);
        if (cached != null) {
            return cached;
        }
        Class<?> clazz = classLoader.parseClass(script);
        if (!Script.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Groovy 脚本必须返回 groovy.lang.Script 类型");
        }
        Class<? extends Script> scriptClass = (Class<? extends Script>) clazz;
        if (cache.size() >= maxCache) {
            cache.keySet().stream().findAny().ifPresent(cache::remove);
        }
        cache.put(script, scriptClass);
        return scriptClass;
    }

    public void close() {
        try {
            classLoader.close();
        } catch (java.io.IOException e) {
            // GroovyClassLoader 关闭时可能抛出 IO 异常，忽略
        }
    }
}
