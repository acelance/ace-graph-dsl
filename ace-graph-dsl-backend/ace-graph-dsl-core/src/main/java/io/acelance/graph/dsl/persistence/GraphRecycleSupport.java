package io.acelance.graph.dsl.persistence;

import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.definition.NodeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回收箱公共逻辑：组装快照、扫描子图引用方。
 */
public final class GraphRecycleSupport {

    private static final Logger log = LoggerFactory.getLogger(GraphRecycleSupport.class);

    private GraphRecycleSupport() {
    }

    /**
     * 从正式库组装删除前快照。
     *
     * @param repository 仓储
     * @param graphId    目标图
     * @return 快照；正式库无该图时返回 {@code null}
     */
    public static GraphRecycleSnapshot buildSnapshot(GraphDefinitionRepository repository, String graphId) {
        List<GraphDefinition> definitions = repository.listVersions(graphId);
        if (definitions == null || definitions.isEmpty()) {
            log.warn("组装回收快照失败：正式库无定义, graphId={}", graphId);
            return null;
        }
        GraphDefinition enabled = repository.getEnabled(graphId);
        String enabledVersion = enabled != null ? enabled.version() : null;
        GraphDefinition latest = definitions.get(definitions.size() - 1);
        String displayName = latest.displayName() != null ? latest.displayName() : graphId;
        log.info("组装回收快照完成, graphId={}, versionCount={}, enabledVersion={}",
                graphId, definitions.size(), enabledVersion);
        return new GraphRecycleSnapshot(graphId, displayName, enabledVersion, definitions);
    }

    /**
     * 扫描其他正式图的全部定义版本，找出通过 {@code subgraphRef} 引用 {@code targetGraphId} 的图（按 graphId 去重）。
     *
     * @param repository    仓储
     * @param targetGraphId 被引用的图 ID
     * @return 引用方 graphId 列表
     */
    public static List<String> findSubgraphReferrers(GraphDefinitionRepository repository, String targetGraphId) {
        Set<String> referrers = new LinkedHashSet<>();
        if (targetGraphId == null || targetGraphId.isBlank()) {
            return List.of();
        }
        for (String graphId : repository.listGraphIds()) {
            if (targetGraphId.equals(graphId)) {
                continue;
            }
            if (refersTo(repository.listVersions(graphId), targetGraphId)) {
                referrers.add(graphId);
            }
        }
        log.info("子图引用预检完成, targetGraphId={}, referrerCount={}", targetGraphId, referrers.size());
        return new ArrayList<>(referrers);
    }

    /**
     * 校验恢复快照：若 {@code enabledVersion} 非空，则 definitions 中必须存在该 DSL 版本。
     *
     * @param snapshot 快照
     * @throws IllegalArgumentException 快照不合法
     */
    public static void validateSnapshotForRestore(GraphRecycleSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("回收快照不存在");
        }
        String enabledVersion = snapshot.enabledVersion();
        if (enabledVersion == null || enabledVersion.isBlank()) {
            return;
        }
        List<GraphDefinition> definitions = snapshot.definitions();
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("回收快照 definitions 为空，无法恢复启用版本: " + enabledVersion);
        }
        boolean found = definitions.stream().anyMatch(d -> enabledVersion.equals(d.version()));
        if (!found) {
            throw new IllegalArgumentException(
                    "回收快照 enabledVersion 在 definitions 中不存在: " + enabledVersion);
        }
    }

    private static boolean refersTo(List<GraphDefinition> versions, String targetGraphId) {
        if (versions == null) {
            return false;
        }
        for (GraphDefinition def : versions) {
            if (def.nodes() == null) {
                continue;
            }
            for (NodeRef node : def.nodes()) {
                if (node == null || node.subgraphRef() == null || node.subgraphRef().isBlank()) {
                    continue;
                }
                String refGraphId = NodeRef.graphIdOf(node.subgraphRef());
                if (targetGraphId.equals(refGraphId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
