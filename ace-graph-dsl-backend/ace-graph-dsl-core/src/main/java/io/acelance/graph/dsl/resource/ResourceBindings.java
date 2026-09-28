package io.acelance.graph.dsl.resource;

import io.acelance.graph.dsl.definition.GenericAgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spec → {@link ResourceBinding} 纯映射（§7.1；P0.5 无旧字段过渡）。
 */
public final class ResourceBindings {

    private static final Logger log = LoggerFactory.getLogger(ResourceBindings.class);

    private ResourceBindings() {
    }

    public static ResourceBinding fromSpec(GenericAgentSpec spec) {
        Objects.requireNonNull(spec, "GenericAgentSpec 不能为空");
        List<String> promptKeys = spec.promptKeys();
        List<String> skillKeys = spec.skillKeys();
        List<String> mcpKeys = spec.mcpKeys();
        List<String> localToolKeys = spec.localToolKeys();
        Map<String, List<String>> mcpWhitelist = spec.mcpToolWhitelist();

        boolean enablePrompt = spec.enablePrompt() || !promptKeys.isEmpty() || nonBlank(spec.prompt());
        boolean enableModel = spec.enableModel() || nonBlank(spec.modelConfigKey());
        boolean enableMcp = spec.enableMcp() || !mcpKeys.isEmpty();
        boolean enableSkill = spec.enableSkill() || !skillKeys.isEmpty();
        boolean enableLocal = spec.enableLocalTools() || !localToolKeys.isEmpty();

        ResourceBinding b = new ResourceBinding(
                enablePrompt, promptKeys,
                enableModel, spec.modelConfigKey(),
                enableLocal, localToolKeys,
                enableMcp, mcpKeys, mcpWhitelist,
                enableSkill, skillKeys
        );
        log.info("ResourceBindings.fromSpec: prompt={}, model={}, mcp={}, skill={}, localTools={}",
                b.enablePrompt(), b.enableModel(), b.enableMcp(), b.enableSkill(), b.enableLocalTools());
        return b;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
