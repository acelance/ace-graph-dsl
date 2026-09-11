package io.acelance.graph.dsl.ai.tool;

import io.acelance.graph.dsl.llm.LlmRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 默认空实现：业务未提供 {@link LocalToolResolver} 时不挂载任何本地工具。
 */
public final class EmptyLocalToolResolver implements LocalToolResolver {

    private static final Logger log = LoggerFactory.getLogger(EmptyLocalToolResolver.class);

    public static final EmptyLocalToolResolver INSTANCE = new EmptyLocalToolResolver();

    private EmptyLocalToolResolver() {
    }

    @Override
    public List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> toolKeys) {
        if (toolKeys != null && !toolKeys.isEmpty()) {
            log.warn("节点 {} 已勾选 localToolKeys={}，但使用 EmptyLocalToolResolver，返回空列表；"
                            + "请业务注册 LocalToolResolver Bean",
                    ctx == null ? "?" : ctx.nodeId(), toolKeys);
        }
        return List.of();
    }
}
