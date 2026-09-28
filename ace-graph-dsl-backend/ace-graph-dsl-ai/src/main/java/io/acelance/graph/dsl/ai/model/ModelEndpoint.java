package io.acelance.graph.dsl.ai.model;

/**
 * 请求级模型端点（三路合并结果）。
 *
 * @param baseUrl API 根地址
 * @param apiKey  密钥（日志严禁打印）
 * @param modelId 模型 ID
 */
public record ModelEndpoint(String baseUrl, String apiKey, String modelId) {

    public boolean isComplete() {
        return nonBlank(baseUrl) && nonBlank(apiKey) && nonBlank(modelId);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
