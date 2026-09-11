package io.acelance.graph.dsl.ai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 带去重元数据的工具回调包装（§5.1.1 / D1）。
 *
 * <p>{@link #toModelCallback(boolean)} 返回给 ChatClient 的回调，其
 * {@link ToolDefinition#name()} 为 uniqueName；description 在冲突时前置来源标识。</p>
 *
 * @param uniqueName   模型可见唯一名
 * @param originalName 原始工具名
 * @param serverKey    MCP server key；非 MCP 可空
 * @param source       来源
 * @param description  原始描述
 * @param delegate     实际执行回调
 */
public record NamedToolCallback(
        String uniqueName,
        String originalName,
        String serverKey,
        ToolSource source,
        String description,
        ToolCallback delegate
) {
    public NamedToolCallback {
        Objects.requireNonNull(uniqueName, "uniqueName");
        Objects.requireNonNull(originalName, "originalName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(delegate, "delegate");
        ToolNames.assertLegal(uniqueName);
    }

    /**
     * @param conflicted 同 originalName 是否存在冲突组；true 时 description 前加来源
     */
    public ToolCallback toModelCallback(boolean conflicted) {
        String desc = description == null ? "" : description;
        if (conflicted) {
            String srcLabel = serverKey != null && !serverKey.isBlank()
                    ? serverKey
                    : source.name();
            desc = "[来源: " + srcLabel + "] " + desc;
        }
        ToolDefinition def = ToolDefinition.builder()
                .name(uniqueName)
                .description(desc)
                .inputSchema(delegate.getToolDefinition().inputSchema())
                .build();
        ToolCallback inner = delegate;
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return inner.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return inner.call(toolInput);
            }
        };
    }
}
