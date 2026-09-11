package io.acelance.graph.dsl.service;

import io.acelance.graph.dsl.agent.GenericAgentNodeFactory;
import io.acelance.graph.dsl.agent.GraphBoundAgentNode;
import io.acelance.graph.dsl.audit.GraphAuditActions;
import io.acelance.graph.dsl.audit.GraphAuditEvent;
import io.acelance.graph.dsl.audit.GraphAuditLogger;
import io.acelance.graph.dsl.definition.GenericAgentDefinition;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.persistence.GenericAgentDefinitionRepository;
import io.acelance.graph.dsl.registry.GraphNodeRegistry;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.acelance.graph.dsl.resource.ResourceKeyValidationGate;
import io.acelance.graph.dsl.resource.ResourceKeyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通用 agent 节点定义应用服务：校验、试跑、CRUD 与启动重载。
 *
 * <p>与 {@link ScriptNodeService} 完全对称的「先定义 → 入库 → 复用」范式：
 * 定义由 {@link GenericAgentDefinitionRepository} 持久化，注册由
 * {@link GraphNodeRegistry} 承担，执行装配由 {@link GenericAgentNode} 承担。</p>
 *
 * <p><b>api-key 处理</b>：落库与注册的实例统一使用掩码 spec（仅留后 4 位），
 * 真实 key 在运行时由 {@code SecretResolver} 按 {@code graphId/nodeId} 还原，
 * 与图内联通道（{@code AgentSecretMasking}）的语义保持一致。</p>
 */
