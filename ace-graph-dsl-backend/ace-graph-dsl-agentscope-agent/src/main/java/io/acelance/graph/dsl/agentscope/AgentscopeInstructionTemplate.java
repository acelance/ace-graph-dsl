package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGENTSCOPE 子 Agent instruction 模板：将 {@code {stateKey}} 替换为 OverAllState 中的值。
 *
 * <p>语义对齐 saa-agent {@code InstructionTemplate}，本模块不依赖 saa-agent 运行时以免耦合。</p>
 */
public final class AgentscopeInstructionTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");

    private AgentscopeInstructionTemplate() {
    }

    /**
     * 渲染 instruction；模板为空时返回空串。
     *
     * @param template instruction 模板
     * @param state    当前 Flow / 父 state
     * @return 渲染后文本
     */
    public static String render(String template, OverAllState state) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        if (state == null) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            Object value = state.value(key).orElse("");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从 Map 渲染（单测用）。
     *
     * @param template 模板
     * @param vars     变量表
     * @return 渲染结果
     */
    public static String render(String template, Map<String, Object> vars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            Object value = vars.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }
}
