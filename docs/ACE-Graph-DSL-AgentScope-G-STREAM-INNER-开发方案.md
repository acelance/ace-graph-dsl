# ACE Graph DSL × AgentScope：G-STREAM-INNER — 开发方案

| 项 | 内容 |
|----|------|
| 状态 | **§0 已拍板**；**M0～M3 框架侧已落地（§0.5=有无工具全推模型可见文本）**（2026-10-26）；下一站 M4 宿主/发版 |
| 日期 | 2026-10-26 |
| 缺口 ID | **G-STREAM-INNER**（路径 B 方案 §5.6 / §10） |
| 分支 | 框架 `feature-as-b1-g-stream-inner`；宿主跟版 `feature-agentscope-b-g-stream-inner` |
| 前置 | 路径 B MVP `1.1.21`；G-MCP 能力已验收（`1.1.22`，Skill + MCP）；**建议** G-MCP PR 合入或从含 G-MCP 的基线拉分支 |
| 关联 | [路径 B 开发方案](./ACE-Graph-DSL-AgentScope-路径B-开发方案.md) §5.6 / §10；[本缺口开发计划](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发计划.md)；[G-MCP 方案](./ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md)；[host.md](../../lesso-ai-project/lesso-ai-platform-agent-server/docs/ace-graph-agentscope-host.md) |

---

## 0. 已拍板（2026-10-26）

> 路径 B 拍板 #7：外层硬流 / 内层不得永久 `stream(false)`。下列为本缺口定案。

| # | 议题 | 结论 |
|---|------|------|
| 1 | AS 流式切入点 | **D0 已探针 + 已落地**：`OpenAIChatModel.stream(true)`；消费路径为 **`ReActAgent.streamEvents(...)`** → `Flux<AgentEvent>`（AS 2.0.4 **非弃用** API）。旧 `stream(..., RuntimeContext)` 标 `@Deprecated`，**禁止**再用 |
| 2 | 推 SSE 通道 | **按建议**：Adapter → `GraphStreamBridge.emit(TokenChunk)`，对齐 GENERIC；**不**另造 AS 专用桥 |
| 3 | `nodeId` / `responseKind` | **按建议**：TokenChunk 使用**父 SAA** `nodeId` + 父 `streamResponseKind`（见 §0.1） |
| 4 | 正文 vs 思考 | **挂父则通道跟父（或父 id 上的 attrs）**；**不是**「改由子 Agent 设思考=思考」（见 §0.2） |
| 5 | ReAct 多轮 + 工具 | **落地口径见 §0.5**（有无工具均全推模型可见文本，终答不得空；TOOL/HINT 仅日志） |
| 6 | 验收路径与分层 | **平台路径冒烟优先**，但 **Lesso/`LessoSseFrame` 只在宿主**；框架只产出中性 `TokenChunk`（见 §0.3） |
| 7 | 验收体验 | **按建议**：SEQUENTIAL 首包明显早于整段结束；不要求与 GENERIC 逐 token 一致 |
| 8 | 降级 | **已落地**：无 bridge / 无 runId → 整段 `call` 写 `outputKey` + WARN；**stream 运行失败** → 同降级 `call` + WARN（已推 chunk 则补 FINISHED，不重推全文）；**不得破坏存量图** |
| 9 | 记忆 / 工具 | **按建议**：子禁 `READ_WRITE`（日志口径 NONE）；G-MCP 不回退；本缺口不改工具 SPI |
| 10 | 范围边界 | **按建议**：不改外层 SSE JSON 语义；不做 H0；不与 G-MCP 同 PR 大改协议 |
| 11 | SAA 父通道规格 | **已落地（框架）**：父 SAA 补齐 `applyDeepThinking` + bizParam；**两条正交链路**见 §0.4 / §0.6；宿主 thinking 冒烟属 M4 |

### 0.1 `responseKind` 从哪来？（回应 #3）

画布上常只看见 **nodeId**；`responseKind` 是另一配置维度，**不是**子 Agent 名。

