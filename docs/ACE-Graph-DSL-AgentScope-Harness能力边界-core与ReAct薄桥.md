# ACE Graph DSL × AgentScope：Harness 能力边界（为何「只引 core + 包 ReAct」不够）

| 项 | 内容 |
|----|------|
| 归属 | **框架侧**（`ace-graph-dsl`）；代码改造主战场见文末 §7 |
| 日期 | 2026-10-21 |
| 宿主镜像 | `lesso-ai-platform-agent-server/docs/AgentScope-Harness能力边界-core与ReAct薄桥.md`（内容同源，便于业务仓查阅） |
| 关联（框架） | [多智能体 FAQ §12 / Q4](./ACE-Graph-DSL-多智能体FAQ与统一口径.md)；[SAA 高阶模式节点设计](./ACE-Graph-DSL-SAA高阶模式节点-开发设计与计划.md)；模块 `ace-graph-dsl-agentscope-agent` |
| 关联（宿主选型） | `lesso-ai-platform-agent-server/docs/ace-graph-agentscope-integration-paths.md`（路径 A/B/C） |
| 官方文档 | [Agent](https://java.agentscope.io/v2/en/docs/building-blocks/agent)、[Quickstart](https://java.agentscope.io/v2/en/docs/quickstart)、[Harness Architecture](https://java.agentscope.io/v2/en/docs/harness/architecture) |
| 结论摘要 | **路径 B 薄桥（`agentscope-core` + `BaseAgent` 包 `ReActAgent`）≠ 文档侧栏 Harness 红框全开** |

---

## 0. 一句话

官方把入口拆成两层：

| 入口 | 主要依赖 | 你得到什么 |
|------|----------|------------|
| **`ReActAgent`** | 主要靠 **`agentscope-core`**（+ `agentscope-extensions-model-*`） | 推理环、工具、Permission、对话上下文等 **核心组件** |
| **`HarnessAgent`** | 推荐 **`agentscope-harness`**（会传递 core） | Workspace / Memory / 压缩 / Skill / Subagent / Sandbox / Session… **整包 Harness** |

文档口径：`HarnessAgent` 包住 `ReActAgent`，加上工作区、长期记忆、上下文压缩、技能、子 Agent、沙箱、会话日志与恢复；多数业务应用从 `HarnessAgent` 起步。`ReActAgent` 适合只要底层引擎、或自行组合上述能力时使用。

因此：**「只引 core + 自研 BaseAgent 包一层 ReAct」只能完整碰到「核心组件」大半；Harness（文档侧栏下框）不要指望自动全能用上。**

`GENERIC_AGENT`（ACE ChatClient 路径）与 `impl=AGENTSCOPE` **可以并存**；选 AGENTSCOPE 且要开 Harness 红框时，按产品定案用 **`HarnessAgent`**，并集成 **`agentscope-harness`**（不能只靠 core）。

---

## 1. 选型文路径 B 实际覆盖什么

路径 B（见宿主 `ace-graph-agentscope-integration-paths.md`）典型形态：

```text
旁路 / 去掉 spring-ai-alibaba-starter-agentscope（钉 AS 1.0.9）
→ 依赖 agentscope-core:2.x（+ model 扩展；勿默认引 agentscope-spring-boot-starter）
→ 自研 BaseAgent 适配器，内部调 ReActAgent / call
→ 改 AgentScopeSubAgentResolver + 模型工厂（模块 ace-graph-dsl-agentscope-agent）
→ 进 SAA FlowAgent / ACE impl=AGENTSCOPE
```

这保证的是：

- 同 jar **版本统一到 AS 2.x**
- 图上 **仍能挂 `impl=AGENTSCOPE` 子节点**
- 与官方 `starter-agentscope` 2.0 **缺失**时的自研桥需求对齐

**不保证**：侧栏 Harness 红框在 ACE 路径上可用。

另：`agentscope-spring-boot-starter:2.x` 钉 **Boot 4.0.x autoconfigure**，是 AS **自家 Boot 装配**，**不是** SAA `BaseAgent` 桥。Boot 3.5 宿主应引 **core（+ harness 若产品化）**。

---

## 2. 对照文档两块能力（核心组件 vs Harness）

### 2.1 核心组件（上框）— 库能力大致齐；薄 ReAct 桥：部分可用

| 能力 | 在 core / ReAct 层？ | 仅薄 BaseAgent（包 ReAct） |
|------|----------------------|----------------------------|
| Agent（智能体） | ✅ | ✅（桥要正确转发 call / stream） |
| 消息与事件 | ✅ | ✅ |
| Middleware | ✅ | ✅（建 Agent 时挂载） |
| Model | ✅（常配合 extensions-model-*） | ✅ |
| Permission System | ✅（ReAct 层） | 🟡 须在建 Agent 时配置并接到 ACE |
| Tool | ✅ | ✅ |
| 上下文与 AgentState | ✅ | ✅ |

→ **上框：路径 B MVP 可以碰到大半。**

### 2.2 Harness（下框）— 别指望「只引 core + 包 ReAct」

| 能力 | 主要所在 | 薄 ReAct 桥 | 换成 HarnessAgent + ACE 产品化后 |
|------|----------|-------------|------------------------------|
| 上下文管理（压缩 / 大工具结果卸载等） | Harness | ❌ | ✅ 可开（**进 ACE 的 Memory/Compaction 产品化已搁置**） |
| 工作区 Workspace | Harness | ❌ | ✅ |
| 会话操作、事件与恢复 | Harness / Session | ❌ / 弱 | ✅（还须与 ACE checkpoint / resume 对齐） |
| 记忆 Memory | Harness | ❌（现 ACE 子记忆仍强制 **NONE** + `InMemoryMemory`） | 库侧 ✅；**进 ACE 已搁置** |
| 文件系统 Filesystem | Harness | ❌ | ✅ |
| 沙箱 Sandbox | Harness | ❌ | ✅（依赖 Docker 等） |
| 子 Agent Subagent | Harness | ❌ | 🟡 与 **ACE 图 / 子图** 须定谁主编排 |
| 技能 Skill | Harness | ❌ | 🟡 与 lesso Skill / Nacos 两套边界 |
| 计划模式 Plan Mode | Harness | ❌ | 🟡 HITL 须接到 ACE resume |
| Channel | Harness 周边 | ❌ | 🟡 与现网 SSE / 会话模型对齐 |

→ **下框：要 `agentscope-harness` + `HarnessAgent` + ACE 产品化，才谈「能用上」。**

改动量细估见：[ACE-Graph-DSL-AgentScope-动态规划与Harness子Agent改动量评估.md](./ACE-Graph-DSL-AgentScope-动态规划与Harness子Agent改动量评估.md)。

---

## 3. 若目标是「Harness 红框」——正确工作包

```text
① 路径 B（版本桥）—— 主改 ace-graph-dsl-agentscope-agent
② 依赖 agentscope-harness
③ 桥内构造 HarnessAgent（按需打开 workspace / subagent / planMode …）
④ ACE 产品化：Spec/UI、conversationId、SSE、interrupt/resume、与宿主协议
```

| 切片 | 说明 |
|------|------|
| 仅路径 B | Harness 红框几乎不动；只解决版本与子节点可跑 |
| + Harness 库与 HarnessAgent | 库能力齐，≠ 设计器 / 生产图可用 |
| + ACE 产品化 | 红框按优先级打开；Memory/Compaction **搁置** |
| Subagent / Skill / Channel | 与 ACE / lesso **抢职责**，须显式选型 |

---

## 4. 与 ACE 产品口径的硬约束

1. **主编排仍是 ACE 图**；禁止 AS Pipeline / Service Workflow **替换** ACE 边（FAQ Q4）。
2. 当前 `impl=AGENTSCOPE`：子记忆强制 **NONE**（Q5），见模块 README / 宿主 `ace-graph-agentscope-host.md`。
3. AS **Service** Team / Workflow = 另一套平台编排，不是本模块交付物。
4. Boot 3.5：用 core / harness **库 API**；勿默认 `agentscope-spring-boot-starter`。

---

## 5. 决策用对照表

| 诉求 | 该怎么做 |
|------|----------|
| AGENTSCOPE 子节点、BOM 统一 AS 2.x | 路径 B 薄桥即可立项 |
| Harness 下框进 ACE | B + **harness + HarnessAgent** + **ACE 产品化** |
| 长期记忆 / 压缩进 ACE | **已搁置** |
| 平台级 Team / Workflow | 另评 AgentScope Service；不替代 ace-graph-dsl |

---

## 6. 代码改造重心（框架 vs 宿主）

| 侧 | 占比 | 主改模块 / 动作 |
|----|------|-----------------|
| **ace-graph-dsl（框架）** | **约 70%～85%** | `ace-graph-dsl-agentscope-agent`（桥 / Harness / Subagent）；必要时 `saa-agent`、Spec；H3 时 `ace-graph-dsl-ui` |
| **业务宿主** | **约 15%～30%** | pom 依赖、旁路 starter、样例图、冒烟；H3 时 resume / SSE 现网协议 |

文档可在业务仓与框架仓各存一份；**实现以框架侧为主**。

---

## 7. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-21 | 框架侧落盘（与宿主 docs 同源）；补充归属与改造重心 |
