package io.acelance.graph.dsl.execution;

import com.alibaba.cloud.ai.graph.NodeOutput;

/**
 * 图流式执行事件适配 SPI。
 *
 * <p>将图 {@code stream()} 产出的每个 {@link NodeOutput} 转换为一个可序列化的 SSE 负载对象
 * （通常为 {@code Map} 或业务 DTO）。宿主可实现本接口以自定义事件结构 / 字段裁剪 / 脱敏。</p>
 */
public interface GraphExecutionEventAdapter {

    /**
     * 将一个节点输出转换为 SSE 事件负载（将被序列化为 JSON 发送）。
     *
     * @param output 节点输出（可能是普通输出 / 流式 chunk / 中断元数据）
     * @return 可序列化负载对象
     */
    Object toPayload(NodeOutput output);
}
