package io.acelance.graph.dsl.definition;

import java.util.List;

/**
 * 编译配置 DTO，对应 DSL 中的 compile 块。
 *
 * @param interruptBefore 需要在哪些节点前中断（HITL）
 * @param saver           checkpoint 存储类型：memory / jdbc / redis
 */
public record CompileConfigDto(
        List<String> interruptBefore,
        String saver
) {

    /** 默认编译配置：不中断、内存存储 */
    public static CompileConfigDto defaultConfig() {
        return new CompileConfigDto(List.of(), "memory");
    }
}
