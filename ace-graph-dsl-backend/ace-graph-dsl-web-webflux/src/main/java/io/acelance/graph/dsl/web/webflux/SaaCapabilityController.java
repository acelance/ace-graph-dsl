package io.acelance.graph.dsl.web.webflux;

import io.acelance.graph.dsl.agent.SaaWorkflowNodeFactory;
import io.acelance.graph.dsl.agent.SubAgentResolver;
import io.acelance.graph.dsl.definition.SaaSubAgentRefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [ace-graph-dsl-web-webflux]产品 {@code SaaCapabilityController}：{@code GET /api/graph/capabilities/saa}。
 *
 * <p>产品 WebFlux 设计器 API（{@code ace.graph.dsl.web.enabled} + REACTIVE）；对齐 Servlet 探测接口；
 * 设计器据此显示「高阶多智能体」是否可用。不新增 yaml 开关。</p>
 */
@RestController
@RequestMapping("/api/graph/capabilities")
public class SaaCapabilityController {

    private static final Logger log = LoggerFactory.getLogger(SaaCapabilityController.class);

    private final ObjectProvider<SaaWorkflowNodeFactory> saaWorkflowNodeFactory;
    private final ObjectProvider<List<SubAgentResolver>> subAgentResolvers;

    public SaaCapabilityController(ObjectProvider<SaaWorkflowNodeFactory> saaWorkflowNodeFactory,
                                           ObjectProvider<List<SubAgentResolver>> subAgentResolvers) {
        this.saaWorkflowNodeFactory = saaWorkflowNodeFactory;
        this.subAgentResolvers = subAgentResolvers;
        log.info("注册 [ace-graph-dsl-web-webflux]SaaCapabilityController（WebFlux /api/graph/capabilities/saa）");
    }

    /**
     * @return {@code saaWorkflowEnabled} / {@code agentscopeEnabled} / openPatterns / openImpls
     */
    @GetMapping("/saa")
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
