package io.acelance.graph.dsl.saa;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子 Agent instruction 模板渲染：将 {@code {stateKey}} 替换为 OverAllState 中的值。
 */
public final class InstructionTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");

    private InstructionTemplate() {
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
     * 从 Map 渲染（无 OverAllState 时）。
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
        if (value instanceof org.springframework.ai.chat.messages.AssistantMessage msg) {
            return msg.getText() != null ? msg.getText() : "";
        }
        return String.valueOf(value);
    }
}