public class GenericAgentNodeService {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentNodeService.class);

    /** 试跑时使用的占位图 ID（不落库、不影响真实图的 secret 命名空间） */
    private static final String DRAFT_GRAPH_ID = "__agent_draft__";

    private final GenericAgentDefinitionRepository repository;
    private final GraphNodeRegistry nodeRegistry;
    private final GenericAgentNodeFactory agentNodeFactory;
    private final GraphAuditLogger auditLogger;
    private final ResourceKeyValidator resourceKeyValidator;

    public GenericAgentNodeService(GenericAgentDefinitionRepository repository,
                                   GraphNodeRegistry nodeRegistry,
                                   GenericAgentNodeFactory agentNodeFactory) {
        this(repository, nodeRegistry, agentNodeFactory, null, null);
    }

    public GenericAgentNodeService(GenericAgentDefinitionRepository repository,
                                   GraphNodeRegistry nodeRegistry,
                                   GenericAgentNodeFactory agentNodeFactory,
                                   GraphAuditLogger auditLogger) {
        this(repository, nodeRegistry, agentNodeFactory, auditLogger, null);
    }

    public GenericAgentNodeService(GenericAgentDefinitionRepository repository,
                                   GraphNodeRegistry nodeRegistry,
                                   GenericAgentNodeFactory agentNodeFactory,
                                   GraphAuditLogger auditLogger,
                                   ResourceKeyValidator resourceKeyValidator) {
        this.repository = repository;
        this.nodeRegistry = nodeRegistry;
        this.agentNodeFactory = Objects.requireNonNull(agentNodeFactory,
                "GenericAgentNodeFactory 不能为空（请引入 ace-graph-dsl-ai）");
        this.auditLogger = auditLogger;
        this.resourceKeyValidator = resourceKeyValidator;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动时从持久化层重载所有已启用的 agent 节点定义到注册中心 */
    public void reloadAllEnabled() {
        List<GenericAgentDefinition> all = repository.findAllEnabled();
        int ok = 0;
        for (GenericAgentDefinition def : all) {
            try {
                nodeRegistry.registerDynamic(toNode(def));
                ok++;
            } catch (Exception e) {
                log.error("通用 agent 节点加载失败, nodeId={}", def.nodeId(), e);
            }
        }
        log.info("通用 agent 节点重载完成, 成功 {}/{}", ok, all.size());
    }

    // ------------------------------------------------------------------ CRUD

    /** 创建 agent 节点定义 */
    public GenericAgentDefinition create(GenericAgentDefinition input) {
        requireAgentId(input.nodeId());
        if (repository.findById(input.nodeId()).isPresent()) {
            throw new IllegalArgumentException("通用 agent 节点已存在: " + input.nodeId());
        }
        if (nodeRegistry.contains(input.nodeId())) {
            throw new IllegalArgumentException("节点 ID 已被占用: " + input.nodeId());
        }
        validateSpec(input.spec());
        Instant now = Instant.now();
        GenericAgentDefinition saved = persistAndRegister(
                normalize(input, input.createdBy(), now, now));
        audit(GraphAuditActions.AGENT_NODE_CREATE, saved, "通用 agent 节点创建");
        return saved;
    }

    /** 更新 agent 节点定义 */
    public GenericAgentDefinition update(String nodeId, GenericAgentDefinition input) {
        requireAgentId(nodeId);
        GenericAgentDefinition existing = repository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("通用 agent 节点不存在: " + nodeId));
        GenericAgentSpec merged = mergeSpec(existing.spec(), input.spec());
        validateSpec(merged);
        GenericAgentDefinition saved = persistAndRegister(normalize(
                input.withNodeId(nodeId).withSpec(merged),
                existing.createdBy(), existing.createdAt(), Instant.now()));
        audit(GraphAuditActions.AGENT_NODE_UPDATE, saved, "通用 agent 节点更新");
        return saved;
    }

    /** 删除 agent 节点定义 */
    public void delete(String nodeId) {
        requireAgentId(nodeId);
        repository.delete(nodeId);
        nodeRegistry.unregisterDynamic(nodeId);
        log.info("通用 agent 节点已删除, nodeId={}", nodeId);
        if (auditLogger != null) {
            audit(GraphAuditActions.AGENT_NODE_DELETE,
                    GenericAgentDefinition.fromSpec(nodeId, nodeId, null), "通用 agent 节点删除");
        }
    }

    /** 列出所有 agent 节点定义 */
    public List<GenericAgentDefinition> listDefinitions() {
        return repository.findAll();
    }

    /** 获取单个 agent 节点定义 */
    public GenericAgentDefinition getDefinition(String nodeId) {
        return repository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("通用 agent 节点不存在: " + nodeId));
    }

    // ------------------------------------------------------------------ 校验 / 试跑

    /**
     * 校验 agent 元数据。
     *
     * <p>规则：modelId 必填；prompt 或 promptKeys 至少一项；outputKey 非空。</p>
     */
    public void validateSpec(GenericAgentSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            throw new IllegalArgumentException("agent 元数据不能为空");
        }
        if (isBlank(spec.modelId())) {
            errors.add("模型标识 modelId 不能为空");
        }
        if (isBlank(spec.prompt()) && (spec.promptKeys() == null || spec.promptKeys().isEmpty())) {
            errors.add("prompt 与 promptKeys 至少填写一项");
        }
        if (isBlank(spec.effectiveOutputKey())) {
            errors.add("输出 key 不能为空");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("agent 元数据校验失败：" + String.join("；", errors));
        }
        // P3.6：可选 ResourceKeyValidator
        ResourceBinding binding = ResourceBindings.fromSpec(spec);
        ResourceKeyValidationGate.assertValid(
                resourceKeyValidator, null, null, null, binding);
    }

    /** 试跑草稿元数据（未持久化） */
    public Map<String, Object> testRunDraft(GenericAgentSpec spec, Map<String, Object> mockState) {
        validateSpec(spec);
        GraphBoundAgentNode node = agentNodeFactory.create(
                GenericAgentDefinition.AGENT_ID_PREFIX + "__draft__", DRAFT_GRAPH_ID, spec);
        log.info("agent 试跑草稿: outputKeys 将写入 spec.outputKey");
        return node.execute(pickVariables(spec, mockState));
    }

    /** 基于已存定义试跑 */
    public Map<String, Object> testRun(String nodeId, Map<String, Object> mockState) {
        GenericAgentDefinition def = getDefinition(nodeId);
        GraphBoundAgentNode node = toNode(def).withGraphId(DRAFT_GRAPH_ID);
        return node.execute(pickVariables(def.spec(), mockState));
    }

    // ------------------------------------------------------------------ 内部

    private GenericAgentDefinition persistAndRegister(GenericAgentDefinition def) {
        GenericAgentDefinition saved = repository.save(def);
        nodeRegistry.registerDynamic(toNode(saved));
        log.info("通用 agent 节点已保存并注册, nodeId={}, version={}", saved.nodeId(), saved.version());
        return saved;
    }

    /** 定义 → 可注册节点（graphId 留空，编译期由 {@code withGraphId} 绑定当前图） */
    private GraphBoundAgentNode toNode(GenericAgentDefinition def) {
        return agentNodeFactory.create(
                def.nodeId(), null, def.spec(),
                def.displayName(), def.description(), def.version(), def.permissionTags());
    }

    /** 归一化：补时间戳、强制启用位与掩码 */
    private GenericAgentDefinition normalize(GenericAgentDefinition input,
                                             String createdBy,
                                             Instant createdAt,
                                             Instant updatedAt) {
        return new GenericAgentDefinition(
                input.nodeId(),
                input.effectiveDisplayName(),
                input.description(),
                input.version(),
                input.spec().masked(),
                input.permissionTags(),
                createdBy,
                createdAt,
                updatedAt,
                true);
    }

    /**
     * 合并更新的 spec。
     *
     * <p>前端回显的是掩码 key（{@code ****xxxx}），用户未改动时直接提交会把掩码值当真值存回。
     * 这里的约定：新提交的 key 若仍是掩码态，保留原有已存值，避免"改一次就丢一次 key"。</p>
     */
    private static GenericAgentSpec mergeSpec(GenericAgentSpec existing, GenericAgentSpec incoming) {
        if (incoming == null) {
            return existing;
        }
        if (existing == null) {
            return incoming;
        }
        boolean incomingKeyIsPlaceholder = incoming.apiKeyMasked()
                || isBlank(incoming.modelApiKey())
                || (incoming.modelApiKey() != null && incoming.modelApiKey().startsWith("****"));
        if (!incomingKeyIsPlaceholder) {
            return incoming;
        }
        return new GenericAgentSpec(
                incoming.modelBaseUrl(), existing.modelApiKey(), existing.apiKeyMasked(), incoming.modelId(),
                incoming.prompt(), incoming.inputKeys(), incoming.outputKey(),
                incoming.streamResponseKind(), incoming.mediaInputKey(),
                incoming.enablePrompt(), incoming.promptKeys(),
                incoming.enableModel(), incoming.modelConfigKey(),
                incoming.enableLocalTools(), incoming.localToolKeys(),
                incoming.enableMcp(), incoming.mcpKeys(), incoming.mcpToolWhitelist(),
                incoming.enableSkill(), incoming.skillKeys());
    }

    /** 按 inputKeys 白名单从 mock state 提取变量（与图内运行时的取值口径一致） */
    private static Map<String, Object> pickVariables(GenericAgentSpec spec, Map<String, Object> mockState) {
        Map<String, Object> source = mockState != null ? mockState : Map.of();
        Map<String, Object> variables = new LinkedHashMap<>();
        for (String key : spec.inputKeySet()) {
            variables.put(key, source.get(key));
        }
        return variables;
    }

    private void audit(String action, GenericAgentDefinition def, String detail) {
        if (auditLogger == null) {
            return;
        }
        try {
            auditLogger.record(GraphAuditEvent.agentNode(
                    action, def.nodeId(), def.version(), def.createdBy(), true, detail));
        } catch (Exception e) {
            log.warn("审计记录失败, action={}, nodeId={}", action, def.nodeId(), e);
        }
    }

    private static void requireAgentId(String nodeId) {
        if (nodeId == null || !nodeId.startsWith(GenericAgentDefinition.AGENT_ID_PREFIX)) {
            throw new IllegalArgumentException("通用 agent 节点 nodeId 必须以 '"
                    + GenericAgentDefinition.AGENT_ID_PREFIX + "' 开头: " + nodeId);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
