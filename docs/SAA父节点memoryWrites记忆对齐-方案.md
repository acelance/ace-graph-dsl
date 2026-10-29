# SAA 父节点 memoryWrites 记忆对齐方案

| 项 | 内容 |
|----|------|
| 状态 | 方案待评审（未开工） |
| 日期 | 2026-10-29 |
| 范围 | `ace-graph-dsl-saa-agent` + 记忆 SPI（Lesso Provider / ChatMemory）+ 设计器字段 |
| 相关约定 | `.cursor/rules/ace-graph-memory-persist.mdc`（按节点写意图即时落盘；**不对齐** Vertical「仅图尾 persist」时机） |
| 典型图 | `bank-interest-fee-verifier-agentscope-graph`（ROUTING + AGENTSCOPE 子 Agent + 可选 `ls_out_put`） |

---

## 1. 背景与问题

### 1.1 现象

- **高阶多智能体（SAA）父节点**无 `memoryMode` / `memoryWrites` 配置。
- **AGENTSCOPE 子 Agent** 框架要求不可 effective `READ_WRITE`（Q5），现网多为 `memoryMode=NONE`，且**不走** ChatClient Memory Advisor。
- 用户可见 ASSISTANT 落库，现网依赖后置 **Generic 出口节点**（如 `agent_ls_out_put_node`）的 `WRITE_ASSISTANT_MAIN_TEXT`。
- 若 SAA 已是业务终态（`agent_result` 已是对用户结论），再强制挂一个 **会调 LLM 的出口 Agent** 做落盘：贵、慢、还可能改写数字；且「持久化」被误绑成「再生成」。

### 1.2 根因（链路）

```
图 NodeAction
  → FlowAgentNodeAction.apply
  → SAA Agent.invoke(seed)          // 编排链，非 ChatClient Advisor 环
  → BaseAgent（GENERIC 或 AGENTSCOPE）
  → 写回 state[outputKey]
  ✗ 此处无 memoryWrites → 无 remote ASSISTANT
```

| 路径 | 记忆落盘 |
|------|----------|
| Generic + `StreamingLlmTemplate` + ChatClient | `LessoOrderedMessageChatMemoryAdvisor`（before/after）按 `memoryWrites` |
| SAA 父 + AS 子 | **无** Advisor；SSE/Thinking Buffer 可有展示，不等于 remote ASSISTANT |
| `ls_out_put`（Generic） | `WRITE_ASSISTANT_MAIN_TEXT` → after 写 ASSISTANT |

**结论：** 缺的是 SAA **父节点级写意图挂点 + 节点完成时落盘**，不是「再套一层 Spring AI Advisor 到 FlowAgent 外层」。

---

## 2. 设计原则

1. **角色语义对齐 Vertical**：USER = 真实用户话；ASSISTANT = 对用户可见答复；思考进 extras / Buffer。
2. **落盘时机对齐 ace-graph**：按节点 `memoryWrites` **即时**生效；禁止为「对齐 Vertical」改成只在图尾 persist。
3. **子 Agent 策略不变**：AS 子 Agent 继续禁止 effective `READ_WRITE`（Q5）；子节点不各自写 MAIN，避免多气泡。
4. **持久化 ≠ 再调模型**：落盘是 Harness 职责；LLM 出口仅作脱敏/润色**可选项**。
5. **MAIN 与 THINKING 合并**：仅 `WRITE_ASSISTANT_MAIN_TEXT` 时 drain Thinking Buffer，合成 **1** 条 ASSISTANT（与现网 Advisor after 语义一致）。
6. **出口勿写假 USER**：若 SAA 配了 `WRITE_USER`，行为须与 Provider 对 out_put 的剥离策略一致或文档明确禁止。

---

## 3. 目标与非目标

### 3.1 目标

