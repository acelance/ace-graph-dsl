# ACE Graph DSL × AgentScope：G-MCP — 开发方案

| 项 | 内容 |
|----|------|
| 状态 | **能力已验收（Skill + MCP 真实调用）**；`1.1.22` mesrelease；待 PR / 测环境跟版 |
| 日期 | 2026-10-23（进度更新 2026-10-26） |
| 缺口 ID | **G-MCP**（路径 B 方案 §10） |
| 分支 | 框架 `feature-as-b1-g-mcp`；宿主跟版 `feature-agentscope-b-g-mcp` |
| 前置 | 路径 B MVP **1.1.21**（AS 2.0.4 + 自研 Adapter，无工具）已验收 |
| 关联 | [路径 B 开发方案](./ACE-Graph-DSL-AgentScope-路径B-开发方案.md) §5.7 / §10；[本缺口开发计划](./ACE-Graph-DSL-AgentScope-G-MCP-开发计划.md)；[host.md](../../lesso-ai-project/lesso-ai-platform-agent-server/docs/ace-graph-agentscope-host.md) |

---

## 0. 已拍板（2026-10-23）

| # | 议题 | 结论 |
|---|------|------|
| 1 | 工具来源 | **默认复用 ACE SPI**（`McpToolResolver` / `LocalToolResolver` / `SkillTools` + Spec keys）；**不**另建 AS 资源目录。本迭代**不**引 `agentscope-harness` Skill；架构上可后期用策略挂 Harness Skill，但**单子 Agent 禁止双挂** |
| 2 | Skill / MCP | **同迭代一次做完 Toolkit 桥**；验收：**Skill 必过**，**MCP 环境允许则过**（不可用时日志明确，不挡 Skill 发版） |
| 3 | SAA pattern | **实现一次覆盖** SEQUENTIAL / PARALLEL / ROUTING / LOOP（Resolver 挂工具与 pattern 无关）。验收：**SEQUENTIAL×工具必过**；另三 pattern **补冒烟/集成测**，不单独立项挡发版 |
| 4 | 记忆 | **继续 Q5**：子 Agent 强制 NONE；禁 `READ_WRITE`；工具会话按 **run** 生命周期，与记忆正交 |
| 5 | 内层流式 | **本缺口不强制**；仍可 `stream(false)`，流式归 **G-STREAM-INNER** |
| 6 | AS API | **以 agentscope-core 2.0.4 实物为准**。已探针：`ReActAgent.Builder.toolkit(Toolkit)`；`Toolkit.registerAgentTool(AgentTool)`；`AgentTool.callAsync(ToolCallParam)` → `Mono<ToolResultBlock>` |
| 7 | 宿主 | **同迭代跟测**；门禁：GENERIC / 无工具 AGENTSCOPE / 现网业务图行为不回归 |

---

## 1. 背景与目标

### 1.1 现状问题

| 点 | 说明 |
|----|------|
| 路径 B MVP | `AceAgentscopeReActBaseAgentAdapter` → `ReActAgent.call` → 写 `outputKey`；**忽略** Spec 工具字段 |
| GENERIC 生产 | `GenericAgentNode` + `StreamingLlmTemplate` 挂 MCP/Skill/`ToolCallback`，利费等依赖 OCR/Skill |
| 结果 | AGENTSCOPE **不能**替换需工具的核对叶；产品能力表须写清「AS 无工具」 |

### 1.2 本阶段目标

1. `impl=AGENTSCOPE` 子 Agent 能使用与 GENERIC **同一套** Spec 工具绑定（MCP / Skill / localTools）。  
2. 在 AS **ReAct** 循环中真实调工具，最终文本仍写约定 `outputKey`。  
3. **Skill 必过**（SEQUENTIAL + 至少 1 个真实 skill）；**MCP 环境允许则过**。  
4. 四 pattern **实现层均可挂工具**；验收 SEQUENTIAL 必过，PARALLEL/ROUTING/LOOP 补冒烟/测。  
5. 无工具 AS 图 / GENERIC / 现网业务图 **回归不坏**（宿主同迭代跟测门禁）。  
6. 关键路径有日志（装配工具数、工具调用开始/结束/失败）。  
7. 子记忆继续 **NONE**（Q5）。

