package io.acelance.graph.dsl.service;

import io.acelance.graph.dsl.audit.GraphAuditActions;
import io.acelance.graph.dsl.audit.GraphAuditEvent;
import io.acelance.graph.dsl.audit.GraphAuditLogger;
import io.acelance.graph.dsl.definition.DynamicNodeDefinition;
import io.acelance.graph.dsl.persistence.DynamicNodeDefinitionRepository;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.registry.NodeOrigin;
import io.acelance.graph.dsl.registry.RegisteredGraphNode;
import io.acelance.graph.dsl.script.ScriptEngine;
import io.acelance.graph.dsl.script.ScriptEngineRegistry;
import io.acelance.graph.dsl.script.ScriptExecutionContext;
import io.acelance.graph.dsl.script.ScriptNodeFactory;
import io.acelance.graph.dsl.script.ScriptOutputNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本（动态）节点应用服务：校验、试跑、CRUD 与启动重载。
 *
 * <p>持久化由 {@link DynamicNodeDefinitionRepository} 承担，注册由
 * {@link GraphNodeRegistry} 承担，脚本编译/执行由 {@link ScriptEngineRegistry} 承担。</p>
 */
public class ScriptNodeService {

    private static final Logger log = LoggerFactory.getLogger(ScriptNodeService.class);

    private final DynamicNodeDefinitionRepository repository;
    private final GraphNodeRegistry nodeRegistry;
    private final ScriptNodeFactory scriptNodeFactory;
    private final ScriptEngineRegistry engineRegistry;
    private final int maxScriptSizeBytes;
    private final String defaultEngine;
    private final GraphAuditLogger auditLogger;

    public ScriptNodeService(DynamicNodeDefinitionRepository repository,
                             GraphNodeRegistry nodeRegistry,
                             ScriptNodeFactory scriptNodeFactory,
                             ScriptEngineRegistry engineRegistry,
                             int maxScriptSizeBytes) {
        this(repository, nodeRegistry, scriptNodeFactory, engineRegistry, maxScriptSizeBytes, "aviator", null);
    }

    public ScriptNodeService(DynamicNodeDefinitionRepository repository,
                             GraphNodeRegistry nodeRegistry,
                             ScriptNodeFactory scriptNodeFactory,
                             ScriptEngineRegistry engineRegistry,
                             int maxScriptSizeBytes,
                             GraphAuditLogger auditLogger) {
        this(repository, nodeRegistry, scriptNodeFactory, engineRegistry, maxScriptSizeBytes, "aviator", auditLogger);
    }

    public ScriptNodeService(DynamicNodeDefinitionRepository repository,
                             GraphNodeRegistry nodeRegistry,
                             ScriptNodeFactory scriptNodeFactory,
                             ScriptEngineRegistry engineRegistry,
                             int maxScriptSizeBytes,
                             String defaultEngine,
                             GraphAuditLogger auditLogger) {
        this.repository = repository;
        this.nodeRegistry = nodeRegistry;
        this.scriptNodeFactory = scriptNodeFactory;
        this.engineRegistry = engineRegistry;
        this.maxScriptSizeBytes = maxScriptSizeBytes;
        this.defaultEngine = defaultEngine;
        this.auditLogger = auditLogger;
    }

    /** 启动时从持久化层重载所有已启用脚本节点到注册中心 */
    public void reloadAllEnabled() {
        List<DynamicNodeDefinition> all = repository.findAllEnabled();
        int ok = 0;
        for (DynamicNodeDefinition def : all) {
            try {
                RegisteredGraphNode node = scriptNodeFactory.create(def);
                nodeRegistry.registerDynamic(node);
                ok++;
            } catch (Exception e) {
                log.error("脚本节点加载失败, nodeId={}", def.nodeId(), e);
            }
        }
        log.info("脚本节点重载完成, 成功 {}/{}", ok, all.size());
    }

    /** 创建脚本节点 */
    public DynamicNodeDefinition create(DynamicNodeDefinition input) {
        requireScriptId(input.nodeId());
        if (repository.findById(input.nodeId()).isPresent()) {
            throw new IllegalArgumentException("脚本节点已存在: " + input.nodeId());
        }
        validateScript(input.engine(), input.scriptBody());
        Instant now = Instant.now();
        DynamicNodeDefinition def = normalize(input, input.createdBy(), now, now);
        DynamicNodeDefinition saved = persistAndRegister(def);
        audit(GraphAuditEvent.scriptNode(GraphAuditActions.SCRIPT_NODE_CREATE,
                saved.nodeId(), saved.version(), saved.createdBy(), true, "脚本节点创建"));
        return saved;
    }

