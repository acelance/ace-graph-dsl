package io.acelance.graph.dsl.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;
import java.util.function.Function;

/**
 * 本地工具包装助手（P3.1）：把业务回调打成 {@link NamedToolCallback}。
 *
 * <p>约定：{@code toolKey} 写入 {@link NamedToolCallback#serverKey()}，便于节点侧 miss 诊断
 * 与 {@code localToolKeys} 对齐。</p>
 */
public final class LocalToolCallbacks {

    private static final Logger log = LoggerFactory.getLogger(LocalToolCallbacks.class);
    private static final String EMPTY_OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}";

    private LocalToolCallbacks() {
    }

    /**
     * @param toolKey     资源 key（与 UI localToolKeys 一致）
     * @param originalName 模型可见原始名（通常与工具函数名一致）
     * @param description  工具描述
     * @param handler      入参 JSON → 结果字符串
     */
    public static NamedToolCallback of(String toolKey,
                                       String originalName,
                                       String description,
                                       Function<String, String> handler) {
        Objects.requireNonNull(handler, "handler");
        String key = (toolKey == null || toolKey.isBlank()) ? "local" : toolKey.trim();
        String orig = (originalName == null || originalName.isBlank()) ? key : originalName.trim();
        String desc = description == null ? ("local tool:" + orig) : description;
        String unique = ToolNames.toModelName(ToolSource.LOCAL, key, orig);
        ToolDefinition def = ToolDefinition.builder()
                .name(orig)
                .description(desc)
                .inputSchema(EMPTY_OBJECT_SCHEMA)
                .build();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                log.info("本地工具被调用: toolKey={}, originalName={}, inputChars={}",
                        key, orig, toolInput == null ? 0 : toolInput.length());
                return handler.apply(toolInput == null ? "" : toolInput);
            }
        };
        return new NamedToolCallback(unique, orig, key, ToolSource.LOCAL, desc, delegate);
    }
}
