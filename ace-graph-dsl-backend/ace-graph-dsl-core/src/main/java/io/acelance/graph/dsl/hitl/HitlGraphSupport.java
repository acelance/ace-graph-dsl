package io.acelance.graph.dsl.hitl;

import io.acelance.graph.dsl.definition.CompileConfigDto;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.HitlNodeSpec;
import io.acelance.graph.dsl.definition.NodeRef;
import io.acelance.graph.dsl.registry.GraphNodeDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HITL 结构节点辅助：识别、注入键、interruptBefore 并入、keyStrategies 补全。
 */
public final class HitlGraphSupport {

    private HitlGraphSupport() {
    }

    /** 是否 HITL 结构节点（类别或 hitlSpec）。 */
    public static boolean isHitlNode(NodeRef ref) {
        if (ref == null) {
            return false;
        }
        return GraphNodeDescriptor.CATEGORY_HITL.equals(ref.category()) || ref.hitlSpec() != null;
    }

    /** 规范化后的 injectKeys；非 HITL 返回空列表。 */
    public static List<String> injectKeysOf(NodeRef ref) {
        if (!isHitlNode(ref)) {
            return List.of();
        }
        HitlNodeSpec spec = ref.hitlSpec();
        return spec == null ? List.of() : spec.injectKeys();
    }

    /**
     * 将图上全部 HITL 节点 id 并入 {@code interruptBefore}（去重保序，不删除已有项）。
     *
     * @return 新的 CompileConfigDto；无 HITL 或已齐全时尽量复用原对象字段
     */
    public static CompileConfigDto mergeInterruptBefore(CompileConfigDto compile, List<NodeRef> nodes) {
        List<String> hitlIds = collectHitlNodeIds(nodes);
        if (hitlIds.isEmpty()) {
            return compile == null
                    ? CompileConfigDto.defaultConfig()
                    : compile;
        }
        CompileConfigDto base = compile != null ? compile : CompileConfigDto.defaultConfig();
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (base.interruptBefore() != null) {
            for (String id : base.interruptBefore()) {
                if (id != null && !id.isBlank()) {
                    merged.add(id.trim());
                }
            }
        }
        merged.addAll(hitlIds);
        return new CompileConfigDto(List.copyOf(merged), base.saver(), base.interruptContent());
    }

    /** 收集 HITL 节点 id（保序）。 */
    public static List<String> collectHitlNodeIds(List<NodeRef> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (NodeRef ref : nodes) {
            if (isHitlNode(ref) && ref.nodeId() != null && !ref.nodeId().isBlank()) {
                ids.add(ref.nodeId().trim());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 为 injectKeys 补 {@code REPLACE}（已有策略不覆盖）。
     *
     * @return 新 map；无变更时仍返回拷贝或原 map 的不可变视图策略由调用方决定
     */
    public static Map<String, String> ensureInjectKeyStrategies(Map<String, String> keyStrategies,
                                                               List<NodeRef> nodes) {
        Map<String, String> out = keyStrategies == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(keyStrategies);
        if (nodes == null) {
            return out;
        }
        for (NodeRef ref : nodes) {
            for (String key : injectKeysOf(ref)) {
                out.putIfAbsent(key, "REPLACE");
            }
        }
        return out;
    }

    /**
     * 发布校验：HITL 须有 injectKeys，且 nodeId 须在 interruptBefore 中。
     *
     * @return 错误文案列表（可空）
     */
    public static List<String> validateHitlNodes(GraphDefinition def) {
        List<String> errors = new ArrayList<>();
        if (def == null || def.nodes() == null) {
            return errors;
        }
        Set<String> interrupt = new LinkedHashSet<>();
        if (def.compile() != null && def.compile().interruptBefore() != null) {
            for (String id : def.compile().interruptBefore()) {
                if (id != null && !id.isBlank()) {
                    interrupt.add(id.trim());
                }
            }
        }
        for (NodeRef ref : def.nodes()) {
            if (!isHitlNode(ref)) {
                continue;
            }
            String nodeId = ref.nodeId() == null ? "" : ref.nodeId().trim();
            List<String> keys = injectKeysOf(ref);
            if (keys.isEmpty()) {
                errors.add("HITL 节点未配置 injectKeys: " + nodeId);
            }
            if (!nodeId.isEmpty() && !interrupt.contains(nodeId)) {
                errors.add("HITL 节点未纳入 interruptBefore: " + nodeId
                        + "（保存/发布时应自动并入；请勿手动移除）");
            }
        }
        return errors;
    }

    /**
     * 规范化图定义：并入 interruptBefore + 补 keyStrategies（发布前调用）。
     */
    public static GraphDefinition normalize(GraphDefinition def) {
        if (def == null) {
            return null;
        }
        CompileConfigDto compile = mergeInterruptBefore(def.compile(), def.nodes());
        Map<String, String> keys = ensureInjectKeyStrategies(def.keyStrategies(), def.nodes());
        return new GraphDefinition(
                def.graphId(), def.displayName(), def.version(), def.description(),
                keys, def.nodes(), def.edges(), compile, def.bootstrap());
    }
}