| 层级 | 配置位置 | 说明 |
|------|----------|------|
| 设计期 | **父 SAA 节点**规格字段 `streamResponseKind`（`SaaWorkflowSpec`） | 设计器属性面板可选；目录 `GET /api/stream-response-kinds`（如 `BIZ` / `OUTPUT`） |
| 运行期 | 写入 `TokenChunk.responseKind` | GENERIC：`StreamingLlmTemplate` 从 Spec/`LlmCallRequest` 带入；SAA 定案：**以父节点 kind 聚合**，避免每个子 Agent 各推一条 OUTPUT |
| 宿主消费 | `AceGraphLessoPlatformSseAdapter` | 用 `responseKind` 等做 OUTPUT 累加等；**框架不写** `LessoSseFrame` |

本缺口实现：AS 子 Agent emit 时 **nodeId = 父 SAA nodeId**，**responseKind = 父 `SaaWorkflowSpec.streamResponseKind`**（缺省与 GENERIC 缺省策略一致）。子 Agent 自身 Spec.kind **不**单独打到用户可见流。

### 0.2 正文 vs 思考（回应 #4，已澄清）

**结论：对前端呈现时，通道（思考气泡 / 正文气泡）跟「TokenChunk 上的 nodeId + attrs / 该 node 图定义」走；本缺口 #3 把流挂到父 SAA，故应由父侧策略决定，而不是「子 Agent Spec 里写了 thinking 就自动是思考」。**

#### 现网对照（你截图里的中间节点）

| 节点类型 | 谁配 `{"thinking":true,"nodeDisplay":…}` | 宿主怎么用 |
|----------|------------------------------------------|------------|
| 图上的 GENERIC / 业务叶节点 | **该图节点自己**的业务附加参数 + 解释器 `lesso.sse-frame`；常配合「应用请求深度思考」 | `TokenChunk.nodeId` = 该节点 → 查定义或 attrs → 思考/正文 |
| **SAA 高阶节点（补齐前）** | 仅有 `streamResponseKind` 等；**缺** bizParam / applyDeepThinking | 挂父且无 attrs → 宿主按正文 |
| **SAA 高阶节点（本迭代后）** | 与 GENERIC 同级可配；emit 带父 attrs | 父 `thinking:true` 等 → 思考/正文由父决定 |

宿主解析顺序（`AceGraphLessoPlatformSseAdapter`）：**先** `TokenChunk.attrs`，**再**按 `nodeId` 查图定义。SAA 结构节点查定义往往对不上 Agent Spec，故 **attrs 透传为必做**。

#### 挂到父 SAA 时，能不能「子 Agent 设思考=思考」？

**不能默认成立**（宿主按父 `nodeId` 查，不读子目录 Spec）。本迭代定案：

| 做法 | 本缺口态度 |
|------|------------|
| A. 父 SAA 补齐 applyDeepThinking + bizParam + attrs 透传 | **必做（§0/#11）** |
| B. 未开启父 bizParam 时默认正文 | **兼容存量** |
| C. 子 nodeId 各自推流 | **不做**（与 #3 冲突） |
| D. 子 Spec thinking 覆盖父 | **不做**（本迭代） |

```text
子 AS 终答文本 ──► TokenChunk{ nodeId=父SAA, responseKind=父流式类型, attrs←父SaaWorkflowSpec }
                         │
                         ▼
              宿主：attrs（优先）→ 思考小气泡 / 正文 delta
```

**一句话：** 通道由**父 SAA 配置 + attrs**决定；本迭代把父规格缺口补齐，而不是改去子 Agent。

### 0.3 框架 vs 业务协议（回应 #6）

