package io.acelance.graph.dsl.audit;

/**
 * 操作审计 SPI。
 *
 * <p>发布 / 回滚 / 脚本节点增删改等写操作会通过本接口上报审计事件。宿主可实现本接口将事件
 * 落库 / 推送到统一审计中心；未提供实现时，starter 注册默认的 {@link Slf4jGraphAuditLogger}
 * 将事件写入日志。</p>
 */
public interface GraphAuditLogger {

    /**
     * 记录一条审计事件。实现需保证不抛出异常影响主流程（建议自行捕获并降级）。
     *
     * @param event 审计事件
     */
    void record(GraphAuditEvent event);
}
