package io.acelance.graph.dsl.store;

import io.acelance.graph.dsl.audit.GraphAuditActions;
import io.acelance.graph.dsl.audit.GraphAuditEvent;
import io.acelance.graph.dsl.audit.GraphAuditLogger;
import io.acelance.graph.dsl.builder.DynamicGraphBuilder;
import io.acelance.graph.dsl.builder.ValidationResult;
import io.acelance.graph.dsl.definition.GraphDefinition;
import io.acelance.graph.dsl.persistence.GraphDefinitionRepository;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图运行时：维护已启用的 CompiledGraph 池，支持发布与回滚。
 */
@Component
public class GraphRuntime {

    private static final Logger log = LoggerFactory.getLogger(GraphRuntime.class);

    private final Map<String, CompiledGraph> enabledGraphs = new ConcurrentHashMap<>();
    private final GraphDefinitionRepository repository;
    private final DynamicGraphBuilder builder;
    private final GraphAuditLogger auditLogger;

    public GraphRuntime(GraphDefinitionRepository repository, DynamicGraphBuilder builder,
                        GraphAuditLogger auditLogger) {
        this.repository = repository;
        this.builder = builder;
        this.auditLogger = auditLogger;
    }

    /**
     * 启动时加载所有已启用的图定义并编译。
     */
    @PostConstruct
    public void init() {
        for (GraphDefinition def : repository.listEnabled()) {
            try {
                CompiledGraph compiled = builder.build(def);
                enabledGraphs.put(def.graphId(), compiled);
                log.info("启动加载图定义成功, graphId={}, version={}", def.graphId(), def.version());
            } catch (Exception e) {
                log.error("启动加载图定义失败, graphId={}, version={}", def.graphId(), def.version(), e);
            }
        }
    }

    /**
     * 获取已启用的 CompiledGraph。
     */
    public CompiledGraph get(String graphId) {
        CompiledGraph g = enabledGraphs.get(graphId);
        if (g == null) {
            throw new IllegalStateException("Graph 未启用或不存在: " + graphId);
        }
        return g;
    }

    /**
     * 发布流程：校验 → 编译 → 切换 enabled。
     *
     * @return 发布结果
     */
    public PublishResult publish(String graphId, String version, String operator) {
        GraphDefinition def = repository.loadVersion(graphId, version);
        if (def == null) {
            return failPublish(graphId, version, operator, "版本不存在: " + graphId + "@" + version);
        }
        ValidationResult validation = builder.validate(def);
        if (!validation.ok()) {
            return failPublish(graphId, version, operator, "校验失败: " + String.join("; ", validation.errors()));
        }
        try {
            CompiledGraph compiled = builder.build(def);
            repository.disableCurrentEnabled(graphId);
            repository.markEnabled(graphId, version);
            enabledGraphs.put(graphId, compiled);
            log.info("发布成功, graphId={}, version={}, operator={}", graphId, version, operator);
            audit(GraphAuditEvent.graph(GraphAuditActions.PUBLISH, graphId, version, operator, true, "发布成功"));
            return PublishResult.ok(version);
        } catch (Exception e) {
            log.error("发布失败, graphId={}, version={}", graphId, version, e);
            return failPublish(graphId, version, operator, "编译失败: " + e.getMessage());
        }
    }

    private PublishResult failPublish(String graphId, String version, String operator, String message) {
        audit(GraphAuditEvent.graph(GraphAuditActions.PUBLISH, graphId, version, operator, false, message));
        return PublishResult.failed(message);
    }

    /**
     * 回滚到指定版本。
     */
    public PublishResult rollback(String graphId, String toVersion, String operator) {
        GraphDefinition def = repository.loadVersion(graphId, toVersion);
        if (def == null) {
            return failRollback(graphId, toVersion, operator, "回滚目标版本不存在: " + graphId + "@" + toVersion);
        }
        try {
            CompiledGraph compiled = builder.build(def);
            repository.disableCurrentEnabled(graphId);
            repository.markEnabled(graphId, toVersion);
            enabledGraphs.put(graphId, compiled);
            log.info("回滚成功, graphId={}, toVersion={}, operator={}", graphId, toVersion, operator);
            audit(GraphAuditEvent.graph(GraphAuditActions.ROLLBACK, graphId, toVersion, operator, true, "回滚成功"));
            return PublishResult.ok(toVersion);
        } catch (Exception e) {
            log.error("回滚失败, graphId={}, toVersion={}", graphId, toVersion, e);
            return failRollback(graphId, toVersion, operator, "回滚编译失败: " + e.getMessage());
        }
    }

    private PublishResult failRollback(String graphId, String toVersion, String operator, String message) {
        audit(GraphAuditEvent.graph(GraphAuditActions.ROLLBACK, graphId, toVersion, operator, false, message));
        return PublishResult.failed(message);
    }

    private void audit(GraphAuditEvent event) {
        if (auditLogger == null) {
            return;
        }
        try {
            auditLogger.record(event);
        } catch (Exception e) {
            log.warn("审计记录失败, action={}, resourceId={}", event.action(), event.resourceId(), e);
        }
    }

    /**
     * 校验图定义（不发布）。
     */
    public ValidationResult validate(GraphDefinition def) {
        return builder.validate(def);
    }

    /**
     * 发布结果。
     */
    public record PublishResult(boolean success, String version, String message) {
        public static PublishResult ok(String version) {
            return new PublishResult(true, version, "OK");
        }

        public static PublishResult failed(String message) {
            return new PublishResult(false, null, message);
        }
    }
}
