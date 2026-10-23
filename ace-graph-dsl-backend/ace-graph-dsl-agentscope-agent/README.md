# ace-graph-dsl-agentscope-agent

可选模块：`impl=AGENTSCOPE` 子 Agent（**路径 B**）。

> 发版基线：**`1.1.21`**（路径 B MVP）；G-MCP 本地 **`1.1.22`**（install，待 deploy）。  
> **G-MCP**（分支 `feature-as-b1-g-mcp`）：ACE SPI → AS Toolkit 已落地；**宿主 Skill 冒烟已过**（`load_skill`+L2）；MCP 代码通、环境可用后再验。  
> 文档：[G-MCP 方案](../../docs/ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md)（§6.1） / [计划](../../docs/ACE-Graph-DSL-AgentScope-G-MCP-开发计划.md)；**G-STREAM-INNER** 另开。

## 定位

- **不**替代 ACE 图主编排；仅作为 SAA Flow（SEQUENTIAL 等）的子 Agent 执行引擎。
- **旁路** `spring-ai-alibaba-starter-agentscope`（官方无适配 agentscope 2.x）。
- 依赖 **`io.agentscope:agentscope-core:2.0.4`** + `agentscope-extensions-model-openai`。
- 自研 `AceAgentscopeReActBaseAgentAdapter`（extends SAA `BaseAgent`）包装 `ReActAgent`。
- **G-MCP**：`AgentScopeToolAssembly` 复用 `McpToolResolver` / `SkillTools` / `LocalToolResolver`；`AceToolCallbackAgentscopeBridge` → `Toolkit.registerAgentTool`。执行期按 `runId`/`agentCode` 装配；MCP 失败跳过不挡 Skill。

## 桥接标识

探测字段 / 日志：

```text
ace-graph-dsl-agentscope-agent@io.agentscope:agentscope-core:2.0.4
```

## 范围

- 模型按 instruction 出文本 → 写 `outputKey`；有工具时 ReAct 可调 ACE Skill/MCP。
- 子记忆 `NONE`（Q5）；不写 remote。
- 内层模型暂 `stream(false)`（缺口 **G-STREAM-INNER**）。
- **不做**：`agentscope-harness` Skill / HarnessAgent（后期策略）。

## 禁止依赖

- `agentscope-spring-boot-starter`（Boot 4）
- `agentscope-harness`（留给阶段 2+）

## 启用

宿主显式引入本模块后，自动配置注册 `AgentScopeSubAgentResolver`；未引入时 `impl=AGENTSCOPE` 校验报无匹配 Resolver。

## 文档

- [路径 B 开发方案](../../docs/ACE-Graph-DSL-AgentScope-路径B-开发方案.md)
- [路径 B 开发计划](../../docs/ACE-Graph-DSL-AgentScope-路径B-开发计划.md)
