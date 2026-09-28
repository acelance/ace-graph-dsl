package io.acelance.graph.dsl.web;

import io.acelance.graph.dsl.agent.SaaWorkflowNodeFactory;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设计器能力探测：是否启用 SAA 高阶多智能体模块 / AgentScope 子 Agent。
 *
 * <p>FE：面板据此提示「模块未启用」；不影响旧图。</p>
 */
@RestController
public class SaaCapabilityController {

    private static final Logger log = LoggerFactory.getLogger(SaaCapabilityController.class);

    private final ObjectProvider<SaaWorkflowNodeFactory> saaWorkflowNodeFactory;
    private final ObjectProvider<List<SubAgentResolver>> subAgentResolvers;

    public SaaCapabilityController(ObjectProvider<SaaWorkflowNodeFactory> saaWorkflowNodeFactory,
                                   ObjectProvider<List<SubAgentResolver>> subAgentResolvers) {
        this.saaWorkflowNodeFactory = saaWorkflowNodeFactory;
        this.subAgentResolvers = subAgentResolvers;
    }

    /**
     * @return {@code saaWorkflowEnabled} / {@code agentscopeEnabled} / openPatterns / openImpls
     */
    @GetMapping("/capabilities/saa")
    public Map<String, Object> saaCapabilities() {
        boolean enabled = saaWorkflowNodeFactory != null
                && saaWorkflowNodeFactory.getIfAvailable() != null;
        List<SubAgentResolver> resolvers = subAgentResolvers != null
                ? subAgentResolvers.getIfAvailable()
                : null;
        if (resolvers == null) {
            resolvers = List.of();
        }
        boolean agentscope = resolvers.stream()
                .anyMatch(r -> r.supports(SaaSubAgentRefs.IMPL_AGENTSCOPE));
        List<String> openImpls = new ArrayList<>();
        if (enabled) {
            openImpls.add(SaaSubAgentRefs.IMPL_GENERIC_AGENT);
        }
        if (agentscope) {
            openImpls.add(SaaSubAgentRefs.IMPL_AGENTSCOPE);
        }
        log.info("SAA 能力探测, saaWorkflowEnabled={}, agentscopeEnabled={}, openImpls={}",
                enabled, agentscope, openImpls);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saaWorkflowEnabled", enabled);
        body.put("agentscopeEnabled", agentscope);
        body.put("openPatterns", enabled
                ? List.of("SEQUENTIAL", "PARALLEL", "ROUTING", "LOOP")
                : List.of());
        body.put("openImpls", openImpls);
        body.put("agentscopeBridge", agentscope
                ? "spring-ai-alibaba-starter-agentscope"
                : null);
        return body;
    }
}