- SAA 父 Spec / 设计器支持 **`memoryWrites`**（至少：`WRITE_USER`、`WRITE_ASSISTANT_THINKING`、`WRITE_ASSISTANT_MAIN_TEXT`）。
- `FlowAgent.invoke` **成功返回后**，按 flags 对 `conversationId` 执行落盘：
  - `WRITE_ASSISTANT_MAIN_TEXT`：用父 **`outputKey` 对应文本**（如 `agent_result`）作为 ASSISTANT `content`，drain Buffer → `thinking_content`。
  - `WRITE_USER`：按现有 SPI（DisplayUserText / PersistMetadata）写 USER（若本图已在更早节点写过 USER，须可关、可去重策略见 §7）。
  - 仅 `WRITE_ASSISTANT_THINKING`：不 drain、不 add ASSISTANT（与 Advisor 一致）。
- SAA 可作为图**最后一个业务节点**，**无需**为落盘再挂 LLM 出口。
- 现有 `ls_out_put` 图可继续使用；迁移为可选。

### 3.2 非目标（本方案不做）

- 不把 Vertical「图尾统一 persist」搬进 ace-graph。
- 不取消 Q5、不让每个 AS 子 Agent 写 remote ASSISTANT。
- 不强制 SAA 父节点走 `ChatClient` + 挂 `LessoOrderedMessageChatMemoryAdvisor`（见 §5 澄清）。
- 不在本方案实现完整「规则脱敏引擎」（脱敏仍可用可选 LLM/规则出口）。

---

## 4. 推荐方案：节点完成落盘钩子（语义对齐 Advisor，路径不共用 ChatClient）

### 4.1 一句话

**复用 `memoryWrites` 契约与 ChatMemory / Buffer 语义；在 `FlowAgentNodeAction`（或等价钩子）于 `invoke` 成功后执行与 Advisor `after`/`before` 等价的落盘，而不是给 FlowAgent 套 ChatClient Advisor 环。**

### 4.2 为何不直接挂 `LessoOrderedMessageChatMemoryAdvisor`

| 点 | 说明 |
|----|------|
| Advisor 绑定对象 | Spring AI **`ChatClient` 单次 call/stream** 的 before/after |
| SAA 终态来源 | **`Agent.invoke` 后的 state 文本**，父节点通常没有「终态正文」那一次 ChatClient |
| AS 子调用 | AgentScope ReAct，不经过该 Advisor |
| 硬套 Advisor | 需 echo/假 ChatClient，成本高、语义别扭 |

因此：

- **对齐的是 flag 语义 + `ChatMemory.add` + Thinking Buffer drain**；
- **不是**「SAA = 再走一遍 Ordered Advisor 环」。

### 4.3 流程（推荐）

```
FlowAgentNodeAction.apply
  1. seed = buildSeedInput(state)
  2. 若含 WRITE_USER → PersistUserHook（可选；见 §7 去重）
  3. flowAgent.invoke(seed)
  4. 组装 out（含 parentOutputKey）
  5. 若含 WRITE_ASSISTANT_MAIN_TEXT
       → text = unwrap(parentOutputKey 值)
       → thinking = SessionThinkingBuffer.drain(conversationId)
       → ChatMemory.add(ASSISTANT(content=text, thinking=...))
  6. 若仅 THINKING → 不 drain、不 add
  7. return out
```

日志（必打）：

- `SAA memory persist: nodeId=..., flags=..., writeUser=..., writeAssistant=..., contentChars=..., thinkingChars=..., conversationId=...`
- 跳过原因：`NONE` / 无 conversationId / 正文为空 / 开关关闭。

### 4.4 与「可选 LLM 出口」关系

| 图形态 | 配置建议 |
|--------|----------|
| SAA 终态已可展示、无需脱敏 | SAA：`WRITE_ASSISTANT_MAIN_TEXT`；**可不挂** `ls_out_put` |
| 需要脱敏 / 润色 / OUTPUT 帧 | 保留 `ls_out_put`；SAA **不要**再写 MAIN（避免两条 ASSISTANT）；出口继续 `WRITE_ASSISTANT_MAIN_TEXT` |
| 思考在 SAA/子 Agent SSE | SAA 或出口二选一写 MAIN 并 drain；**禁止**两处都 `writeAssistant=true` |

---