### 1.3 非目标（禁止顺带做）

| 项 | 归属 |
|----|------|
| 内层模型流式接 SSE | **G-STREAM-INNER** |
| 以「四 pattern×工具全绿」为发版硬门槛 | 否；另三 pattern 补测即可，不另立挡板 Issue |
| `agentscope-harness` Skill / HarnessAgent / Subagent / Plan | 阶段 2+（G-HARNESS）；本迭代不做 |
| 子 Agent remote 记忆 / 假 USER | 禁止 |
| 用 AS Toolkit 替换 ACE 资源目录 | 禁止 |
| 用 SAA ROUTING 替换利费 `fee_type` 条件边 | 禁止 |

---

## 2. 设计原则

| # | 原则 |
|---|------|
| P1 | **ACE 图仍是主编排**；AS 仅子运行时 |
| P2 | **资源仍走 ACE**：`ref=agentscope:agent:{id}` → 已注册 GenericAgent Spec |
| P3 | **工具装配复用 GENERIC 同级 SPI**，Adapter 内禁止另开 MCP 连接 |
| P4 | **不引入** `agentscope-harness`、`agentscope-spring-boot-starter` |
| P5 | 宿主 MCP session 依赖 `runId` / `agentCode`：须从 Flow `OverAllState` **透传**（对齐 `GenericAgentBaseAgentAdapter`） |
| P6 | AS 2.0.4 API **以实物为准**，有差改实现与本方案一子弹 |

---

## 3. 目标架构

### 3.1 今日（路径 B MVP，无工具）

```text
AgentScopeSubAgentResolver
  → modelFactory.create(spec)
  → ReActAgent.builder().name().sysPrompt().model().build()   // 无 toolkit
  → AceAgentscopeReActBaseAgentAdapter
        └─ instruction → call → outputKey
```

### 3.2 目标（G-MCP）

```text
AgentScopeSubAgentResolver.resolve(...)
  ├─ modelFactory.create(spec)                         // 已有
  ├─ AgentScopeToolAssembly.assemble(...)              // 新增
  │     ├─ ResourceBindings.fromSpec(spec)
  │     ├─ LlmRequestContext(runId, agentCode, graphId, …)  // 从 OverAllState / 父请求透传
  │     ├─ LocalToolResolver + McpToolResolver + McpToolFilter
  │     ├─ Skill：L1 拼入 sysPrompt；SkillTools → NamedToolCallback
  │     ├─ ToolDeduper → List<ToolCallback>（或中间 DTO）
  │     └─ AceToolCallbackAgentscopeBridge → AS Toolkit / 可调用工具
  → AceAgentscopeReActBaseAgentAdapter.invoke（执行期装配，因需 runId）
        ├─ AgentScopeToolAssembly.assemble(state, spec)
        ├─ AceToolCallbackAgentscopeBridge.toToolkit(callbacks)
        └─ ReActAgent.builder().sysPrompt(+L1).model(...).toolkit(Toolkit).build()
              → call → 写 outputKey；记忆 NONE
```

> **实现要点（2026-10-23）**：工具在 **invoke** 时装配（非 resolve），以便透传 `ace.graph.dsl.runId` / `agentCode`；无工具时行为与 1.1.21 一致。

### 3.3 与 GENERIC 对照

| | GENERIC_AGENT | AGENTSCOPE（本缺口后） |
|--|---------------|------------------------|
| 执行引擎 | Spring AI `ChatClient` + `StreamingLlmTemplate` | AS 2.0.4 `ReActAgent` |
| 工具来源 | 同左 SPI | **同左 SPI** + Bridge |
| Spec 工具字段 | 全量使用 | **须全量使用**（不再忽略） |
| 记忆 | 子默认 NONE | 强制 NONE + 禁 READ_WRITE |

