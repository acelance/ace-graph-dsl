package io.acelance.graph.dsl.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.ai.tool.ToolSource;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillPathSafety;
import io.acelance.graph.dsl.skill.SkillResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 框架内置 Skill 工具（§6.4.1）：{@code ace__skill__load_skill} / {@code ace__skill__read_skill_resource}。
 */
public final class SkillTools {

    private static final Logger log = LoggerFactory.getLogger(SkillTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String LOAD_SKILL = "ace__skill__load_skill";
    public static final String READ_RESOURCE = "ace__skill__read_skill_resource";

    private static final String LOAD_SCHEMA = """
            {"type":"object","properties":{"code":{"type":"string","description":"skill key from L1 catalog"}},"required":["code"]}
            """;
    private static final String READ_SCHEMA = """
            {"type":"object","properties":{"code":{"type":"string"},"path":{"type":"string","description":"relative resource path"}},"required":["code","path"]}
            """;

    private SkillTools() {
    }

    /**
     * @param ctx            请求上下文（闭包捕获白名单）
     * @param skillKeys      本节点白名单
     * @param contentLoader  L2
     * @param resourceLoader L3
     * @param activated      已激活 code 集合（跨调用去重，可共享）
     */
    public static List<NamedToolCallback> builtin(LlmRequestContext ctx,
                                                  List<String> skillKeys,
                                                  SkillContentLoader contentLoader,
                                                  SkillResourceLoader resourceLoader,
                                                  Set<String> activated) {
        List<String> whitelist = skillKeys == null ? List.of() : List.copyOf(skillKeys);
        Set<String> activatedSafe = activated != null ? activated : new LinkedHashSet<>();
        List<NamedToolCallback> out = new ArrayList<>(2);
        out.add(named(LOAD_SKILL, "load_skill",
                "Load full skill instructions by code (must be in node whitelist).",
                LOAD_SCHEMA,
                input -> loadSkill(ctx, whitelist, contentLoader, resourceLoader, activatedSafe, input)));
        out.add(named(READ_RESOURCE, "read_skill_resource",
                "Read a skill resource by relative path after load_skill.",
                READ_SCHEMA,
                input -> readResource(ctx, whitelist, resourceLoader, input)));
        log.info("节点 {} 注册内置 Skill 工具: {}, {}", ctx.nodeId(), LOAD_SKILL, READ_RESOURCE);
        return List.copyOf(out);
    }

    private static NamedToolCallback named(String unique, String original, String desc,
                                           String schema, java.util.function.Function<String, String> fn) {
        ToolDefinition def = ToolDefinition.builder()
                .name(unique)
                .description(desc)
                .inputSchema(schema)
                .build();
        ToolCallback cb = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return fn.apply(toolInput);
            }
        };
        return new NamedToolCallback(unique, original, null, ToolSource.BUILTIN, desc, cb);
    }

    private static String loadSkill(LlmRequestContext ctx,
                                    List<String> whitelist,
                                    SkillContentLoader contentLoader,
                                    SkillResourceLoader resourceLoader,
                                    Set<String> activated,
                                    String toolInput) {
        String code = readField(toolInput, "code");
        if (!SkillPathSafety.inWhitelist(code, whitelist)) {
            log.warn("节点 {} load_skill 越权: code={}, whitelist={}", ctx.nodeId(), code, whitelist);
            return "ERROR: skill code not in node whitelist: " + code;
        }
        if (!activated.add(code)) {
            log.info("节点 {} skill={} 已激活，跳过重复加载", ctx.nodeId(), code);
            return "Skill already activated: " + code;
        }
        String body = contentLoader == null
                ? null
                : contentLoader.loadBody(ctx, code).orElse(null);
        if (body == null || body.isBlank()) {
            log.warn("节点 {} load_skill 正文为空: code={}", ctx.nodeId(), code);
            return "ERROR: skill body not found: " + code;
        }
        List<String> index = resourceLoader == null
                ? List.of()
                : resourceLoader.listResourceIndex(ctx, code);
        log.info("节点 {} 强制/工具激活 skill={}，L2 chars={}, L3 index={}",
                ctx.nodeId(), code, body.length(), index.size());
        StringBuilder sb = new StringBuilder();
        sb.append("### Skill ").append(code).append("\n\n").append(body);
        if (!index.isEmpty()) {
            sb.append("\n\n#### Resource index\n");
            for (String p : index) {
                sb.append("- ").append(p).append('\n');
            }
            sb.append("Use ace__skill__read_skill_resource to read a path.\n");
        }
        return sb.toString();
    }

    private static String readResource(LlmRequestContext ctx,
                                       List<String> whitelist,
                                       SkillResourceLoader resourceLoader,
                                       String toolInput) {
        String code = readField(toolInput, "code");
        String path = readField(toolInput, "path");
        if (!SkillPathSafety.inWhitelist(code, whitelist)) {
            log.warn("节点 {} read_skill_resource 越权: code={}", ctx.nodeId(), code);
            return "ERROR: skill code not in node whitelist: " + code;
        }
        if (!SkillPathSafety.isSafeRelativePath(path)) {
            log.warn("节点 {} read_skill_resource 路径不安全: path={}", ctx.nodeId(), path);
            return "ERROR: unsafe resource path: " + path;
        }
        if (resourceLoader == null) {
            return "ERROR: no SkillResourceLoader configured";
        }
        String content = resourceLoader.loadResource(ctx, code, path).orElse(null);
        if (content == null) {
            log.warn("节点 {} L3 资源未找到: code={}, path={}", ctx.nodeId(), code, path);
            return "ERROR: resource not found: " + code + " / " + path;
        }
        log.info("节点 {} 读取 L3 资源: code={}, path={}, chars={}",
                ctx.nodeId(), code, path, content.length());
        return content;
    }

    private static String readField(String json, String field) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode v = node.get(field);
            return v == null || v.isNull() ? "" : v.asText("").trim();
        } catch (Exception e) {
            log.debug("解析 skill 工具入参失败，按空处理: {}", e.getMessage());
            return "";
        }
    }
}
