package io.acelance.graph.dsl.resource;

import java.util.List;

/**
 * 可选资源 key 存在性校验（§7.4）：有 Bean 时保存入口拦截；无 Bean 则跳过。
 */
public interface ResourceKeyValidator {

    enum Status { OK, MISSING, SKIPPED, ERROR }

    record ItemResult(ResourceType type, String key, Status status, String message) {
    }

    record ValidationResult(boolean ok, List<ItemResult> items) {
        public static ValidationResult passed() {
            return new ValidationResult(true, List.of());
        }
    }

    /**
     * @param agentCode 智能体编码
     * @param graphId   图 ID
     * @param binding   节点资源勾选
     */
    ValidationResult validate(String agentCode, String graphId, ResourceBinding binding);
}
