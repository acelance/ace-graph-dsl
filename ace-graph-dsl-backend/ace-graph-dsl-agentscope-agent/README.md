# ace-graph-dsl-agentscope-agent

可选模块：`impl=AGENTSCOPE` 子 Agent（**路径 B**）。

## 定位

- **不**替代 ACE 图主编排；仅作为 SAA Flow（SEQUENTIAL 等）的子 Agent 执行引擎。
- **旁路** `spring-ai-alibaba-starter-agentscope`（官方无适配 agentscope 2.x）。
- 依赖 **`io.agentscope:agentscope-core:2.0.4`** + `agentscope-extensions-model-openai`。
- 自研 `AceAgentscopeReActBaseAgentAdapter`（extends SAA `BaseAgent`）包装 `ReActAgent`。

## 桥接标识

探测字段 / 日志：

```text
ace-graph-dsl-agentscope-agent@io.agentscope:agentscope-core:2.0.4
```

## MVP 范围

- 模型按 instruction 出文本 → 写 `outputKey`（对齐 M3 冒烟）。
- 子记忆 `NONE`（Q5）；不写 remote。
- 内层模型暂 `stream(false)`（缺口 **G-STREAM-INNER**，不得永久化）。
- **不做**：MCP/工具挂载（**G-MCP**）、`agentscope-harness` / HarnessAgent。

## 禁止依赖

- `agentscope-spring-boot-starter`（Boot 4）
- `agentscope-harness`（留给阶段 2+）

## 启用

宿主显式引入本模块后，自动配置注册 `AgentScopeSubAgentResolver`；未引入时 `impl=AGENTSCOPE` 校验报无匹配 Resolver。

## 文档

- [路径 B 开发方案](../../docs/ACE-Graph-DSL-AgentScope-路径B-开发方案.md)
- [路径 B 开发计划](../../docs/ACE-Graph-DSL-AgentScope-路径B-开发计划.md)
