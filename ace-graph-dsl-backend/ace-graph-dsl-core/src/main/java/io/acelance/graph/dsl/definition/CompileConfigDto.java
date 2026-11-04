package io.acelance.graph.dsl.definition;

import java.util.List;

/**
 * 编译配置 DTO，对应 DSL 中的 compile 块。
 *
 * @param interruptBefore   需要在哪些节点前中断（HITL）
 * @param saver             checkpoint 存储类型：memory / jdbc / redis
 * @param interruptContent  图级人工中断提示文案（可空；宿主平台 SSE 结束帧可读；空则宿主 fallback）
 */
public record CompileConfigDto(
        List<String> interruptBefore,
        String saver,
        String interruptContent
) {

    /** 默认编译配置：不中断、内存存储、无自定义中断文案 */
    public static CompileConfigDto defaultConfig() {
        return new CompileConfigDto(List.of(), "memory", null);
    }
}
