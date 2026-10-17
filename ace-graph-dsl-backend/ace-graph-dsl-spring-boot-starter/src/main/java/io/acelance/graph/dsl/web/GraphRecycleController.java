package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.autoconfigure.BuiltinGraphRegistry;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import io.acelance.graph.dsl.persistence.GraphRecycleEntry;
import io.acelance.graph.dsl.persistence.GraphRecycleSnapshot;
import io.acelance.graph.dsl.persistence.GraphRecycleSupport;
import io.acelance.graph.dsl.persistence.RecycleVersionGenerator;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissionResolver;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.store.GraphRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图回收箱 REST API：子图引用预检、删除进回收、列表/预览、恢复、彻底清除。
 */
@RestController
@RequestMapping("/definitions")
public class GraphRecycleController {

    private static final Logger log = LoggerFactory.getLogger(GraphRecycleController.class);

    private final GraphDefinitionRepository store;
    private final GraphRuntime runtime;
    private final GraphMenuPermissionResolver menuPermissions;
    private final BuiltinGraphRegistry builtinRegistry;

    public GraphRecycleController(GraphDefinitionRepository store,
                                  GraphRuntime runtime,
                                  GraphMenuPermissionResolver menuPermissions,
                                  BuiltinGraphRegistry builtinRegistry) {
        this.store = store;
        this.runtime = runtime;
        this.menuPermissions = menuPermissions;
        this.builtinRegistry = builtinRegistry;
    }

