package io.acelance.graph.dsl.langfuse;

/**
 * Langfuse ingestion HTTP 发送抽象（便于测试时替换为内存桩）。
 */
@FunctionalInterface
public interface LangfuseHttpSender {

    /**
     * 发送一批事件 JSON（已序列化为 Langfuse ingestion 请求体）。
     * @param json {@code {"batch":[...]} 形式的请求体}
     */
    void send(String json) throws Exception;
}
