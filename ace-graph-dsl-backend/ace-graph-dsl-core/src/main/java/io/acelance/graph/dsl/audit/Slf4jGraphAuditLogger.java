package io.acelance.graph.dsl.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认审计实现：将事件输出到 SLF4J 日志（logger 名 {@code ace.graph.dsl.audit}）。
 */
public class Slf4jGraphAuditLogger implements GraphAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("ace.graph.dsl.audit");

    @Override
    public void record(GraphAuditEvent event) {
        if (event == null) {
            return;
        }
        log.info("[AUDIT] action={} resource={}:{} version={} operator={} success={} detail={} at={}",
                event.action(), event.resourceType(), event.resourceId(), event.version(),
                event.operator(), event.success(), event.detail(), event.timestamp());
    }
}
