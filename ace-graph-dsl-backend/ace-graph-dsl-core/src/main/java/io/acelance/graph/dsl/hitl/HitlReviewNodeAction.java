package io.acelance.graph.dsl.hitl;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.acelance.graph.dsl.definition.HitlNodeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 闸门节点：resume 写入 state 后执行；对 {@link HitlNodeSpec#injectKeys()} 做回声。
 *
 * <p>缺键不抛错（方案 §9 #4）；关键路径打日志便于排障。</p>
 */
public final class HitlReviewNodeAction implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(HitlReviewNodeAction.class);

    private final String graphId;
    private final String nodeId;
    private final List<String> injectKeys;

    /**
     * @param graphId    图 id（日志）
     * @param nodeId     画布节点 id
     * @param hitlSpec   HITL 规格；null 时 injectKeys 视为空
     */
    public HitlReviewNodeAction(String graphId, String nodeId, HitlNodeSpec hitlSpec) {
        this.graphId = graphId == null ? "" : graphId;
        this.nodeId = nodeId == null ? "" : nodeId;
        this.injectKeys = hitlSpec == null ? List.of() : hitlSpec.injectKeys();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        String runId = readRunId(state);
        log.info("HITL 闸门开始: graphId={}, nodeId={}, injectKeys={}, runId={}",
                graphId, nodeId, injectKeys, runId);
        for (String key : injectKeys) {
            Object v = state.value(key).orElse(null);
            if (v == null) {
                log.warn("HITL 注入键缺失（不中断）: graphId={}, nodeId={}, key={}, runId={}",
                        graphId, nodeId, key, runId);
                continue;
            }
            out.put(key, v);
            log.info("HITL 回声键: graphId={}, nodeId={}, key={}, present=true, runId={}",
                    graphId, nodeId, key, runId);
        }
        log.info("HITL 闸门结束: graphId={}, nodeId={}, echoedKeys={}, runId={}",
                graphId, nodeId, out.keySet(), runId);
        return out;
    }

    private static String readRunId(OverAllState state) {
        if (state == null) {
            return "";
        }
        Object v = state.value("ace.graph.dsl.runId").orElse(null);
        return v == null ? "" : String.valueOf(v);
    }
}
