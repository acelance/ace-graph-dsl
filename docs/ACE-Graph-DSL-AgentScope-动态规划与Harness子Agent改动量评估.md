# ACE Graph DSL × AgentScope：动态规划 / Harness 子 Agent 改动量评估

| 项 | 内容 |
|----|------|
| 归属 | **框架侧**（`ace-graph-dsl`）；**代码改造主战场在本仓** |
| 日期 | 2026-10-21 |
| 评估对象 | 在 ACE 路径上开启 AS **Plan Mode** 与 **Harness Subagent**（动态委派）的工程改动量 |
| 宿主镜像 | `lesso-ai-platform-agent-server/docs/AgentScope-动态规划与Harness子Agent改动量评估.md` |
| 关联 | [Harness 能力边界](./ACE-Graph-DSL-AgentScope-Harness能力边界-core与ReAct薄桥.md)；[分阶段分支规划](./ACE-Graph-DSL-AgentScope-路径B与Harness分阶段分支规划.md)；宿主 `ace-graph-agentscope-integration-paths.md`；模块 `ace-graph-dsl-agentscope-agent` |
| 结论摘要 | **不算小改**。路径 B 之后：库内演示约 **+1～2 周**；接设计器 / HITL / 观测约 **再 +2～4 周**。整包偏「中大改」。主改框架，宿主辅。 |

---

## 0. 一句话结论

- **薄路径 B（只包 `ReActAgent`）用不上** 文档里的动态规划 / Harness 子 Agent。
- 要开这两项：须 **`agentscope-harness` + `HarnessAgent`**，并在 ACE 侧接线。
- **改动量：中等偏多 → 明显偏多**（视是否做满产品化）。
- **实现重点**：`ace-graph-dsl`（尤其 `ace-graph-dsl-agentscope-agent` / 必要时 UI）；宿主做依赖与验收。
- 若目标只是「图上固定拆几个子节点」→ 优先 **ACE 子图 / SAA SEQUENTIAL**，通常比 Harness 动态 Subagent **便宜一个数量级**。

---

## 1. 代码改造分布（框架 vs 宿主）

| 侧 | 占比（观感） | 主要改什么 |
|----|--------------|------------|
| **ace-graph-dsl** | **约 70%～85%** | Resolver 升 HarnessAgent；harness 依赖；Subagent/Plan 接线；Spec/校验；H3 时 `ace-graph-dsl-ui` |
| **platform-agent-server 等宿主** | **约 15%～30%** | pom、旁路 starter、样例图、冒烟；H3 时 resume / SSE 现网协议 |

`GENERIC_AGENT` 业务图（利费等）**不必**为动态规划整批改写。

---

## 2. 前置（必付）

| 前置 | 估时（熟悉本仓 1 人） | 主改位置 |
|------|----------------------|----------|
| **路径 B**：`agentscope-core` 2.x + 自研 BaseAgent 桥 + Resolver / 模型工厂 | **约 10～17 人日** | **框架** `ace-graph-dsl-agentscope-agent`；宿主改 pom |

没有路径 B，后续切片均免谈。现状：`AgentScopeSubAgentResolver` 仍为 `ReActAgent` + 官方 `AgentScopeAgent`（钉 1.0.9 链路）。

---

## 3. 目标能力拆开估时

| 切片 | 改什么 | 人日（粗） | 主改侧 | 观感 |
|------|--------|------------|--------|------|
| **H0 库切换** | 依赖 `agentscope-harness`；Resolver 构建 **`HarnessAgent`**；最小 Workspace；SAA SEQUENTIAL 冒烟 | **3～6** | 框架 | 中 |
| **H1 子 Agent MVP** | `.subagent(...)` / `workspace/subagents/`；ACE 注册 Agent → 子规格；同步委派回结果 | **5～10** | 框架 | **中偏多** |
| **H2 Plan Mode MVP** | `.enablePlanMode()`；只读规划 → 人确认 | **2～4**（库内） | 框架 | 中 |
| **H3 ACE 产品化** | Spec/JSON；设计器；Plan ↔ `interrupt`/`resume`；子步骤 SSE/Langfuse | **8～15** | **框架 UI + API**；宿主 resume/SSE | **多** |
| **H4 生产硬化** | 后台 Subagent、与 ACE 子图职责、Skill 双栈、超时/取消 | **5～12** | 框架为主 | 多 |

### 3.1 叠加粗算（路径 B **之后**）

| 交付档 | 合计人日 | 观感 |
|--------|----------|------|
| **能演示**：H0 + H1 | **约 8～16** | 中等 |
| **+ Plan 可演示**：+H2 | **约 10～20** | 中偏多 |
| **可配置上线雏形**：+H3 | **约 18～35** | **明显偏多** |
| **生产可用**：+H4 | **约 25～45+** | 大 |

再算路径 B：**B + 动态规划/子 Agent 产品化 ≈ 1.5～3 个月（1 人）**。

> 宿主选型文「Harness 另开 +1～3 周」偏乐观，**大致只够 H0～H2 库内演示**，盖不住 H3。

---

## 4. 为何「多」——相对现状

当前链路：

```text
ACE GenericAgentSpec
  → ReActAgent（InMemoryMemory + 记忆 NONE）
  → 包装为 SAA AgentScopeAgent
```

增量：

1. 入口 ReAct → **Harness**（依赖、Builder、Workspace）——**框架**
2. 子 Agent 与 ACE 已注册 Agent 映射——**框架**
3. Plan HITL × 宿主 resume 弱——**框架 + 宿主**
4. 子步骤是否进 SSE——**框架事件 + 宿主协议**
5. Q5 记忆 NONE vs Harness 默认记忆——产品规则（Memory 进 ACE **已搁置**）

### 4.1 不必动

- 大部分 **`GENERIC_AGENT`** 业务节点
- ACE 边主编排（禁止 AS Pipeline 换边）

---

## 5. 与「薄桥 MVP」对照

| 能力 | 薄 ReAct 路径 B MVP | HarnessAgent + 产品化 |
|------|---------------------|------------------------|
| Plan Mode | ❌ | ✅（H2 + 可选 H3） |
| Harness Subagent | ❌ | ✅（H1 + 可选 H3/H4） |
| 固定多子节点流水线 | ACE 子图 / SAA SEQUENTIAL | 不必为「动态」上 Harness |

---

## 6. 建议切法

1. **先路径 B**（框架 agentscope-agent + 宿主 pom）。
2. **再 H0 + H1**：1 个样例「父 Harness + 1～2 同步子 Agent」——验证值不值得做满。
3. Plan / 设计器 / resume **单独立项**（H2/H3）。
4. Memory/Compaction 进 ACE **继续搁置**。
5. 需求可写成「固定拆分」→ **优先 ACE/SAA**，不上动态 Subagent。

---

## 7. 决策速查

| 诉求 | 建议 |
|------|------|
| AGENTSCOPE 能跑、AS 2.x 统一 | 路径 B；**不做** H1/H2 |
| 演示动态委派 | B + H0 + H1（框架为主，约再 +1～2 周） |
| 演示 Plan + 人确认 | +H2；现网 resume 算 H3 |
| 设计器可配、可观测、上生产 | 必须 H3（+ 视情况 H4） |
| 只要固定流水线 | **不要**为本评估付动态规划成本 |

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-21 | 框架侧落盘（与宿主 docs 同源）；强调改造重心在 ace-graph-dsl |