| 侧 | 职责 | 禁止 |
|----|------|------|
| **框架**（agentscope-agent / streaming / saa） | `GraphStreamBridge` + `TokenChunk`（nodeId / responseKind / outputType / 中性 attrs）；SAA 规格只存中性 bizParam 字段 | **禁止**依赖 `LessoSseFrame`、`deltaThinking`、`NODE_DEEP_THINKING`、平台 session 字段 |
| **宿主**（agent-server） | `AceGraphLessoPlatformSseAdapter`：TokenChunk → 平台 SSE | 不把 Lesso 协议类下沉进框架模块 |
| **验收** | 可用平台 `…/chat/stream` 做端到端冒烟 | 验收路径 ≠ 把业务协议实现塞进框架 |

`/{graphId}/stream` + formatter：框架原生路径补测即可，不挡发版。

### 0.4 SAA 父通道规格补齐（§0/#11，本迭代必做）

对齐 `GenericAgentSpec` 能力，落到 `SaaWorkflowSpec` + 设计器 + 运行透传。

> **禁止混用：** `applyDeepThinking` ≠ bizParam attrs。前者只闸门「是否把请求级深思交给**模型**」；后者只影响宿主 SSE **思考/正文气泡**（业务解释器）。

| 项 | 内容 |
|----|------|
| 规格字段 | `applyDeepThinking`、`enableBizParams`、`bizParamInterpreterId`、`bizParamRaw`（与 GENERIC 同级；框架只存中性载荷） |
| 设计器 | 「应用请求深度思考」「业务附加参数」分开展示；hint 不得写成互相决定 |
| 运行时 · attrs | 父 enableBizParams 时 emit `TokenChunk.attrs`（`SaaStreamAttrs`）；**与 deepThinking 无关** |
| 运行时 · 模型深思 | `applyDeepThinking` ∧ state `ace.graph.dsl.deepThinking` → `AgentScopeGenerateOptionsCustomizer` → `GenerateOptions.additionalBodyParams`（宿主如 `enable_thinking`） |
| 缺省 | 字段缺省 / 未启用 → 与今日一致；**存量图不坏** |
| 宿主 | **不改** `LessoSseFrame`；attrs 走既有解释器；GenerateOptions 走独立 Bean |
| 非目标 | 不为 SAA 另造解释器；不把 `lesso.sse-frame` 硬编码进框架核心 |

### 0.5 §0/#5 可见流落地口径（2026-10-26 确认；同日修订）

> **硬约束：有无 Toolkit 均不得把终答压空。** 模型可见文本一律进用户可见流。
> 消费 API：`streamEvents` → `AgentEvent`（`TEXT_BLOCK_DELTA` / `THINKING_BLOCK_DELTA` / `AGENT_RESULT` 兜底）。

| 事件 | 可见流 | 说明 |
|------|--------|------|
| `TEXT_BLOCK_DELTA` / `THINKING_BLOCK_DELTA` | **全量推送**（有无工具相同） | 思考块增量仍进 TokenChunk；前端是否「思考气泡」由 **bizParam attrs** 决定 |
| `AGENT_RESULT` | 仅当此前无 delta 时兜底推全文 | 避免空终答 |
| `TOOL_*` / `HINT_BLOCK` | **不进**可见流 | 仅日志 |

**实现状态**：

- **已落地**：`AgentScopeVisibleStreamSession` 消费 `AgentEvent`；全推模型可见文本；TOOL/HINT 仅日志；一次写 `outputKey`。  
- **不做**：按有无工具压制文本、工具后缓冲再补推。

### 0.6 两条正交链路（applyDeepThinking vs bizParam）

| 链路 | 配置 | 运行落点 | 谁解析行为 |
|------|------|----------|------------|
| **模型深思** | 父/子 `applyDeepThinking` ∧ state `ace.graph.dsl.deepThinking` | `GenerateOptions`（SPI `AgentScopeGenerateOptionsCustomizer`；GENERIC 对等 `LlmChatOptionsCustomizer`） | 模型 / 网关（如 `enable_thinking`） |
| **SSE 思考气泡** | 父 `enableBizParams` + `bizParamRaw`（如 `{"thinking":true}`） | `TokenChunk.attrs` | **业务侧**解释器（如 `lesso.sse-frame`） |

