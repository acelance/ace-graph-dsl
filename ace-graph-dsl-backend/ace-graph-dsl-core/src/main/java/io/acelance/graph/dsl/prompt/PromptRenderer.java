package io.acelance.graph.dsl.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 变量渲染器（框架内部，§4.4.2）。
 *
 * <p>严格单遍替换：替换入的内容不再参与后续扫描，避免 state 值携带 {@code {{...}}} 造成模板注入。
 * 替换值必须经 {@link Matcher#quoteReplacement}，否则 {@code $}/{@code \} 会被当作组引用。</p>
 */
public final class PromptRenderer {

    private static final Logger log = LoggerFactory.getLogger(PromptRenderer.class);

    /** {{ state.key }} / {{ key }}，允许内部空白 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*(?:state\\.)?([A-Za-z0-9_.\\-]+)\\s*}}");

    private final ObjectMapper objectMapper;
    private final PromptRenderProperties props;

    public PromptRenderer(ObjectMapper objectMapper, PromptRenderProperties props) {
        this.objectMapper = Objects.requireNonNullElseGet(objectMapper, ObjectMapper::new);
        this.props = props != null ? props : PromptRenderProperties.defaults();
    }

    public PromptRenderer() {
        this(new ObjectMapper(), PromptRenderProperties.defaults());
    }

    /**
     * 单遍渲染。
     *
     * @param template 已合并的模板（多 promptKeys 按序拼接后传入）
     * @param snapshot 变量快照，system/user 共用同一份
     * @param nodeId   日志用
     * @return 已渲染纯文本（可能被总长截断）
     */
    public String render(String template, Map<String, Object> snapshot, String nodeId) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, Object> snap = snapshot != null ? snapshot : Map.of();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (m.find()) {
            String name = m.group(1);
            String value = stringify(snap, name, nodeId);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return truncateTotal(sb.toString(), nodeId);
    }

    /**
     * 扫描模板中的占位符名（去重）。
     */
    public Set<String> findPlaceholders(String template) {
        Set<String> names = new HashSet<>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * 编译期双向比对：模板用了但 inputKeys 未声明 / 声明了但未引用 → warn。
     */
    public void warnInputKeyMismatch(String nodeId, String template, Set<String> inputKeys) {
        Set<String> used = findPlaceholders(template);
        Set<String> declared = inputKeys != null ? inputKeys : Set.of();
        for (String u : used) {
            if (!declared.contains(u)) {
                log.warn("节点 {} 的 prompt 引用了未声明的变量 {}，运行期将渲染为空串；"
                                + "请将其补入 inputKeys 以纳入边可达性校验",
                        nodeId, u);
            }
        }
        for (String d : declared) {
            if (!used.contains(d)) {
                log.warn("节点 {} 的 inputKeys 声明了 {} 但 prompt 未引用，建议移除", nodeId, d);
            }
        }
    }

    private String stringify(Map<String, Object> snapshot, String name, String nodeId) {
        Object v = snapshot.get(name);
        if (v == null) {
            if (props.strictVariables()) {
                throw new IllegalStateException(String.format(
                        "节点 %s 的 prompt 变量 %s 无值（严格模式）。请检查上游是否写入该 state key，"
                                + "或关闭 ace.graph.dsl.prompt.strict-variables", nodeId, name));
            }
            log.warn("节点 {} 的 prompt 变量 {} 无值，渲染为空串", nodeId, name);
            return "";
        }
        String text;
        if (v instanceof String s) {
            text = s;
        } else if (v instanceof Number || v instanceof Boolean) {
            text = String.valueOf(v);
        } else {
            text = toJson(v, nodeId, name);
        }
        return truncateValue(text, nodeId, name);
    }

    private String toJson(Object v, String nodeId, String name) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            log.warn("节点 {} 变量 {} JSON 序列化失败，回退 toString: {}", nodeId, name, e.toString());
            return String.valueOf(v);
        }
    }

    private String truncateValue(String text, String nodeId, String name) {
        int max = props.maxValueLength();
        if (text.length() <= max) {
            return text;
        }
        log.warn("节点 {} 变量 {} 超单值上限 {}，截断至该长度（state 原文不变）",
                nodeId, name, max);
        return text.substring(0, max);
    }

    private String truncateTotal(String text, String nodeId) {
        int max = props.maxTotalLength();
        if (text.length() <= max) {
            return text;
        }
        log.warn("节点 {} 渲染后 prompt 超总长上限 {}，截断（state 原文不变）", nodeId, max);
        return text.substring(0, max);
    }
}