## 5. 备选方案（对比后不优先）

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| **A. 节点完成钩子（推荐）** | invoke 后按 flags + outputKey 落盘 | 无额外 LLM；对齐写意图；SAA 可作图尾 | 需新 Spec 字段与 SPI | **采用** |
| B. 无模型透传节点 | 图尾加「只落盘不调 LLM」节点 | 改 SAA 少 | 仍多一个图节点；设计器心智差 | 可作过渡 |
| C. 保留 LLM `ls_out_put` 兜底 | 现状 | 零开发 | 贵、慢、易改写；SAA 不能当真正图尾 | 仅作增强项 |
| D. echo + 真挂 Ordered Advisor | invoke 后假 ChatClient 触发 after | 复用 Advisor 代码路径 | 造假调用、脆弱 | **不采用** |
| E. 图尾统一 persist | 运行时图结束扫 state | 实现省事 | 违反 ace-graph 落盘约定 | **禁止** |

---

## 6. Spec / API 变更

### 6.1 `SaaWorkflowSpec` 增补

建议字段（与 `GenericAgentSpec` 对齐风格）：

```text
List<String> memoryWrites   // null=不参与写；[]=明确不写；非空=以 flags 为准
MemoryMode memoryMode       // 可选兼容；有 memoryWrites 时以 writes 为准（同 Generic 规则）
```

`effectiveMemoryWrites()` / `effectiveMemoryMode()`：复用 `MemoryWriteFlag.resolveFlexible` 规则，避免两套解析。

JSON 示例：

```json
{
  "pattern": "ROUTING",
  "inputKeys": "user_query",
  "outputKey": "agent_result",
  "memoryWrites": ["WRITE_ASSISTANT_MAIN_TEXT"],
  "subAgents": [ ... ]
}
```

### 6.2 设计器

- 高阶多智能体节点「对话记忆」区块：与 Generic 同款 **写图记忆 memoryWrites** 多选 / 「以写意图为准」。
- 文案说明：
  - MAIN 使用本节点 **outputKey** 文本，**不**再调模型；
  - 与下游 `ls_out_put` 同时开 MAIN 会双气泡，需互斥提示。

### 6.3 校验

- `SaaWorkflowValidator`：允许父节点任意 `memoryWrites`；**子 Agent** 仍禁止 effective `READ_WRITE`（Q5 不变）。
- 可选 WARN：父与图内下一跳 out_put 均含 `WRITE_ASSISTANT_MAIN_TEXT`。

---

## 7. 实现落点（模块）

| 层级 | 模块 / 类 | 职责 |
|------|-----------|------|
| Spec | `ace-graph-dsl-core`：`SaaWorkflowSpec` | 字段 + effective 解析 |
| 执行 | `ace-graph-dsl-saa-agent`：`FlowAgentNodeAction`（或抽 `SaaMemoryPersistSupport`） | invoke 后按 flags 落盘 |
| SPI | 新建中性接口（建议在 `ace-graph-dsl-ai` 或 core）如 `GraphNodeMemoryPersister` | `persistUser` / `persistAssistantMain`；框架不写死 Lesso extras 键名 |
| Lesso | `lesso-ai-platform-agent-server`：实现 Persister，委托现有 `ChatMemory` + `LessoSessionThinkingBuffer.drain` + Display/PersistMetadata SPI | 与 Ordered Advisor after 行为对齐 |
| 装配 | Factory 把 Spec.memoryWrites、conversationId 解析键传入 NodeAction | 与 Generic 同源取 conversationId |
| 设计器 / UI schema | graph-dsl-ui / designer | 暴露 memoryWrites |

### 7.1 WRITE_USER 与同图其它节点（配置纪律，不做自动去重）

同一图内多个节点都勾选 `WRITE_USER` 会导致 **重复 USER 气泡**——这是**配置问题**，不是框架运行时自动去重场景。

- **实现**：**不**做 `skipIfLastMessageIsUser` 等隐式跳过；SAA 按自身 `memoryWrites` 如实落盘。
- **责任**：由用户 / 图作者自查——全图通常只应有 **一处** 配 `WRITE_USER`（入口或 SAA 等，按业务选型）。
- **文档 / 设计器**：可在说明里提示「勿与其它节点重复勾选 WRITE_USER」；不做强制拦截（除非后续单独做静态校验 WARN，仍属可选）。

