# ACE Graph DSL × AgentScope：G-MCP — 开发计划

| 项 | 内容 |
|----|------|
| 状态 | **Skill 冒烟已过；`1.1.22` 已 deploy mesrelease**（MCP 环境 404；PR / 测环境跟版待续） |
| 日期 | 2026-10-23 |
| 分支 | 框架 `feature-as-b1-g-mcp`；宿主跟版 `feature-agentscope-b-g-mcp` |
| 方案 | [ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md](./ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md)（§0 已拍板） |
| 前置 | 路径 B MVP `1.1.21` 已验收；建议主线已合入或从含 1.1.21 的基线拉分支 |
| 估时 | **约 6～12 人日**（含 Skill+MCP 同迭代、四 pattern 补测、宿主跟测） |

---

## 1. 目标（一句话）

让 `impl=AGENTSCOPE` 子 Agent **复用 ACE MCP/Skill/localTools SPI**，在 AS 2.0.4 ReAct 中真实调工具并写回 `outputKey`；**Skill 必过**，MCP 环境允许则过；四 pattern 实现可挂工具，SEQUENTIAL 验收必过。

---

## 2. 里程碑

| 里程碑 | 目标 | 建议人日 | 退出标准 |
|--------|------|----------|----------|
| **M0** | 2.0.4 Toolkit API 探针 + 拉分支 | 0.5～1 | 实物 API 笔记落方案；分支已建 |
| **M1** | ToolAssembly + Bridge + Resolver 接入 | 2～4 | stub tool：SEQUENTIAL 集成测绿；另三 pattern 至少各 1 条 stub 测 |
| **M2** | Skill 对齐（L1 + SkillTools） | 1～2 | ✅ 宿主 SEQUENTIAL + `paddleocr`：`load_skill` 桥接成功 |
| **M3** | MCP 挂载 + runId 透传 | 1～2 | ✅ 代码路径通；`time-mcp` HTTP 404 跳过且不挡 Skill |
| **M4** | 样例 / 文档 / 宿主回归 / 发版 | 1～2 | 文档已跟；**`1.1.22` mesrelease ✅**；testdata / PR / 测环境跟版待续 |
| **缓冲** | 冲突 / CR | 0.5～1 | PR 可合 |

**合计**：约 **6～12 人日**。

---

## 3. 任务分解（可勾选）

### 3.0 M0 — 探针与分支

- [x] 方案 §0 拍板 #1～#7（2026-10-23）
- [x] 对照 `agentscope-core:2.0.4`：`Builder.toolkit(Toolkit)` / `registerAgentTool` / `callAsync`
- [x] 差异回填方案 §0/#6、§3
- [x] 分支 `feature-as-b1-g-mcp`（已存在）

### 3.1 M1 — 装配与桥

- [x] 新增 `AgentScopeToolAssembly`（复用 `ResourceBindings` / Resolver SPI / `ToolDeduper`）
- [x] 新增 `AceToolCallbackAgentscopeBridge` + `AceToolCallbackAgentTool`
- [x] 改造 Adapter：**执行期**装配 Toolkit 并构建 ReActAgent；无工具与 1.1.21 一致
- [x] 从 Flow state 读 `runId` / `agentCode`（缺则 WARN，不阻断无工具）
- [x] AutoConfiguration 注入 MCP/Local/Skill SPI → `AgentScopeToolAssembly`
- [x] 单测：Bridge + Assembly + Adapter + SEQUENTIAL 无工具回归（5 tests 绿）
- [ ] 集成测：SEQUENTIAL + AGENTSCOPE + stub tool 完整 ReAct 调工具写回（可选加强）
- [ ] 集成测：PARALLEL / ROUTING / LOOP 各至少 1 条 stub tool（补测）

### 3.2 M2 — Skill（验收必过）

- [x] Skill L1 / `SkillTools.builtin()` 已接入 Assembly（有 Bean 时生效）
- [x] 冒烟：宿主 `test-subagent-skill-graph` + `paddleocr-text-recognition`（SEQUENTIAL）
  - 挂载：`Toolkit registered=2` / `ReActAgent tools=2`
  - 调用：`桥接工具成功, tool=ace__skill__load_skill` + L2 chars=5657（复跑稳定）
- [x] 关键日志：装配 skill/mcp 数、tool 调用

### 3.3 M3 — MCP（环境允许则过）

