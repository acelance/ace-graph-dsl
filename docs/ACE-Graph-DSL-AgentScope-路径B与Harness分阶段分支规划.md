# ACE Graph DSL × AgentScope：路径 B / Harness 分阶段分支规划

| 项 | 内容 |
|----|------|
| 日期 | 2026-10-21（进度更新 2026-10-23） |
| 阶段 1 | **MVP 已验收**（框架 `1.1.21` mesrelease；测试环境业务回归通过；待 PR 合入主线） |
| 目的 | 工作量大，**禁止单分支一把梭**；按可合并、可回滚、可验收切阶段建分支 |
| 改造重心 | **框架** `ace-graph-dsl`（约 70%～85%）；宿主 `lesso-ai-platform-agent-server` 辅 |
| 前置文档 | [Harness 能力边界](./ACE-Graph-DSL-AgentScope-Harness能力边界-core与ReAct薄桥.md)；[改动量评估](./ACE-Graph-DSL-AgentScope-动态规划与Harness子Agent改动量评估.md)；宿主 [integration-paths](../../lesso-ai-project/lesso-ai-platform-agent-server/docs/ace-graph-agentscope-integration-paths.md) |
| **阶段 1 实施** | [路径 B 开发方案](./ACE-Graph-DSL-AgentScope-路径B-开发方案.md)；[路径 B 开发计划](./ACE-Graph-DSL-AgentScope-路径B-开发计划.md) |
| 已定搁置 | Memory / Compaction 进 ACE；不升 Boot 4 / SAA 2.0 整栈（除非另立项） |

---

## 0. 总原则

1. **一阶段一分支、一阶段一可合并增量**；合并进主开发线前须有冒烟清单打勾。
2. **主开发线**假设为 `develop` / `main`（以各仓实际为准）；下文用 `{base}` 表示。
3. **框架仓与宿主仓可同阶段、不同 PR**；框架先合、宿主跟版本，避免宿主先升依赖而框架未发。
4. 每阶段结束：**可回退到上一阶段产物**（路径 A / 旧薄桥仍可编译或明确废弃说明）。
5. **Gate**：未过验收不得开下一阶段分支（可先建分支但不合）。

```text
{base}
  └─ feature/as-b0-version-bridge          ← 阶段 0（可选）：依赖与 BOM 探针
       └─ feature/as-b1-path-b-mvp         ← 阶段 1：路径 B 薄桥可跑
            └─ feature/as-h0-harness-entry ← 阶段 2：HarnessAgent 入口
                 └─ feature/as-h1-subagent-mvp ← 阶段 3：同步子 Agent
                      ├─ feature/as-h2-plan-mode      ← 阶段 4a（可并行于 4b 准备）
                      └─ feature/as-h3-productization ← 阶段 4b：设计器/resume/SSE
                           └─ feature/as-h4-harden    ← 阶段 5：生产硬化
```

> 命名可按团队规范改前缀（`feat/`、`topic/`）；**阶段序号与验收含义保持不变**。

---

## 1. 阶段一览

| 阶段 | 分支建议名 | 目标 | 人日（粗） | 合入后能力 |
|------|------------|------|------------|------------|
| **0** | `feature/as-b0-version-bridge` | 依赖探针：可编过、不改行为 | 1～2 | 可选；降低 B1 翻车 |
| **1** | `feature/as-b1-path-b-mvp` | 路径 B：AS 2.x + ReAct 薄桥，`impl=AGENTSCOPE` 冒烟 | 8～15 | **AGENTSCOPE 在 2.x 可跑** |
| **2** | `feature/as-h0-harness-entry` | 引 `agentscope-harness`，Resolver 建 HarnessAgent（能力先关） | 3～6 | 入口换成 Harness；红框未开 |
| **3** | `feature/as-h1-subagent-mvp` | 同步 Harness Subagent 演示 | 5～10 | **动态委派可演示** |
| **4a** | `feature/as-h2-plan-mode` | Plan Mode 库内 + 最小 HITL 钩子 | 2～4 | 规划可演示（现网 resume 可后置） |
| **4b** | `feature/as-h3-productization` | Spec/设计器、resume、SSE/观测 | 8～15 | 可配置雏形 |
| **5** | `feature/as-h4-harden` | 后台子 Agent、失败策略、双栈边界文档 | 5～12 | 生产硬化 |

