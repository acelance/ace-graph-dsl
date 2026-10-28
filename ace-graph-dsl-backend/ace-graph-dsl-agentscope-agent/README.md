# ace-graph-dsl-agentscope-agent

可选模块：`impl=AGENTSCOPE` 子 Agent（**路径 B**）。

> 发版基线：**`1.1.23`**（G-STREAM-INNER，内层流式）；G-MCP 为 `1.1.22`；路径 B MVP 为 `1.1.21`。  
> **G-MCP**（分支 `feature-as-b1-g-mcp`）：ACE SPI → AS Toolkit 已落地；**Skill + MCP 真实调用均已验收**（2026-10-26）。  
> **G-STREAM-INNER**（分支 `feature-as-b1-g-stream-inner`）：框架侧主路径已落地（`stream(true)` + `ReActAgent.streamEvents` → `GraphStreamBridge`；父 SAA attrs；`applyDeepThinking`→GenerateOptions SPI）；宿主冒烟 / 发版见计划 M4。  
> 文档：[G-STREAM-INNER 方案](../../docs/ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md) / [计划](../../docs/ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发计划.md)；[G-MCP 方案](../../docs/ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md)。

## 定位

- **不**替代 ACE 图主编排；仅作为 SAA Flow（SEQUENTIAL 等）的子 Agent 执行引擎。
- **旁路** `spring-ai-alibaba-starter-agentscope`（官方未适配 agentscope 2.x）。
- 依赖 **`io.agentscope:agentscope-core:2.0.4`** + `agentscope-extensions-model-openai`。
- 自研 `AceAgentscopeReActBaseAgentAdapter`（extends SAA `BaseAgent`）包装 `ReActAgent`。
- **G-MCP**：`AgentScopeToolAssembly` 复用 `McpToolResolver` / `SkillTools` / `LocalToolResolver`；`AceToolCallbackAgentscopeBridge` → `Toolkit.registerAgentTool`。
- **G-STREAM-INNER**：`OpenAIChatModel.stream(true)`；`ReActAgent.streamEvents` → `GraphStreamBridge.emit(TokenChunk)`（挂**父 SAA** nodeId / kind / **bizParam attrs**）；`applyDeepThinking`∧state → `AgentScopeGenerateOptionsCustomizer`（**模型**参数，与 attrs 正交）；无 bridge / 无 runId / streamEvents 失败 → `call` + WARN。

## 桥接标识

探测字段 / 日志：

```text
ace-graph-dsl-agentscope-agent@io.agentscope:agentscope-core:2.0.4
```

## 范围

- 模型按 instruction 出文本 → 写 `outputKey`；有工具时 ReAct 可调 ACE Skill/MCP。
- 子记忆禁 `READ_WRITE`（Q5）；不写 remote。
- 内层流式：见方案 §0.5（有无工具均全推模型可见文本；TOOL/HINT 仅日志）。
- **不做**：`agentscope-harness` Skill / HarnessAgent（后期策略）；框架内写 Lesso SSE 协议。

## 禁止依赖

- `agentscope-spring-boot-starter`（Boot 4）
- `agentscope-harness`（留给阶段 2+）

## 启用

宿主显式引入本模块后，自动配置注册 `AgentScopeSubAgentResolver`（注入 `GraphStreamBridge`）；未引入时 `impl=AGENTSCOPE` 校验报无匹配 Resolver。

## 文档

- [路径 B 开发方案](../../docs/ACE-Graph-DSL-AgentScope-路径B-开发方案.md)
- [路径 B 开发计划](../../docs/ACE-Graph-DSL-AgentScope-路径B-开发计划.md)
- [G-STREAM-INNER 开发方案](../../docs/ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md)
- [G-STREAM-INNER 开发计划](../../docs/ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发计划.md)