二者可同时开、可只开其一；**禁止**用 deepThinking 决定 attrs，也**禁止**用 attrs 代替模型 extraBody。

---

## 1. 背景与目标

### 1.1 缺口提出时的问题（落地前）

> 下表描述 **G-STREAM-INNER 开工前** 的痛点，非当前分支实现。

| 点 | 说明 |
|----|------|
| 外层 | `AceGraphExecutionController` 已 `Flux.merge(graph.stream, tokens)`；业务走 `LessoSseFrame`（宿主适配） |
| GENERIC 内层 | `GenericAgentNode` → `StreamingLlmTemplate` → `GraphStreamBridge.emit(TokenChunk)` |
| AGENTSCOPE 内层（当时） | `DefaultAgentScopeModelFactory` **钉** `stream(false)`；Adapter：`ReActAgent.call(...).block()` → 整段写 `outputKey`；**不**经 bridge |
| SAA 父通道（当时） | 无 `applyDeepThinking` / bizParam，挂父流时无法像 GENERIC 叶节点一样控思考/正文 |
| 结果（当时） | 用户侧 AS「干等 → 整段弹出」；SAA 父无法配通道策略 |

### 1.2 本阶段目标

1. `impl=AGENTSCOPE` 子 Agent 执行期向图 SSE **增量推送**（至少终答可见文本）。  
2. 去掉永久 `stream(false)`；按 §0/#8 降级。  
3. **最终仍写**约定 `outputKey`。  
4. **父 SAA 补齐** `applyDeepThinking` + bizParam，设计器可配，emit **attrs 透传**（§0/#11）。  
5. **SEQUENTIAL 必过**；G-MCP 路径不回归。  
6. 外层业务 SSE / GENERIC / 无工具 AS / **存量图**回归不坏。  
7. 关键日志：流式开启/降级、emit、完成写回。  
8. 子记忆继续 **NONE**（Q5）。

### 1.3 非目标（禁止顺带做）

| 项 | 归属 |
|----|------|
| `LessoSseFrame` / 前端协议字段进框架 | **禁止**（§0.3）；除非单独评审改宿主协议 |
| `agentscope-harness` / HarnessAgent / Plan | G-HARNESS / H0 |
| 重做 G-MCP 工具装配 | 已验收；本缺口只消费既有 Toolkit |
| AS 模型内思考/正文双流拆分 | 否；通道跟父 SAA 配置（§0/#4/#11） |
| 四 pattern×流式全绿挡发版 | 否；SEQUENTIAL 必过 |
| 子 Agent remote 记忆 / 假 USER | 禁止 |
| 子 Spec thinking 覆盖父 | 本迭代不做（§0.2-D） |

---

## 2. 设计原则

| # | 原则 |
|---|------|
| P1 | **ACE 图仍是主编排**；流式事件进既有 `GraphStreamBridge` |
| P2 | **对齐 GENERIC 的 TokenChunk 契约**；业务协议留在宿主 |
| P3 | **外层硬流不变**；本缺口只补内层 B（§5.6） |
| P4 | AS API **以 agentscope-core 2.0.4 实物为准**；D0 探针回填 |
| P5 | **可降级**：无 bridge / 无 runId / stream 失败 → `call` 整段写回 + WARN；**不破坏存量图** |
| P6 | **不引入** Boot starter / harness；**不**把 Lesso 类型引入框架模块 |
| P7 | **父 SAA 通道可配**：规格/UI/attrs 与 GENERIC 同级语义，缺省兼容存量 |

---

## 3. 目标架构

### 3.1 落地前（非流，对照用）

```text
DefaultAgentScopeModelFactory
  → OpenAIChatModel.builder().stream(false)
AceAgentscopeReActBaseAgentAdapter
  → ReActAgent.call(userMsg).block()
  → out.put(outputKey, fullText)
  // 无 GraphStreamBridge
```

### 3.2 当前目标架构（G-STREAM-INNER，框架侧已基本落地）