---

## 4. 模块与类（建议）

主战场：`ace-graph-dsl-agentscope-agent`（已依赖 `ace-graph-dsl-ai`）。

| 类（建议名，实施可微调） | 职责 |
|--------------------------|------|
| `AgentScopeToolAssembly` | Spec + Context → ACE 侧 `ToolCallback` 列表 + 可选 sysPrompt 追加 |
| `AceToolCallbackAgentscopeBridge` | `ToolCallback` ↔ AS 2.0.4 Toolkit / Tool 调用委托 |
| `AgentScopeSubAgentResolver`（改） | 构建 ReActAgent 前调用 Assembly；透传 run 上下文 |
| `AceAgentscopeReActBaseAgentAdapter` | 原则上少改；若需在 `doCall` 注入 per-call context，保持输出契约 |

**禁止**：在 Adapter 内直接 `openToolSession` 绕过 `McpToolResolver`。

宿主侧（Lesso）：确认 `LessoNacosMcpToolResolver` / `LessoAceGraphMcpSessionRegistry` 在子 Agent 路径能拿到合法 `runId`；不足则在透传层补齐，**不**复制一套 MCP 客户端。

---

## 5. 数据与上下文透传

`GenericAgentBaseAgentAdapter` 今日会读 state 中的 run / agentCode / conversation 等键再调 `GenericAgentNode`。  
AS 路径今日**未透传** → MCP `openToolSession` 可能无法按 run 回收。

本缺口必须对齐至少：

| 键 / 信息 | 用途 |
|-----------|------|
| `runId`（或图执行约定键） | MCP session 生命周期 |
| `agentCode` | 宿主 MCP / Skill 隔离 |
| `graphId` / `parentNodeId` | 日志与排障 |
| Spec.`mcpKeys` / `skillKeys` / `localToolKeys` / whitelist | 工具集合 |

---

## 6. 验收标准（方案级）

1. **Skill 必过**：Spec 挂 skill 的 AGENTSCOPE 子 Agent，ReAct 中真实调工具并写回 `outputKey`（SEQUENTIAL）。  
2. **MCP**：环境可用则同路径验收；不可用则日志明确，**不挡** Skill 发版。  
3. **Pattern**：SEQUENTIAL×工具必过；PARALLEL / ROUTING / LOOP 各有冒烟或集成测（stub 即可），不挡发版。  
4. 与同 Spec GENERIC 在「能调通工具」层面对齐（不要求逐 token 一致）。  
5. 无工具 AS、GENERIC、现网业务图 **回归不坏**（宿主同迭代门禁）。  
6. 子 Agent 仍拒绝 `READ_WRITE`；不写 remote USER/ASSISTANT。  
7. 日志：装配数量、tool 开始/成功/失败、bridge 仍为路径 B 坐标。  
8. 文档 / CHANGELOG / §10 G-MCP 状态更新。

### 6.1 宿主冒烟证据

#### 6.1.1 Skill（2026-10-23，本地 agent-server + `1.1.22`）

图：`test-subagent-skill-graph`；子 Agent：`agent:test-subagent-skill-or-mcp`；口令 forceSkills=`paddleocr-text-recognition`。

| 验收项 | 结论 | 关键日志 |
|--------|------|----------|
| Skill 挂载 | ✅ | `ResourceBindings … skill=true`；`Toolkit 装配完成, registered=2`；`ReActAgent 已挂 Toolkit, tools=2` |
| Skill 调用 | ✅ | `桥接工具开始/成功, tool=ace__skill__load_skill`；`Skill L2 已加载: key=paddleocr-text-recognition` |
| MCP 打开失败不挡 | ✅ | `time-mcp` 404 跳过；同轮仍 `load_skill` 成功 |
| 旁路噪音 | 忽略 | Nacos `find-skills` 404、`weather-assistant` Prompt not found |

#### 6.1.2 MCP 真实调用（2026-10-26，本地 agent-server + `1.1.22`）