    /**
     * 子图引用预检：打开删除确认框时调用，只读、不阻断删除。
     */
    @GetMapping("/{graphId}/subgraph-referrers")
    public Map<String, List<String>> subgraphReferrers(@PathVariable String graphId) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_DELETE, "无权删除 Graph");
        List<String> graphIds = GraphRecycleSupport.findSubgraphReferrers(store, graphId);
        log.info("子图引用预检, graphId={}, referrers={}", graphId, graphIds);
        return Map.of("graphIds", graphIds);
    }

    /**
     * 删除正式图进回收箱。
     */
    @PostMapping("/{graphId}/recycle")
    public Map<String, String> recycle(@PathVariable String graphId,
                                       @RequestBody RecycleConfirmRequest body) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_DELETE, "无权删除 Graph");
        if (body == null || body.confirmGraphId() == null || body.confirmGraphId().isBlank()) {
            throw new IllegalArgumentException("confirmGraphId 不能为空");
        }
        if (!graphId.equals(body.confirmGraphId())) {
            throw new IllegalArgumentException("confirmGraphId 与路径 graphId 不一致");
        }
        if (builtinRegistry.contains(graphId)) {
            log.warn("拒绝删除内置图, graphId={}", graphId);
            throw new IllegalArgumentException("内置图不允许删除: " + graphId);
        }
        log.info("开始删除图进回收箱, graphId={}", graphId);
        String recycleVersion = store.moveToRecycle(graphId);
        // 事务已提交：再卸本进程缓存；失败不回滚，接口仍成功
        try {
            runtime.evict(graphId);
        } catch (Exception e) {
            log.error("删除后卸载运行时缓存失败（库已回收，接口仍成功）, graphId={}, recycleVersion={}",
                    graphId, recycleVersion, e);
        }
        log.info("删除图进回收箱成功, graphId={}, recycleVersion={}", graphId, recycleVersion);
        return Map.of("recycleVersion", recycleVersion);
    }

    /**
     * 回收箱列表。
     */
    @GetMapping("/recycle")
    public List<GraphRecycleEntry> listRecycle() {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VIEW, "无权查看 Graph");
        return store.listRecycle();
    }

    /**
     * 加载回收快照（供回收预览）。{@code recycleVersion} 含空格与冒号，放查询参数。
     */
    @GetMapping("/recycle/{graphId}")
    public GraphRecycleSnapshot getRecycle(@PathVariable String graphId,
                                           @RequestParam String recycleVersion) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VIEW, "无权查看 Graph");
        if (recycleVersion == null || recycleVersion.isBlank()) {
            throw new IllegalArgumentException("recycleVersion 不能为空");
        }
        GraphRecycleSnapshot snapshot = store.loadRecycle(graphId, recycleVersion);
        if (snapshot == null) {
            throw new IllegalArgumentException("回收行不存在: " + graphId + " / " + recycleVersion);
        }
        return snapshot;
    }

    /**
     * 从回收箱恢复到正式库。
     */
    @PostMapping("/recycle/{graphId}/restore")
    public Map<String, Object> restore(@PathVariable String graphId,
                                       @RequestBody RestoreRecycleRequest body) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_RESTORE, "无权从回收箱恢复");
        if (body == null || body.recycleVersion() == null || body.recycleVersion().isBlank()) {
            throw new IllegalArgumentException("recycleVersion 不能为空");
        }
        if (builtinRegistry.contains(graphId)) {
            log.warn("拒绝恢复为内置图 graphId（脏数据防护）, graphId={}", graphId);
            throw new IllegalArgumentException("内置图不允许从回收箱恢复: " + graphId);
        }
        // 事务前预检：行不存在 / 快照不合法
        GraphRecycleSnapshot preview = store.loadRecycle(graphId, body.recycleVersion());
        if (preview == null) {
            throw new IllegalArgumentException(
                    "回收行不存在: " + graphId + " / " + body.recycleVersion());
        }
        GraphRecycleSupport.validateSnapshotForRestore(preview);

        log.info("开始从回收箱恢复, graphId={}, recycleVersion={}", graphId, body.recycleVersion());
        GraphRecycleSnapshot restored = store.restoreFromRecycle(graphId, body.recycleVersion());
        // 事务已提交：按启用状态 refresh 或 evict；失败不回滚
        try {
            if (restored.enabledVersion() != null && !restored.enabledVersion().isBlank()) {
                runtime.refresh(graphId);
                log.info("恢复后已刷新运行时缓存, graphId={}, enabledVersion={}",
                        graphId, restored.enabledVersion());
            } else {
                runtime.evict(graphId);
                log.info("恢复为未发布，已卸载运行时缓存, graphId={}", graphId);
            }
        } catch (Exception e) {
            log.error("恢复后刷新/卸载运行时缓存失败（库已恢复，接口仍成功）, graphId={}, recycleVersion={}",
                    graphId, body.recycleVersion(), e);
        }
        return Map.of(
                "graphId", graphId,
                "enabledVersion", restored.enabledVersion() == null ? "" : restored.enabledVersion()
        );
    }

    /**
     * 彻底清除一条回收行（物理删除，不可恢复）。
     */
    @PostMapping("/recycle/{graphId}/purge")
    public Map<String, Object> purge(@PathVariable String graphId,
                                     @RequestBody PurgeRecycleRequest body) {
        MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_PURGE, "无权彻底清除回收图");
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (body.recycleVersion() == null || body.recycleVersion().isBlank()) {
            throw new IllegalArgumentException("recycleVersion 不能为空");
        }
        if (body.confirmGraphId() == null || body.confirmGraphId().isBlank()) {
            throw new IllegalArgumentException("confirmGraphId 不能为空");
        }
        if (body.confirmSuffix() == null || body.confirmSuffix().isBlank()) {
            throw new IllegalArgumentException("confirmSuffix 不能为空");
        }
        // 三者都校验，大小写敏感
        if (!graphId.equals(body.confirmGraphId())) {
            throw new IllegalArgumentException("confirmGraphId 与路径 graphId 不一致");
        }
        String expectedSuffix = RecycleVersionGenerator.suffixOf(body.recycleVersion());
        if (!body.confirmSuffix().equals(expectedSuffix)) {
            throw new IllegalArgumentException("confirmSuffix 与 recycleVersion 末 4 位不一致");
        }
        GraphRecycleSnapshot existing = store.loadRecycle(graphId, body.recycleVersion());
        if (existing == null) {
            throw new IllegalArgumentException(
                    "回收行不存在: " + graphId + " / " + body.recycleVersion());
        }
        // 再校验 suffix 与库中行一致（防止前端带错全串但仍碰巧末 4 位对上另一条）
        String storedSuffix = RecycleVersionGenerator.suffixOf(body.recycleVersion());
        if (!body.confirmSuffix().equals(storedSuffix)) {
            throw new IllegalArgumentException("confirmSuffix 校验失败");
        }
        log.info("开始彻底清除回收行, graphId={}, recycleVersion={}", graphId, body.recycleVersion());
        boolean deleted = store.purgeRecycle(graphId, body.recycleVersion());
        if (!deleted) {
            throw new IllegalArgumentException(
                    "回收行不存在: " + graphId + " / " + body.recycleVersion());
        }
        log.info("彻底清除回收行成功, graphId={}, recycleVersion={}", graphId, body.recycleVersion());
        return Map.of("purged", true);
    }

    /** 删除进回收箱请求体 */
    public record RecycleConfirmRequest(String confirmGraphId) {
    }

    /** 恢复请求体 */
    public record RestoreRecycleRequest(String recycleVersion) {
    }

    /** 清除请求体：三者必填，大小写敏感 */
    public record PurgeRecycleRequest(String recycleVersion, String confirmGraphId, String confirmSuffix) {
    }
}