- [x] `mcpKeys` + whitelist → `McpToolResolver` → Bridge（代码路径）
- [x] 宿主联调：已走到 `LessoNacosMcpToolResolver` 打开 `time-mcp`；endpoint HTTP 404 → `tools=0` / `mcp=0`（环境，非桥接）
- [x] 失败路径：MCP 异常跳过，不挡后续 Skill 装配（本轮 Skill 仍 `load_skill` 成功）
- [ ] （可选）换可用 MCP 后再验 `mcp≥1` + `桥接工具开始, tool=ace__mcp__…`
- [ ] （可选）localTools 同路径验证

### 3.4 M4 — 样例、文档、宿主跟测、发版

- [ ] testdata：`saa-agentscope-sequential-with-skill`（主）+ README
- [ ] 另三 pattern 样例或测试说明（可简）
- [x] 更新 `ace-graph-dsl-agentscope-agent/README.md`
- [x] 更新宿主 `ace-graph-agentscope-host.md` 冒烟项
- [x] 路径 B 方案 §10 **G-MCP** 状态更新
- [x] 根 `CHANGELOG.md`；版本 bump（`1.1.22`）
- [x] `mvn deploy` mesrelease（2026-10-23，含 `ace-graph-dsl-agentscope-agent-1.1.22`）
- [ ] 宿主测环境镜像跟 `1.1.22` 部署（pom 已写 1.1.22，需 CI 重打）
- [x] 门禁：Skill 冒烟已过；GENERIC/无工具 AS / 业务核对仍以既有回归为准

### 3.5 明确不做（本计划）

- [ ] ~~G-STREAM-INNER~~（另 Issue / `feature-as-b1-g-stream-inner`）
- [ ] ~~Harness Skill / Subagent~~（`feature-as-h0-harness-entry`；策略位后期）
- [ ] ~~以四 pattern×工具全绿为发版硬门槛~~（补测即可）

---

## 4. 建议日程（1 人参考）

| 日序 | 内容 |
|------|------|
| D1 | M0 API 探针 + 分支 |
| D2～D3 | M1 Assembly + Bridge + 四 pattern stub 测 |
| D4 | M2 Skill（必过） |
| D5 | M3 MCP（尽力）+ 宿主透传 |
| D6～D7 | M4 样例文档 + 发版 + 宿主同迭代测环境门禁 |

---

## 5. PR / 合入检查清单

### 框架 PR

- [ ] 标题含：`as-b1-g-mcp` / G-MCP  
- [ ] 无 `agentscope-harness`、无 Boot starter  
- [ ] 无工具路径行为与 `1.1.21` 兼容  
- [ ] 测试命令与结果粘贴  
- [ ] 方案 / 计划 / README / CHANGELOG 已改  
- [ ] **不**包含 G-STREAM-INNER / H0 大改  

### 宿主 PR（若拆）

- [ ] 依赖新框架版本  
- [ ] Skill（及可选 MCP）冒烟打勾  
- [ ] 利费等业务回归不坏  

---

## 6. 验收标准（计划级）

与 [开发方案 §6](./ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md) / §0 拍板一致：

1. **Skill 必过**（SEQUENTIAL）；MCP 环境允许则过  
2. 四 pattern 实现可挂工具；另三 pattern 有补测  
3. GENERIC / 无工具 AS / 现网业务 **不回归**（宿主同迭代）  
4. 记忆 NONE 不变  
5. 文档与 §10 缺口状态已更新  

---

## 7. Issue 文案（可复制）

**标题**：`[as-b1 follow-up] G-MCP：AGENTSCOPE 子 Agent 挂载 Skill / MCP 工具`

**分支**：`feature-as-b1-g-mcp`（宿主 `feature-agentscope-b-g-mcp`）

**正文要点**：链本计划 + 方案（§0 已拍板）；Skill 必过 / MCP 尽力；四 pattern 实现覆盖；Out：G-STREAM-INNER / Harness。

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-23 | 初稿：G-MCP 开发计划 |
| 2026-10-23 | 同步 §0 拍板：Skill+MCP 同迭代；四 pattern 补测；宿主同迭代；状态→可开工 |
| 2026-10-23 | M2 Skill 宿主冒烟通过；M3 MCP 打开失败（time-mcp 404）且不挡 Skill；文档/§10/host/CHANGELOG 已同步 |
| 2026-10-23 | **`1.1.22` deploy mesrelease SUCCESS**（全模块含 agentscope-agent） |
