package io.acelance.graph.dsl.resource;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 节点资源勾选视图（运行期只读）。
 *
 * <p>由 UI / {@code GenericAgentSpec} 落库后经 {@link ResourceBindings#fromSpec} 映射；
 * Template 按 enable 开关决定是否调用对应 Resolver。</p>
 */
public record ResourceBinding(
        boolean enablePrompt,
        List<String> promptKeys,
        boolean enableModel,
        String modelConfigKey,
        boolean enableLocalTools,
        List<String> localToolKeys,
        boolean enableMcp,
        List<String> mcpKeys,
        Map<String, List<String>> mcpToolWhitelist,
        boolean enableSkill,
        List<String> skillKeys
) {
    public ResourceBinding {
        promptKeys = promptKeys == null ? List.of() : List.copyOf(promptKeys);
        localToolKeys = localToolKeys == null ? List.of() : List.copyOf(localToolKeys);
        mcpKeys = mcpKeys == null ? List.of() : List.copyOf(mcpKeys);
        skillKeys = skillKeys == null ? List.of() : List.copyOf(skillKeys);
        mcpToolWhitelist = mcpToolWhitelist == null ? Map.of() : Map.copyOf(mcpToolWhitelist);
    }

    /** 全部关闭、keys 为空的空绑定 */
    public static ResourceBinding disabledAll() {
        return new ResourceBinding(
                false, List.of(),
                false, null,
                false, List.of(),
                false, List.of(), Map.of(),
                false, List.of());
    }
}
