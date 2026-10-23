package io.acelance.graph.dsl.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * G-MCP：将 Spring AI {@link ToolCallback} 适配为 AS 2.0.4 {@link AgentTool}。
 *
 * <p>入参 Map → JSON 字符串交给 {@link ToolCallback#call(String)}；结果包装为 {@link ToolResultBlock}。</p>
 */
final class AceToolCallbackAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AceToolCallbackAgentTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolCallback callback;
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    AceToolCallbackAgentTool(ToolCallback callback) {
        this.callback = Objects.requireNonNull(callback, "callback");
        ToolDefinition def = callback.getToolDefinition();
        this.name = def != null && StringUtils.hasText(def.name()) ? def.name() : "unnamed_tool";
        this.description = def != null && def.description() != null ? def.description() : "";
        this.parameters = parseParametersSchema(def != null ? def.inputSchema() : null, this.name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> invoke(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("AGENTSCOPE 桥接工具调用失败, tool={}, err={}", name, ex.toString(), ex);
                    String id = toolCallId(param);
                    return Mono.just(ToolResultBlock.of(id, name,
                            TextBlock.builder().text("ERROR: " + ex.getMessage()).build()));
                });
    }

    private ToolResultBlock invoke(ToolCallParam param) throws Exception {
        long start = System.nanoTime();
        Map<String, Object> input = param != null && param.getInput() != null
                ? param.getInput()
                : Map.of();
        String json = MAPPER.writeValueAsString(input);
        log.info("AGENTSCOPE 桥接工具开始, tool={}, inputLen={}", name, json.length());
        String result = callback.call(json);
        if (result == null) {
            result = "";
        }
        long costMs = (System.nanoTime() - start) / 1_000_000L;
        log.info("AGENTSCOPE 桥接工具成功, tool={}, costMs={}, resultLen={}", name, costMs, result.length());
        String id = toolCallId(param);
        return ToolResultBlock.of(id, name, TextBlock.builder().text(result).build());
    }

    private static String toolCallId(ToolCallParam param) {
        if (param == null) {
            return "";
        }
        ToolUseBlock use = param.getToolUseBlock();
        return use != null && use.getId() != null ? use.getId() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseParametersSchema(String inputSchema, String toolName) {
        if (!StringUtils.hasText(inputSchema)) {
            return defaultObjectSchema();
        }
        try {
            Map<String, Object> map = MAPPER.readValue(inputSchema, MAP_TYPE);
            if (map == null || map.isEmpty()) {
                return defaultObjectSchema();
            }
            return map;
        } catch (Exception ex) {
            log.warn("AGENTSCOPE 工具 schema 解析失败, tool={}, 使用默认 object schema, err={}",
                    toolName, ex.toString());
            return defaultObjectSchema();
        }
    }

    private static Map<String, Object> defaultObjectSchema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new LinkedHashMap<>());
        return m;
    }
}