### 7.2 正文为空

`outputKey` 空串 / null：打 WARN，**不** add 空 ASSISTANT（与 Advisor after 无 Generation 跳过类似）。

### 7.3 conversationId 缺失

无 conversationId：打 WARN，跳过落盘（与现网 Advisor 参与条件一致）。

---

## 8. 利费图（agentscope）迁移建议

**现状：** `START → SAA(ROUTING) → ls_out_put → END`，落盘在出口。

**迁移选项：**

| 选项 | 做法 | 适用 |
|------|------|------|
| M1 最小 | SAA 增加 `WRITE_ASSISTANT_MAIN_TEXT`；去掉 `ls_out_put` 边 | 不需要出口脱敏时 |
| M2 保留脱敏 | SAA **不写 MAIN**；继续 `ls_out_put` | 现网脱敏/OUTPUT 帧仍要 |
| M3 混合 | SAA 写 MAIN；`ls_out_put` 改为无记忆、仅展示（一般不推荐，易重复） | 避免 |

推荐：**先落地框架能力（本方案）→ 利费图按是否还要 LLM 脱敏在 M1/M2 间选**；默认文档示例用 M1 证明「SAA 可作图尾」。

---

## 9. 测试与验收

### 9.1 单测 / 集成

1. SAA Spec：`memoryWrites` 解析 / effective 与 Generic 一致。  
2. `FlowAgentNodeAction`：mock Persister；含 MAIN 时 invoke 后调用一次 `persistAssistantMain`，参数为 outputKey 文本。  
3. 仅 THINKING：不调用 persistAssistant。  
4. 无 conversationId / 空正文：不落盘 + WARN。  
5. Q5：子 Agent `READ_WRITE` 仍失败。  

### 9.2 联调验收（agentscope 利费）

1. SAA 配 MAIN、去掉出口：一轮 remote **1 USER（若有）+ 1 ASSISTANT**，ASSISTANT content≈`agent_result`。  
2. 日志出现 `SAA memory persist ... writeAssistant=true`。  
3. 深度思考开启时 ASSISTANT 带 thinking_content（drain 成功）。  
4. 与 `ls_out_put` 同时 MAIN：出现双 ASSISTANT 或校验 WARN（按选定策略断言）。  

---

## 10. 分期

| 阶段 | 交付 | 估计 |
|------|------|------|
| P0 | Spec + FlowAgentNodeAction 钩子 + Lesso Persister + 单测 | 核心 |
| P1 | 设计器字段 + 校验 WARN + 样例图/文档 | 可用 |
| P2 | 利费图迁移（M1 或 M2）+ 回归 SSE/记忆 | 业务 |

---

## 11. 决策摘要（供评审勾选）

- [ ] 采用 **方案 A：节点完成落盘钩子**（不硬挂 ChatClient Advisor）。
- [ ] SAA 父支持 `memoryWrites`；子 Agent Q5 不变。
- [ ] MAIN 正文来源 = 父 **`outputKey`** 文本。
- [ ] 同图多节点 `WRITE_USER` 由用户配置自查，框架**不做**自动去重。
- [ ] `ls_out_put` 降级为脱敏/润色可选，不再是记忆唯一兜底。
- [ ] **禁止** 图尾统一 persist 替代节点写意图。

---

## 12. 参考

- `.cursor/rules/ace-graph-memory-persist.mdc`
- `MemoryWriteFlag` / `GenericAgentSpec.effectiveMemoryWrites`
- `LessoOrderedMessageChatMemoryAdvisor`（before USER / after ASSISTANT 语义参照）
- `LessoAceGraphChatMemoryAdvisorProvider`
- `FlowAgentNodeAction`
- `docs/troubleshooting/2026-10-15-bank-fee-followup-memory-and-llm-latency.md`
- `nacos-config-sample/prompt-ls-out-put-node-v0.0.1.md`