```text
DefaultAgentScopeModelFactory
  → stream(true)
AceAgentscopeReActBaseAgentAdapter.invoke
  ├─ 解析 runId / 父 SAA nodeId / 父 streamResponseKind / 父 bizParam→attrs
  ├─ GraphStreamBridge（缺则 NOOP / 无 runId → 降级 call）
  ├─ 装配 Toolkit（G-MCP，已有）
  ├─ ReActAgent.stream → AgentScopeVisibleStreamSession（§0.5）
  │     └─ 可见增量 → emit TokenChunk(…, attrs←父 SaaWorkflowSpec)
  ├─ stream 失败 → WARN + call 写 outputKey（§0/#8）
  └─ out.put(outputKey, aggregatedText)
外层 Controller
  └─ merge tokens → 宿主 Adapter 映射平台 SSE（框架不感知 Lesso）
```

> **实现要点**：流式逻辑在 Adapter + `AgentScopeStreamSupport` / `AgentScopeVisibleStreamSession`；父 attrs 来自 `SaaStreamAttrs.fromSpec`（§0.4）；**禁止** Adapter 复制 MCP；**禁止**绕过 bridge 写 HTTP / `LessoSseFrame`。

### 3.3 与 GENERIC 对照

| | GENERIC_AGENT | AGENTSCOPE + 父 SAA（本缺口后） |
|--|---------------|--------------------------------|
| 执行引擎 | ChatClient + `StreamingLlmTemplate` | AS 2.0.4 `ReActAgent.stream` + 可见流会话 |
| 推 SSE | `GraphStreamBridge` + `TokenChunk` | **同左**；nodeId/kind/attrs 跟**父 SAA** |
| 通道配置 | 叶节点 Spec bizParam / applyDeepThinking | **父** `SaaWorkflowSpec` 同级字段 |
| 终态 | 写节点输出 / state | 仍写 `outputKey` |
| 无 runId / NOOP bridge | 非流或降级 | **同左**（§0/#8） |

---

## 4. 模块与类（建议）

主战场：`ace-graph-dsl-agentscope-agent` + `ace-graph-dsl-core`（`SaaWorkflowSpec`）+ `ace-graph-dsl-ui`（`SaaWorkflowForm`）；可依赖 `ace-graph-dsl-ai` streaming。

| 类 / 面（建议名，实施可微调） | 职责 |
|-------------------------------|------|
| `SaaWorkflowSpec` | 增补 applyDeepThinking / bizParam 四字段 |
| `SaaWorkflowForm`（UI） | 父节点深度思考 + 业务附加参数面板 |
| `DefaultAgentScopeModelFactory` | `stream(true)`（已落地） |
| `AceAgentscopeReActBaseAgentAdapter` | 消费流、emit（含父 attrs）、聚合、写 `outputKey`；保留 G-MCP |
| `AgentScopeStreamSupport` / `SaaStreamAttrs`（可选） | TokenChunk 构造、父重定向、`streamAttrsFromSaaSpec` |
| 单测 | stub 流 → bridge；attrs 含 enableBizParams；降级；Spec 序列化兼容 |

**禁止**：Adapter 直接操作 Controller / 写 `LessoSseFrame`。

宿主：跟版冒烟；协议不变则前端可无改（仅设计器增字段）。

---

## 5. 数据与上下文

| 键 / 信息 | 用途 |
|-----------|------|
| `runId` | `GraphStreamBridge.emit` 路由 |
| 父 SAA `nodeId` | TokenChunk.nodeId（§0/#3） |
| 父 `streamResponseKind` | TokenChunk.responseKind（§0.1） |
| 父 bizParam / applyDeepThinking | TokenChunk.attrs（§0.4） |
| `outputKey` | 终态写回 |
| bridge 是否 NOOP | 流式或降级 |

---

## 6. 验收标准（方案级）

