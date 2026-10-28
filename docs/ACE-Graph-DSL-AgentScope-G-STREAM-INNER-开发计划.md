# ACE Graph DSL × AgentScope：G-STREAM-INNER — 开发计划

| 项 | 内容 |
|----|------|
| 状态 | **§0 已拍板；M0～M3 框架侧已落地；`1.1.23` 已 deploy mesrelease（2026-10-28）；下一站 M4 宿主冒烟/测环境跟版** |
| 日期 | 2026-10-26 |
| 分支 | 框架 `feature-as-b1-g-stream-inner`；宿主跟版 `feature-agentscope-b-g-stream-inner` |
| 方案 | [ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md)（§0 已拍板） |
| 前置 | G-MCP 能力已验收（`1.1.22`）；建议从含 G-MCP 的基线或合入后主线拉分支 |
| 估时 | **约 5～10 人日**（含 D0、AS 流、**SAA 父 bizParam/applyDeepThinking**、宿主冒烟；不含改平台 SSE 协议） |

---

## 1. 目标（一句话）

让 `impl=AGENTSCOPE` 子 Agent 经 `GraphStreamBridge`/`TokenChunk` 增量接到图 SSE；**父 SAA 可配通道策略**（applyDeepThinking + bizParam + attrs）；终态写 `outputKey`；SEQUENTIAL 必过。

---

## 2. 里程碑

| 里程碑 | 目标 | 建议人日 | 退出标准 |
|--------|------|----------|----------|
| **M0** | §0 拍板 + AS 2.0.4 流式 API 探针 | 0.5～1 | 拍板含 #11；探针笔记回填 |
| **M1** | **SAA 父通道规格 + UI + attrs 工具** | 1～2 | Spec 可反序列化旧 JSON；设计器可配；`streamAttrsFromSaaSpec` 单测 |
| **M2** | Model 开流 + Adapter emit（父 nodeId/kind/attrs）+ 降级 | 2～3 | stub 流 → bridge；无 bridge 整段写回 |
| **M3** | 工具轮可见性 + G-MCP 回归 + 父 thinking 冒烟 | 1～2 | SEQUENTIAL 绿；父 `thinking:true` 平台可见思考通道 |
| **M4** | 宿主回归 + 文档发版 | 1～2 | 存量图不坏；CHANGELOG / §10；建议 bump deploy |
| **缓冲** | API 差异 / CR | 0.5～1 | PR 可合 |

**合计**：约 **5～10 人日**。

---

## 3. 任务分解（可勾选）

### 3.0 M0 — 拍板与探针

- [x] 方案 §0 #1～#10 拍板回填（2026-10-26）
- [x] §0/#11：SAA 父通道补齐纳入本迭代（2026-10-26）
- [x] 对照 `agentscope-core:2.0.4`：`OpenAIChatModel.stream(boolean)` + `Model.stream` → `Flux<ChatResponse>`（javap 2026-10-26）
- [x] 差异回填方案 §0/#1
- [x] 分支 `feature-as-b1-g-stream-inner` / 宿主 `feature-agentscope-b-g-stream-inner`（已存在）

### 3.1 M1 — SAA 父通道规格（§0/#11）

- [x] `SaaWorkflowSpec`：增 `applyDeepThinking`、`enableBizParams`、`bizParamInterpreterId`、`bizParamRaw` + `of(...)` 兼容工厂
- [x] 旧图 JSON 缺字段兼容（默认关 / 空 = 今日行为）
- [x] `SaaWorkflowForm`：深度思考开关 + 业务附加参数（解释器 / raw）
- [x] `SaaStreamAttrs.fromSpec`：与 GENERIC 同键的中性 attrs
- [x] 单测：`SaaWorkflowSpecJsonTest` / `SaaStreamAttrsTest` / Validator（core 绿）

### 3.2 M2 — AS 流式装配与降级（消费 `streamEvents`，非弃用 `stream`）

- [x] `DefaultAgentScopeModelFactory`：移除永久 `stream(false)` → `stream(true)`
- [x] Adapter：`GraphStreamBridge`；父 `nodeId` / `streamResponseKind` / **父 attrs**（`ReActAgent.stream`）
- [x] 聚合全文写 `outputKey`
- [x] 降级：NOOP / 无 runId → 与今日一致 + WARN
- [x] 降级：stream 运行失败 → `call` + WARN（已推 chunk 补 FINISHED，不重推全文）
- [x] 单测：`AceAgentscopeStreamBridgeTest`（Bridge chunk；attrs 透传；降级 / stream 失败不 NPE）