**建议默认推进到阶段 3 再决策**是否开 4a/4b；4a 与 4b 可串行（先 4a 再 4b）或 4b 直接吞并 Plan 产品化。

---

## 2. 分阶段详情

### 阶段 0 — 依赖探针（可选）

| | |
|--|--|
| **分支** | `feature/as-b0-version-bridge` |
| **从** | `{base}` |
| **框架** | BOM/属性试挂 `agentscope-core:2.x`（或 profile）；确认与 Spring AI 1.1 / Boot 3.5 / Reactor·Jackson 冲突面；**行为仍走路径 A** 亦可 |
| **宿主** | 同仓或跟随试编译；可不发版 |
| **验收** | `mvn -pl ace-graph-dsl-agentscope-agent -am compile` 通过；冲突有书面钉版本策略 |
| **合入** | 可合可弃；若冲突 Cleared 直接并进阶段 1 分支 |

---

### 阶段 1 — 路径 B MVP（必做）

| | |
|--|--|
| **分支** | `feature/as-b1-path-b-mvp` |
| **从** | `{base}` 或阶段 0 |
| **框架** | 旁路 `starter-agentscope`；自研 BaseAgent ↔ AS 2.x `ReActAgent`；改 `AgentScopeSubAgentResolver` / `AgentScopeModelFactory`；记忆仍 NONE；单测 + `saa-agentscope-sequential-sql` 类样例 |
| **宿主** | pom 升/钉 AS 2.x；去掉 1.0.9 钉死；能力探测 `agentscopeEnabled`；冒烟图 |
| **不做** | Harness、Subagent、Plan、设计器改字段 |
| **验收** | ① 日志启用 agentscope 模块；② `impl=AGENTSCOPE` 试运行写回约定 outputKey；③ GENERIC_AGENT / 无 AGENTSCOPE 图回归通过 |
| **发布** | 框架 **`1.1.21`** → mesrelease（2026-10-23）；宿主升该版本 |
| **回滚** | 宿主退回钉 1.0.9 + 旧框架；或 feature flag 关 AGENTSCOPE |
| **进度** | 本地 AS SEQUENTIAL 冒烟 + 测试环境业务回归 ✅；PR 合入主线 ☐ |

**Gate → 阶段 2**：阶段 1 **合入主线**后，且缺口 G-MCP / G-STREAM-INNER 按需另开；勿与 H0 同迭代强塞。  
G-MCP 文档：[开发方案](./ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md) / [开发计划](./ACE-Graph-DSL-AgentScope-G-MCP-开发计划.md)（分支 `feature-as-b1-g-mcp`）。  
**进度（2026-10-26）**：G-MCP **能力已验收**（Skill + `translation` MCP 真实调用，`1.1.22`）；待 PR 合入。  
G-STREAM-INNER 文档初稿：[方案](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md) / [计划](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发计划.md)（§0 待拍板；分支 `feature-as-b1-g-stream-inner`）；**勿与 H0 同迭代**。

---

### 阶段 2 — Harness 入口（H0）

| | |
|--|--|
| **分支** | `feature/as-h0-harness-entry`（从已合阶段 1 的 `{base}`） |
| **框架** | 依赖 `agentscope-harness`；Resolver 改为构建 **`HarnessAgent`**（Workspace 最小；**关闭** subagent/plan/memory 产品开关）；保证仍可被 SAA Flow 调用 |
| **宿主** | 依赖传递即可；样例可不变 |
| **不做** | 动态委派、Plan、UI |
| **验收** | 原 AGENTSCOPE 样例图行为不劣化；进程可起；日志标明 `HarnessAgent` |
| **Gate → 阶段 3** | H0 合入且无阻塞缺陷 |

---

### 阶段 3 — 同步 Subagent MVP（H1）**【建议决策点】**

| | |
|--|--|
| **分支** | `feature/as-h1-subagent-mvp` |
| **框架** | `.subagent(...)` 或 workspace 规格；ACE 已注册 Agent → 子规格映射；**同步**委派 + 结果回灌；1 个官方 testdata 样例 |
| **宿主** | 导入样例图、跑通一次委派；文档记「与 ACE 子图边界」 |
| **不做** | 设计器配置面板（可用 JSON/手改样例）；后台 Subagent；现网全量 SSE 子步骤 |
| **验收** | 父节点运行中至少触发 1 次子 Agent Task 且父侧拿到结果；失败有明确日志 |
| **决策** | 业务是否值得继续 Plan / 产品化 → 是则开 4；否则停在 3，主线保留「可演示动态委派」 |