1. **SEQUENTIAL × AGENTSCOPE**：平台流可见增量；bridge 有 chunk；首包早于整段结束。  
2. 结束后 `outputKey` 完整。  
3. **父 SAA**：设计器可配 applyDeepThinking + bizParam；父配 `thinking:true` 时平台冒烟可见思考通道（attrs 透传）；未配时与今日一致（正文）。  
4. **G-MCP** 路径不回归。  
5. 降级与**存量图**不坏。  
6. GENERIC、外层业务 SSE、无工具 AS 回归不坏。  
7. 子 Agent 仍 NONE；框架无 Lesso 协议类型依赖。  
8. 文档 / CHANGELOG / §10 已更新。

---

## 7. 风险与缓解

| 风险 | 缓解 |
|------|------|
| AS 2.0.4 ReAct 无稳定 Agent 级 stream | D0 探针；可退化为模型流或半流 |
| 子 nodeId 导致多气泡 | §0/#3 父节点重定向 |
| 工具结果进用户气泡 | §0.5：TOOL/HINT 仅日志；模型文本全推 |
| 业务协议污染框架 | §0.3；禁 Lesso import |
| SAA Spec 扩字段破坏旧 JSON | Jackson 缺省兼容；缺字段=旧行为 |
| 存量图回归 | §0/#8；bizParam 默认关 |

---

## 8. 测试与样例

| 类型 | 内容 |
|------|------|
| D0 探针 | 2.0.4 流式入口；回填 §0/#1 |
| 单测 | stub 流 → bridge；`streamAttrsFromSaaSpec`；Spec 反序列化旧 JSON |
| 集成测 | SEQUENTIAL + AGENTSCOPE；降级；父 thinking attrs |
| 宿主冒烟 | 平台流增量；父配 thinking 出思考气泡；存量/业务图抽测 |
| 回归 | GENERIC 流式图、无工具 AS sql 样例 |

---

## 9. 与其它缺口关系

```text
路径 B MVP（1.1.21，内层非流）──已验收
G-MCP（1.1.22，工具）──────────能力已验收
        │
        ├─► G-STREAM-INNER（本文）── AS 流 + 父 SAA 通道规格补齐（§0/#11）
        └─► 阶段 2 H0 Harness（另分支；不同迭代）
```

---

## 10. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-26 | 初稿：G-STREAM-INNER 开发方案；§0 待拍板 |
| 2026-10-26 | **§0 已拍板** #1～#10；增补 §0.1～§0.3 |
| 2026-10-26 | §0.2 澄清父通道 / 否决「都改子 Agent」 |
| 2026-10-26 | **§0/#11**：SAA 父 `applyDeepThinking`+bizParam **纳入本迭代**；增 §0.4 |
| 2026-10-26 | 开工：D0 探针回填 §0/#1；M1 Spec/UI/`SaaStreamAttrs` 落地 |
| 2026-10-26 | M2：`stream(true)` + Adapter `ReActAgent.stream` → bridge emit（父 nodeId/kind/attrs）+ 降级；单测绿 |
| 2026-10-26 | **正交补齐**：`applyDeepThinking`→`AgentScopeGenerateOptionsCustomizer`；流式改 **`streamEvents`**（弃用 `stream`）；增 §0.6 |
| 2026-10-26 | **顺带**：SAA ROUTING 暴露 `routerSystemPrompt` / `routerInstruction` / `routerFallbackAgent` → `LlmRoutingAgent`（可控路由规则） |
| 2026-10-26 | M3：有工具压制中间 REASONING；工具事件仅日志；G-MCP 相关单测回归绿 |
| 2026-10-26 | §0/#8：stream 失败 → `call` + WARN；已推 chunk 补 FINISHED、不重推全文 |
| 2026-10-26 | 文档对齐：§0/#1 改为 ReActAgent.stream；新增 §0.5 可见流口径；§1.1/§3 标明落地前 vs 当前；#5 终答缓冲标「待改代码」 |
| 2026-10-26 | §0.5 工具后 REASONING 缓冲 + 结束补推已落地；`includeReasoningChunk` 恒开 |
| 2026-10-26 | **§0.5 修订**：有无工具均全推模型可见文本（否决按工具压制/缓冲）；硬约束终答不得空 |