### 3.3 M3 — 对齐、工具、父 thinking 冒烟

- [x] 可见流（方案 §0.5）：有无工具均全推 REASONING/SUMMARY/AGENT_RESULT；TOOL/HINT 仅日志；终答不得空
- [x] 回归：G-MCP Skill/MCP 单测不坏（`AgentScopeToolAssemblyTest` / Bridge 测试绿）
- [x] **顺带**：ROUTING `routerSystemPrompt` / `routerInstruction` / `routerFallbackAgent` → Factory/`LlmRoutingAgent` + 设计器（旧 JSON 缺省兼容）
- [ ] 宿主：父配 `{"thinking":true,…}` + 解释器 → 思考小气泡；未配 → 正文（→ M4 平台冒烟）
- [ ] （可选）PARALLEL/ROUTING/LOOP 补测

### 3.4 M4 — 宿主、文档、发版

- [ ] 平台 `chat/stream`：AS 增量 + 父通道策略
- [ ] GENERIC / 业务 / **存量 SAA 图**抽测不回归
- [x] 方案/计划文档对齐当前实现与 §0.5 口径（2026-10-26）
- [ ] agentscope README / host.md / 路径 B §10 / CHANGELOG 发版勾选（跟版时再勾）
- [x] CHANGELOG；版本 bump + `deploy` mesrelease（`1.1.23`，2026-10-28）
- [ ] PR：标题含 `as-b1-g-stream-inner` / G-STREAM-INNER

### 3.5 明确不做（本计划）

- [ ] ~~G-HARNESS / H0~~
- [ ] ~~改 LessoSseFrame 字段语义~~
- [ ] ~~重做工具 SPI~~
- [ ] ~~子 Spec thinking 覆盖父~~
- [ ] ~~子 nodeId 各自推流~~

---

## 4. 建议日程（1 人参考）

| 日序 | 内容 |
|------|------|
| D1 | M0 探针 + 拉分支；开工 M1 Spec |
| D2 | M1 UI + attrs 工具 + 单测 |
| D3～D4 | M2 Factory + Adapter emit |
| D5 | M3 工具回归 + 父 thinking 冒烟 |
| D6～D7 | M4 宿主/存量回归 + 文档发版 |

---

## 5. PR / 合入检查清单

### 框架 PR

- [ ] 标题含：`as-b1-g-stream-inner` / G-STREAM-INNER  
- [ ] 含 SAA 父 applyDeepThinking + bizParam（规格/UI/attrs）  
- [ ] 无 harness、无 Boot starter、无 Lesso 协议类型进框架  
- [ ] 无 bridge / 未配 bizParam 时与发版前兼容  
- [ ] 测试命令与结果粘贴  
- [ ] 方案 / 计划 / README / CHANGELOG / §10 已改  

### 宿主 PR（若拆）

- [ ] 依赖新框架版本  
- [ ] 平台流 + 父 thinking 冒烟打勾  
- [ ] 利费等业务 / 存量图回归不坏  

---

## 6. 验收标准（计划级）

与 [开发方案 §6](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md) / §0 一致：

1. SEQUENTIAL：平台可见增量；`outputKey` 完整  
2. 父 SAA 可配通道；thinking attrs 生效；未配兼容存量  
3. 降级路径明确  
4. G-MCP / GENERIC / 外层业务不回归  
5. 记忆 NONE；文档已更新  

---

## 7. Issue 文案（可复制）

**标题**：`[as-b1 follow-up] G-STREAM-INNER：AGENTSCOPE 内层流式 + SAA 父通道规格补齐`

**分支**：`feature-as-b1-g-stream-inner`（宿主 `feature-agentscope-b-g-stream-inner`）

**正文要点**：链本计划 + 方案（§0 含 #11）；`GraphStreamBridge`/`TokenChunk`；父 SAA `applyDeepThinking`+bizParam+attrs；SEQUENTIAL 必过；框架不写 Lesso 协议；Out：H0 / 子覆盖父。

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-26 | 初稿：G-STREAM-INNER 开发计划 |
| 2026-10-26 | 同步方案 §0 已拍板；M0 拍板项勾选 |
| 2026-10-26 | **纳入 §0/#11**：M1=SAA 父通道规格；估时调整为 5～10 人日；Issue 标题同步 |
| 2026-10-26 | **开工**：M0 探针完成；M1 Spec/UI/`SaaStreamAttrs`/单测落地 |
| 2026-10-26 | M2/M3 主路径勾选；§0/#8 stream 失败降级；§0.5 终答缓冲标待改；文档对齐 |
