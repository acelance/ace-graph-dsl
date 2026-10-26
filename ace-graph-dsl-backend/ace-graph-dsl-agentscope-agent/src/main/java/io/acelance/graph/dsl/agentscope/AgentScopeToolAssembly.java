package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.ai.skill.SkillTools;
import io.acelance.graph.dsl.ai.tool.EmptyLocalToolResolver;
import io.acelance.graph.dsl.ai.tool.EmptyMcpToolResolver;
import io.acelance.graph.dsl.ai.tool.LocalToolResolver;
import io.acelance.graph.dsl.ai.tool.McpToolFilter;
import io.acelance.graph.dsl.ai.tool.McpToolResolver;
import io.acelance.graph.dsl.ai.tool.NamedToolCallback;
import io.acelance.graph.dsl.ai.tool.ToolDeduper;
import io.acelance.graph.dsl.ai.tool.ToolSource;
import io.acelance.graph.dsl.definition.GenericAgentSpec;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.resource.ResourceBinding;
import io.acelance.graph.dsl.resource.ResourceBindings;
import io.acelance.graph.dsl.runtime.ModelOverrideSpec;
import io.acelance.graph.dsl.skill.ForceSkills;
import io.acelance.graph.dsl.skill.SkillCatalogResolver;
import io.acelance.graph.dsl.skill.SkillContentLoader;
import io.acelance.graph.dsl.skill.SkillDescriptor;
import io.acelance.graph.dsl.skill.SkillL1Catalog;
import io.acelance.graph.dsl.skill.SkillResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * G-MCP：按 ACE Spec / SPI 装配工具（MCP / Skill / localTools），供 AS Toolkit 桥接。
 *
 * <p>须在子 Agent <strong>执行时</strong>调用（依赖 state 中的 runId / agentCode），
 * 与 {@code GenericAgentNode#resolveNamedTools} + Skill L1/Tools 对齐；不另开 MCP 连接。</p>
 */
public class AgentScopeToolAssembly {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeToolAssembly.class);

    private final McpToolResolver mcpToolResolver;
    private final LocalToolResolver localToolResolver;
    private final SkillCatalogResolver skillCatalog;
    private final SkillContentLoader skillContent;
    private final SkillResourceLoader skillResources;

    /**
     * @param mcpToolResolver    可空 → Empty
     * @param localToolResolver  可空 → Empty
     * @param skillCatalog       可空 → 跳过 L1
     * @param skillContent       可空 → 跳过 SkillTools
     * @param skillResources     可空
     */
    public AgentScopeToolAssembly(McpToolResolver mcpToolResolver,
                                  LocalToolResolver localToolResolver,
                                  SkillCatalogResolver skillCatalog,
                                  SkillContentLoader skillContent,
                                  SkillResourceLoader skillResources) {
        this.mcpToolResolver = mcpToolResolver != null ? mcpToolResolver : EmptyMcpToolResolver.INSTANCE;
        this.localToolResolver = localToolResolver != null ? localToolResolver : EmptyLocalToolResolver.INSTANCE;
        this.skillCatalog = skillCatalog;
        this.skillContent = skillContent;
        this.skillResources = skillResources;
    }

    /** 无 SPI 的空装配（仅测无工具路径）。 */
    public static AgentScopeToolAssembly noop() {
        return new AgentScopeToolAssembly(null, null, null, null, null);
    }

    /**
     * 从父 Flow state + Spec 装配工具。
     *
     * @param graphId      父图
     * @param parentNodeId 高阶节点 id（作 ctx.nodeId）
     * @param agentId      子 Agent 注册 id（日志）
     * @param spec         GenericAgentSpec
     * @param state        当前 OverAllState（含 runId / agentCode）
     */
    public AgentScopeToolAssemblyResult assemble(String graphId,
                                                 String parentNodeId,
                                                 String agentId,
                                                 GenericAgentSpec spec,
                                                 OverAllState state) {
        Objects.requireNonNull(spec, "spec");
        ResourceBinding binding = ResourceBindings.fromSpec(spec);
        String runId = readStateString(state, ModelOverrideSpec.ACE_RUN_ID_KEY);
        String agentCode = readStateString(state, LlmRequestContext.ACE_AGENT_CODE_KEY);
        String conversationId = readStateString(state, LlmRequestContext.ACE_CONVERSATION_ID_KEY);
        if (!StringUtils.hasText(agentCode)) {
            log.warn("AGENTSCOPE 工具装配缺 agentCode（父 seed 未透传?）, graphId={}, parentNodeId={}, agentId={}, runId={}",
                    graphId, parentNodeId, agentId, runId);
        } else {
            log.info("AGENTSCOPE 工具装配上下文, graphId={}, parentNodeId={}, agentId={}, agentCode={}, runId={}",
                    graphId, parentNodeId, agentId, agentCode, runId);
        }

        LlmRequestContext ctx = new LlmRequestContext(
                agentCode != null ? agentCode : "",
                graphId != null ? graphId : "",
                parentNodeId != null ? parentNodeId : "",
                runId != null ? runId : "",
                conversationId,
                state,
                binding);

        List<NamedToolCallback> allNamed = new ArrayList<>();
        int localCount = 0;
        int mcpCount = 0;
        int skillCount = 0;
        String suffix = "";

        // local
        if (binding.enableLocalTools() && !binding.localToolKeys().isEmpty()) {
            try {
                List<NamedToolCallback> local = localToolResolver.resolve(ctx, binding.localToolKeys());
                if (local != null && !local.isEmpty()) {
                    allNamed.addAll(local);
                    localCount = local.size();
                }
            } catch (Exception ex) {
                log.error("AGENTSCOPE LocalToolResolver 失败已跳过, graphId={}, agentId={}, err={}",
                        graphId, agentId, ex.toString(), ex);
            }
        }

        // MCP
        if (binding.enableMcp() && !binding.mcpKeys().isEmpty()) {
            try {
                List<NamedToolCallback> mcp = mcpToolResolver.resolve(ctx, binding.mcpKeys());
                if (mcp == null) {
                    mcp = List.of();
                }
                List<NamedToolCallback> filtered = McpToolFilter.apply(mcp, binding, parentNodeId);
                allNamed.addAll(filtered);
                mcpCount = filtered.size();
                log.info("AGENTSCOPE MCP 工具解析, graphId={}, agentId={}, raw={}, filtered={}",
                        graphId, agentId, mcp.size(), filtered.size());
            } catch (Exception ex) {
                log.error("AGENTSCOPE McpToolResolver 失败已跳过（不挡 Skill）, graphId={}, agentId={}, err={}",
                        graphId, agentId, ex.toString(), ex);
            }
        }

        // Skill L1 + builtin tools
        if (binding.enableSkill()) {
            List<String> effectiveSkillKeys = ForceSkills.effectiveSkillKeys(
                    state, parentNodeId, binding.skillKeys());
            if (!effectiveSkillKeys.isEmpty()) {
                if (skillCatalog != null) {
                    try {
                        List<SkillDescriptor> skills = skillCatalog.resolve(ctx, effectiveSkillKeys);
                        String l1 = SkillL1Catalog.format(skills != null ? skills : List.of());
                        if (StringUtils.hasText(l1)) {
                            suffix = l1;
                            log.info("AGENTSCOPE 已追加 Skill L1, graphId={}, agentId={}, items={}",
                                    graphId, agentId, skills != null ? skills.size() : 0);
                        }
                    } catch (Exception ex) {
                        log.error("AGENTSCOPE SkillCatalog 失败已跳过 L1, graphId={}, agentId={}, err={}",
                                graphId, agentId, ex.toString(), ex);
                    }
                }
                if (skillContent != null) {
                    try {
                        Set<String> activated = new LinkedHashSet<>();
                        List<NamedToolCallback> skillTools = SkillTools.builtin(
                                ctx, effectiveSkillKeys, skillContent, skillResources, activated);
                        if (skillTools != null && !skillTools.isEmpty()) {
                            allNamed.addAll(skillTools);
                            skillCount = skillTools.size();
                        }
                    } catch (Exception ex) {
                        log.error("AGENTSCOPE SkillTools 失败已跳过, graphId={}, agentId={}, err={}",
                                graphId, agentId, ex.toString(), ex);
                    }
                } else {
                    log.warn("AGENTSCOPE enableSkill 但无 SkillContentLoader, graphId={}, agentId={}",
                            graphId, agentId);
                }
            }
        }

        List<ToolCallback> modelCbs = ToolDeduper.toModelCallbacks(allNamed);
        AgentScopeToolAssemblyResult result = new AgentScopeToolAssemblyResult(
                modelCbs, suffix, skillCount, mcpCount, localCount);
        log.info("AGENTSCOPE 工具装配完成, bridge={}, graphId={}, agentId={}, result={}, sources={}",
                AgentscopeBridgeArtifacts.BRIDGE_ARTIFACT, graphId, agentId, result,
                summarizeSources(allNamed));
        return result;
    }

    private static String summarizeSources(List<NamedToolCallback> named) {
        int mcp = 0;
        int skill = 0;
        int local = 0;
        int other = 0;
        for (NamedToolCallback n : named) {
            if (n == null || n.source() == null) {
                other++;
                continue;
            }
            if (n.source() == ToolSource.MCP) {
                mcp++;
            } else if (n.source() == ToolSource.BUILTIN) {
                skill++;
            } else if (n.source() == ToolSource.LOCAL) {
                local++;
            } else {
                other++;
            }
        }
        return "mcp=" + mcp + ",skill=" + skill + ",local=" + local + ",other=" + other;
    }

    private static String readStateString(OverAllState state, String key) {
        if (state == null || key == null) {
            return null;
        }
        Object v = state.value(key).orElse(null);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
