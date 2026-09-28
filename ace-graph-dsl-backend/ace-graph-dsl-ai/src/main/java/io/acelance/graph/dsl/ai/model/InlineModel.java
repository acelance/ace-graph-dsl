package io.acelance.graph.dsl.ai.model;

/**
 * 节点内联模型三字段。
 */
public record InlineModel(String baseUrl, String apiKey, boolean apiKeyMasked, String modelId) {

    public static InlineModel fromSpec(io.acelance.graph.dsl.definition.GenericAgentSpec spec) {
        if (spec == null) {
            return new InlineModel(null, null, false, null);
        }
        return new InlineModel(spec.modelBaseUrl(), spec.modelApiKey(), spec.apiKeyMasked(), spec.modelId());
    }

    public boolean hasAny() {
        return nonBlank(baseUrl) || nonBlank(apiKey) || nonBlank(modelId);
    }

    public boolean isComplete() {
        return nonBlank(baseUrl) && nonBlank(apiKey) && nonBlank(modelId);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