    /** 更新脚本节点 */
    public DynamicNodeDefinition update(String nodeId, DynamicNodeDefinition input) {
        requireScriptId(nodeId);
        DynamicNodeDefinition existing = repository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("脚本节点不存在: " + nodeId));
        validateScript(input.engine(), input.scriptBody());
        DynamicNodeDefinition def = normalize(
                withNodeId(input, nodeId), existing.createdBy(), existing.createdAt(), Instant.now());
        DynamicNodeDefinition saved = persistAndRegister(def);
        audit(GraphAuditEvent.scriptNode(GraphAuditActions.SCRIPT_NODE_UPDATE,
                saved.nodeId(), saved.version(), saved.createdBy(), true, "脚本节点更新"));
        return saved;
    }

    /** 删除脚本节点 */
    public void delete(String nodeId) {
        requireScriptId(nodeId);
        repository.delete(nodeId);
        nodeRegistry.unregisterDynamic(nodeId);
        log.info("脚本节点已删除, nodeId={}", nodeId);
        audit(GraphAuditEvent.scriptNode(GraphAuditActions.SCRIPT_NODE_DELETE,
                nodeId, null, null, true, "脚本节点删除"));
    }

    /** 列出所有脚本节点定义 */
    public List<DynamicNodeDefinition> listDefinitions() {
        return repository.findAll();
    }

    /** 获取单个脚本节点定义 */
    public DynamicNodeDefinition getDefinition(String nodeId) {
        return repository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("脚本节点不存在: " + nodeId));
    }

    /** 校验脚本语法（含大小限制） */
    public void validateScript(String engine, String scriptBody) {
        if (scriptBody == null || scriptBody.isBlank()) {
            throw new IllegalArgumentException("脚本内容不能为空");
        }
        int size = scriptBody.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxScriptSizeBytes) {
            throw new IllegalArgumentException("脚本超过大小限制: " + size + " > " + maxScriptSizeBytes + " 字节");
        }
        ScriptEngine scriptEngine = engineRegistry.require(resolveEngine(engine));
        scriptEngine.validate(scriptBody);
    }

    /** 试跑草稿脚本（未持久化） */
    public Map<String, Object> testRunDraft(String engine,
                                            String scriptBody,
                                            Set<String> inputKeys,
                                            Set<String> outputKeys,
                                            Map<String, Object> mockState,
                                            Map<String, Object> config) {
        validateScript(engine, scriptBody);
        ScriptEngine scriptEngine = engineRegistry.require(resolveEngine(engine));
        Object compiled = scriptEngine.compile(scriptBody);
        Map<String, Object> state = extractState(mockState, inputKeys);
        ScriptExecutionContext ctx = new ScriptExecutionContext(state, config != null ? config : Map.of());
        Object result = scriptEngine.execute(compiled, ctx);
        return ScriptOutputNormalizer.normalize(result, outputKeys);
    }

    /** 基于已存定义试跑 */
    public Map<String, Object> testRun(String nodeId, Map<String, Object> mockState, Map<String, Object> config) {
        DynamicNodeDefinition def = getDefinition(nodeId);
        return testRunDraft(def.engine(), def.scriptBody(), def.inputKeys(), def.outputKeys(), mockState, config);
    }

    private void audit(GraphAuditEvent event) {
        if (auditLogger == null) {
            return;
        }
        try {
            auditLogger.record(event);
        } catch (Exception e) {
            log.warn("审计记录失败, action={}, nodeId={}", event.action(), event.resourceId(), e);
        }
    }

    private DynamicNodeDefinition persistAndRegister(DynamicNodeDefinition def) {
        DynamicNodeDefinition saved = repository.save(def);
        RegisteredGraphNode node = scriptNodeFactory.create(saved);
        nodeRegistry.registerDynamic(node);
        log.info("脚本节点已保存并注册, nodeId={}, version={}", saved.nodeId(), saved.version());
        return saved;
    }

    private DynamicNodeDefinition normalize(DynamicNodeDefinition input,
                                            String createdBy,
                                            Instant createdAt,
                                            Instant updatedAt) {
        String engine = resolveEngine(input.engine());
        String version = (input.version() == null || input.version().isBlank()) ? "1.0.0" : input.version();
        return new DynamicNodeDefinition(
                input.nodeId(),
                input.displayName(),
                input.category(),
                input.description(),
                input.inputKeys(),
                input.outputKeys(),
                input.supportsParallel(),
                version,
                engine,
                input.scriptBody(),
                sha256(input.scriptBody()),
                input.configurableProps(),
                NodeOrigin.SCRIPT,
                input.permissionTags(),
                createdBy,
                createdAt,
                updatedAt,
                true);
    }

    private static DynamicNodeDefinition withNodeId(DynamicNodeDefinition input, String nodeId) {
        return new DynamicNodeDefinition(
                nodeId, input.displayName(), input.category(), input.description(),
                input.inputKeys(), input.outputKeys(), input.supportsParallel(),
                input.version(), input.engine(), input.scriptBody(), input.scriptHash(),
                input.configurableProps(), input.origin(), input.permissionTags(),
                input.createdBy(), input.createdAt(), input.updatedAt(), input.enabled());
    }

    private String resolveEngine(String engine) {
        return (engine == null || engine.isBlank()) ? this.defaultEngine : engine;
    }

    private static void requireScriptId(String nodeId) {
        if (nodeId == null || !nodeId.startsWith(DynamicNodeDefinition.SCRIPT_ID_PREFIX)) {
            throw new IllegalArgumentException("脚本节点 nodeId 必须以 '" + DynamicNodeDefinition.SCRIPT_ID_PREFIX + "' 开头: " + nodeId);
        }
    }

    private static Map<String, Object> extractState(Map<String, Object> mockState, Set<String> inputKeys) {
        Map<String, Object> source = mockState != null ? mockState : Map.of();
        if (inputKeys == null || inputKeys.isEmpty()) {
            return new HashMap<>(source);
        }
        Map<String, Object> state = new HashMap<>();
        for (String key : new HashSet<>(inputKeys)) {
            state.put(key, source.get(key));
        }
        return state;
    }

    private static String sha256(String content) {
        if (content == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