图同上；`mcpKeys` 含 `time-mcp` + `translation`（Higress 网关路径）。

| 验收项 | 结论 | 关键日志 |
|--------|------|----------|
| `time-mcp` | ⚠ 环境 | `打开 MCP: key=time-mcp` → `Server Not Found … 404` → `MCP open 失败已跳过` |
| `translation` 打开 | ✅ | `打开 MCP: key=translation`；`MCP 工具: translate_text → mcp__translation__translate_text` |
| MCP 桥接调用 | ✅ | `桥接工具开始/成功, tool=mcp__translation__translate_text, costMs=346` |
| 装配汇总 | ✅ | `AgentScopeToolAssemblyResult{total=3, skill=2, mcp=1}`；子 Agent 写回 `step1` 成功 |

> **说明**：G-MCP **Skill 必过 + MCP 尽力**均已满足。`time-mcp`（`/mcp-servers/time`）持续 404 属环境，勿当框架缺陷。可选再验本机鉴权 MCP：`alibaba-demo-mcp-nacos-auth-webflux-server`（`192.168.28.64:8094/mcp`）。

---

## 7. 风险与缓解

| 风险 | 缓解 |
|------|------|
| AS 2.0.4 Toolkit API 与文档不一致 | D0 对照实物 jar；差异回填本方案 |
| Skill L1/L2/L3 与 GENERIC 行为漂移 | 优先复用 `SkillTools` + 同一 L1 拼装逻辑 |
| MCP session 泄漏 / 串 run | 强制透传 `runId`；跟宿主 registry 单测或联调清单 |
| 工具结果过大拖垮内层 call | 日志打长度；超限策略可二期（勿阻塞首期冒烟） |
| 与 G-STREAM-INNER 纠缠 | 本缺口允许内层非流；勿在本 PR 改 SSE 协议 |

---

## 8. 测试与样例

| 类型 | 内容 |
|------|------|
| 单测 | Bridge：假 `ToolCallback` → AS 可调用；Assembler 读 Spec keys |
| 集成测 | SEQUENTIAL + AGENTSCOPE + stub tool 写回（**必过**）；PARALLEL/ROUTING/LOOP + stub tool 各至少 1 条 |
| 宿主冒烟 | 1 个真实 Skill；MCP 环境允许则再跑 MCP；业务图回归 |
| testdata | `saa-agentscope-sequential-with-skill`（主）；另三 pattern 可复用/改造现有 saa-*-* 样例挂 AGENTSCOPE+工具 |

---

## 9. 与其它缺口关系

```text
路径 B MVP（1.1.21，无工具）──已验收
        │
        ├─► G-MCP（本文，已拍板）── ACE SPI→Toolkit；Skill 必过 / MCP 尽力；四 pattern 实现覆盖
        ├─► G-STREAM-INNER ── 内层流式（可并行，另分支）
        └─► 阶段 2 H0 Harness（含可选 Harness Skill 策略；勿与 G-MCP 同迭代强塞）
```

---

## 10. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-23 | 初稿：G-MCP 开发方案（承接路径 B §10） |
| 2026-10-23 | **§0 已拍板** #1～#7（Skill+MCP 同迭代；四 pattern 实现覆盖/SEQUENTIAL 必过；宿主同迭代跟测） |
| 2026-10-23 | 代码落地：`AgentScopeToolAssembly` / `AceToolCallbackAgentscopeBridge` / Adapter 执行期挂 Toolkit；单测 5 绿 |
| 2026-10-23 | **宿主 Skill 冒烟通过**（`test-subagent-skill-graph`）：挂载 Toolkit + `ace__skill__load_skill` 桥接 + L2 `paddleocr-text-recognition`；MCP 打开 `time-mcp` 遇 HTTP 404 跳过且不挡 Skill（见方案 §6.1） |
| 2026-10-26 | **MCP 真实调用验收**：同图打开 `translation`，桥接 `mcp__translation__translate_text` 成功；`time-mcp` 仍环境 404；状态→能力已验收（见 §6.1.2） |