---

### 阶段 4a — Plan Mode（H2，可选）

| | |
|--|--|
| **分支** | `feature/as-h2-plan-mode`（建议从阶段 3 合入后的 `{base}`） |
| **框架** | `enablePlanMode`；最小「规划结束等人」钩子（可先进程内/日志级，不强制现网 resume） |
| **宿主** | 可选：若要现网审批，与 4b 合并做 |
| **验收** | 样例能进入规划态并打印/返回待确认；确认后继续 |
| **说明** | 可与 4b 合并为一个分支 `feature/as-h2h3-plan-and-ui`，避免长期分叉 |

---

### 阶段 4b — ACE 产品化（H3）

| | |
|--|--|
| **分支** | `feature/as-h3-productization` |
| **框架** | Spec/JSON 字段；`ace-graph-dsl-ui` 开关与子 Agent 列表；校验；子步骤观测挂钩 |
| **宿主** | WebFlux **resume** 补齐（若 Plan/HITL 走现网）；SSE 协议扩展；Nacos 样例 Prompt/图 |
| **验收** | 设计器可配 → 发布 → 流式跑通；Plan/审批可 resume；Langfuse/日志可见子步骤 |
| **风险** | 跨仓联调；单独排期，勿与阶段 1 同迭代强塞 |

---

### 阶段 5 — 生产硬化（H4）

| | |
|--|--|
| **分支** | `feature/as-h4-harden` |
| **内容** | 后台 Subagent、超时/取消、failurePolicy、Skill 双栈边界 FAQ、与 ACE 子图职责白皮一小段 |
| **验收** | 压测/失败用例清单；文档与 FAQ 更新 |

---

## 3. 双仓协作节奏

```text
迭代 N:   框架 as-b1  → 发版 → 宿主跟版冒烟
迭代 N+1: 框架 as-h0  → 发版 → 宿主无感或小跟
迭代 N+2: 框架 as-h1  → 发版 → 宿主样例验收 → 【决策会】
迭代 N+3: 框架 as-h2/h3 + 宿主 resume/SSE（可同一迭代双 PR）
```

| 规则 | 说明 |
|------|------|
| 版本 | 每阶段框架至少打 **可引用版本**（或私服 SNAPSHOT 约定）再改宿主 |
| PR | 框架 PR 描述贴本规划「阶段 ID + 验收勾选」 |
| 禁止 | 宿主 PR 直接改框架源码树；禁止阶段 1 未合就开 H3 UI |

---

## 4. 明确不做（本规划范围外）

| 项 | 处理 |
|----|------|
| Memory / Compaction 进 ACE | 搁置；阶段 2+ 保持 NONE / 关 harness memory 产品开关 |
| Boot 4 + SAA 2.0 整栈 | 另立项；本规划保持 Boot 3.5 |
| AgentScope Service Workflow 替换 ACE 图 | 禁止；FAQ 口径不变 |
| 全量 `GENERIC_AGENT` 换成 HarnessAgent | 不做；仅 `impl=AGENTSCOPE` 路径 |

---

## 5. 最小推进路径（若资源紧）

只做 **1 → 2 → 3**，然后停：

| 交付 | 价值 |
|------|------|
| 阶段 1 | AS 2.x 统一、AGENTSCOPE 可跑 |
| 阶段 2 | 为后续 Harness 能力留入口 |
| 阶段 3 | 验证「动态子 Agent」业务是否买单 |

**不默认做 4a/4b/5**，除非决策会通过。

---

## 6. 分支操作备忘（示例）

```bash
# 框架仓
git fetch origin
git checkout {base}
git pull
git checkout -b feature/as-b1-path-b-mvp

# 阶段完成后
# PR → 合 {base} → tag / 发版
# 再: git checkout {base} && git pull && git checkout -b feature/as-h0-harness-entry
```

宿主仓对称：`feature/as-b1-path-b-mvp-host` 仅依赖与样例，**等框架版本号**再提测。

---

## 7. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-21 | 初稿：分阶段分支规划（B0～H4） |
| 2026-10-23 | 阶段 1：`1.1.21` 私服 + 测试环境业务回归；Gate 仍要求 PR 合入主线后再开 H0 |
