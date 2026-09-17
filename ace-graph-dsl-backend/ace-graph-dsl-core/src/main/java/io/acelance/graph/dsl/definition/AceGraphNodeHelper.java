package io.acelance.graph.dsl.definition;

import io.acelance.graph.dsl.bizparam.DefaultStringBizParamInterpreter;
import io.acelance.graph.dsl.bizparam.NodeBizParamInterpreter;
import io.acelance.graph.dsl.bizparam.NodeBizParamInterpreterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 graphId + nodeId 读取节点定义与业务附加参数。
 *
 * <p>解释结果按 {@code graphId|nodeId|interpreterId|raw} 缓存，避免每个 token 重复解析。</p>
 */
public final class AceGraphNodeHelper {

    private static final Logger log = LoggerFactory.getLogger(AceGraphNodeHelper.class);
    private static final Object NULL_SENTINEL = new Object();

    private final GraphDefinitionSource definitionSource;
    private final NodeBizParamInterpreterRegistry registry;
    private final ConcurrentHashMap<String, Object> interpretCache = new ConcurrentHashMap<>();

    public AceGraphNodeHelper(GraphDefinitionSource definitionSource,
                              NodeBizParamInterpreterRegistry registry) {
        this.definitionSource = Objects.requireNonNull(definitionSource, "definitionSource");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<NodeRef> findNode(String graphId, String nodeId) {
        GraphDefinition def = definitionSource.get(graphId);
        if (def == null || def.nodes() == null || nodeId == null) {
            return Optional.empty();
        }
        return def.nodes().stream()
                .filter(n -> nodeId.equals(n.nodeId()))
                .findFirst();
    }

    public Optional<GenericAgentSpec> findAgentSpec(String graphId, String nodeId) {
        return findNode(graphId, nodeId).map(NodeRef::agentSpec).filter(Objects::nonNull);
    }

    public boolean isBizParamsEnabled(String graphId, String nodeId) {
        return findAgentSpec(graphId, nodeId).map(GenericAgentSpec::enableBizParams).orElse(false);
    }

    /** 原始附加参数文本；未开启或无 Spec 时返回 null */
    public String getBizParamRaw(String graphId, String nodeId) {
        return findAgentSpec(graphId, nodeId)
                .filter(GenericAgentSpec::enableBizParams)
                .map(GenericAgentSpec::bizParamRaw)
                .orElse(null);
    }

    /**
     * 按节点配置的解释器解析附加参数。
     *
     * @return 未开启时 null；开启时返回解释结果（默认解释器下多为 String）
     */
    public Object interpretBizParam(String graphId, String nodeId) {
        Optional<GenericAgentSpec> opt = findAgentSpec(graphId, nodeId);
        if (opt.isEmpty() || !opt.get().enableBizParams()) {
            return null;
        }
        GenericAgentSpec spec = opt.get();
        final String interpreterId = (spec.bizParamInterpreterId() == null
                || spec.bizParamInterpreterId().isBlank())
                ? DefaultStringBizParamInterpreter.ID
                : spec.bizParamInterpreterId().trim();
        final String raw = spec.bizParamRaw();
        String cacheKey = graphId + '\0' + nodeId + '\0' + interpreterId + '\0'
                + (raw == null ? "" : raw);
        Object cached = interpretCache.computeIfAbsent(cacheKey,
                k -> doInterpret(graphId, nodeId, interpreterId, raw));
        return cached == NULL_SENTINEL ? null : cached;
    }

    /** 类型化读取；类型不符时返回 empty */
    public <T> Optional<T> interpretBizParam(String graphId, String nodeId, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object value = interpretBizParam(graphId, nodeId);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        log.debug("bizParam 类型不匹配: graphId={}, nodeId={}, expect={}, actual={}",
                graphId, nodeId, type.getName(), value.getClass().getName());
        return Optional.empty();
    }

    public void clearInterpretCache() {
        interpretCache.clear();
    }

    public NodeBizParamInterpreterRegistry registry() {
        return registry;
    }

    public Map<String, String> listInterpreterIds(String graphId) {
        GraphDefinition def = definitionSource.get(graphId);
        if (def == null || def.nodes() == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (NodeRef n : def.nodes()) {
            if (n.agentSpec() != null && n.agentSpec().enableBizParams()) {
                String id = n.agentSpec().bizParamInterpreterId();
                out.put(n.nodeId(), id == null || id.isBlank()
                        ? DefaultStringBizParamInterpreter.ID : id);
            }
        }
        return Map.copyOf(out);
    }

    private Object doInterpret(String graphId, String nodeId, String interpreterId, String raw) {
        NodeBizParamInterpreter interpreter = registry.resolveOrDefault(interpreterId);
        try {
            Object value = interpreter.interpret(raw);
            log.debug("bizParam 已解释: graphId={}, nodeId={}, interpreter={}, type={}",
                    graphId, nodeId, interpreter.id(),
                    value == null ? "null" : value.getClass().getSimpleName());
            return value == null ? NULL_SENTINEL : value;
        } catch (RuntimeException ex) {
            log.warn("bizParam 解释失败，回落 String: graphId={}, nodeId={}, interpreter={}, err={}",
                    graphId, nodeId, interpreterId, ex.toString());
            return raw == null ? "" : raw;
        }
    }
}
