package io.acelance.graph.dsl.script;

import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 脚本条件边路由工厂：将路由表达式编译为图条件边 {@link EdgeAction}。
 *
 * <p>表达式以图状态（{@code state}）为白名单上下文执行，返回值（转为字符串）即条件边
 * mapping 的 key。借助 {@link ScriptEngine}（默认 aviator），路由规则可在 DSL 中动态配置，
 * 无需为分支逻辑编写 Java {@code RegisteredEdgeDispatcher} 并发版。</p>
 */
@Component
public class ScriptEdgeActionFactory {

    private final ScriptEngineRegistry engineRegistry;

    public ScriptEdgeActionFactory(ScriptEngineRegistry engineRegistry) {
        this.engineRegistry = engineRegistry;
    }

    /** 引擎是否可用（用于校验阶段判断脚本路由是否支持）。 */
    public boolean supports(String engineId) {
        return engineRegistry.supports(engineId);
    }

    /**
     * 校验路由表达式（语法）。
     *
     * @throws IllegalArgumentException 引擎不存在或表达式非法时
     */
    public void validate(String engineId, String expression) {
        engineRegistry.require(engineId).validate(expression);
    }

    /**
     * 将路由表达式编译为条件边 {@link EdgeAction}。
     *
     * @param engineId   脚本引擎 ID
     * @param expression 路由表达式（返回 mapping key）
     * @return 条件边 action，apply 返回路由 key 字符串
     */
    public EdgeAction create(String engineId, String expression) {
        ScriptEngine engine = engineRegistry.require(engineId);
        engine.validate(expression);
        Object compiled = engine.compile(expression);
        return state -> {
            Map<String, Object> snapshot = state.data();
            ScriptExecutionContext ctx = new ScriptExecutionContext(snapshot, Map.of());
            Object result = engine.execute(compiled, ctx);
            return result == null ? "" : String.valueOf(result);
        };
    }
}
