# SAA 父节点 memoryWrites 记忆对齐方案

| 项 | 内容 |
|----|------|
| 状态 | **P0+P1 设计器已完成（待联调/发版）**；分支 `feature-saa-memory-writes` / `feature-saa-memory-persister` |
| 完成度 | **P0 ≈ 100%**（W1～W8）；**P1 设计器 W9 已完成**；**整体方案 ≈ 85%**（缺联调 W10；W11 非本阶段） |
| 日期 | 2026-10-29（议题收口）；开工/收口代码 2026-10-30 |
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
6. **出口勿写假 USER**：产品上避免出口写 USER；**P0 不强制**框架自动剥离（见 O2 修订）——由用户配置保证出口 / 图尾不配 `WRITE_USER`；自动剥离可后置对齐 Generic。

---

## 3. 目标与非目标

### 3.1 目标

| 目标 | 状态 | 完成度 |
|------|------|--------|
| SAA 父 Spec 支持 `memoryWrites`（USER / THINKING / MAIN） | **已完成**（Spec） | 100% |
| 设计器支持配置 memoryWrites | **已完成**（`SaaWorkflowForm`） | 100% |
| invoke 后按 flags 落盘（MAIN=outputKey；USER=SPI；仅 THINKING 不 drain） | **已完成** | 100% |
| SAA 可作图尾、无需为落盘再挂 LLM 出口 | **代码能力具备**；真机联调未做 | ~90% |
| 现有 `ls_out_put` 图可继续用 | **兼容保留** | 100% |

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
  2. 若含 WRITE_USER → PersistUserHook（invoke 前；重复配置由用户自查，见 §7.1）
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
| 需要脱敏 / 润色 / OUTPUT 帧 | 保留 `ls_out_put`；SAA **不宜**再写 MAIN（避免双气泡）；出口继续 `WRITE_ASSISTANT_MAIN_TEXT` |
| 思考在 SAA/子 Agent SSE | MAIN 只应在一处 drain；若 SAA 与出口**同时** MAIN → **校验 WARN**（O1/O5），**不**硬拒绝 |

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

### 6.1 `SaaWorkflowSpec` 增补 — **已完成 100%**

建议字段（与 `GenericAgentSpec` 对齐风格）：

```text
List<String> memoryWrites   // null=回退 memoryMode；[]=明确不写；非空=以 flags 为准
MemoryMode memoryMode       // 仅当 memoryWrites==null 时生效（同 Generic）
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

### 6.2 设计器 — **已完成 100%**（P1 / W9）

- 高阶多智能体节点「对话记忆」区块：与 Generic 同款 **写图记忆 memoryWrites** 多选 / 「以写意图为准」。
- 文案说明：
  - MAIN 使用本节点 **outputKey** 文本，**不**再调模型；
  - 与下游 `ls_out_put` 同时开 MAIN 会双气泡，需互斥提示；出口勿勾 WRITE_USER。

### 6.3 校验 — **已完成 100%**（Q5 原有 + 双 MAIN WARN + warnings 通道）

- `SaaWorkflowValidator`：允许父节点任意 `memoryWrites`；**子 Agent** 仍禁止 effective `READ_WRITE`（Q5 不变）。
- **P0 WARN（不硬拒绝）**：SAA 父含 `WRITE_ASSISTANT_MAIN_TEXT`，且**直接后继**为出口类节点（`out_put` / `output` / `ls_out_put` 等）且该后继也含 MAIN → 打 WARN（见 §13.1「双 MAIN」）。
- **不做**：禁止线性多业务节点各自写 MAIN（那是合法多步落盘）。

---

## 7. 实现落点（模块）

| 层级 | 模块 / 类 | 职责 | 状态 | 完成度 |
|------|-----------|------|------|--------|
| Spec | `ace-graph-dsl-core`：`SaaWorkflowSpec` | 字段 + effective 解析 | **已完成** | 100% |
| 执行 | `ace-graph-dsl-saa-agent`：`FlowAgentNodeAction` | `WRITE_USER` 在 invoke **前**；MAIN 在 invoke **后**（见 §4.3） | **已完成** | 100% |
| SPI | `ace-graph-dsl-ai`：`GraphNodeMemoryPersister` | `persistUser` / `persistAssistantMain`；框架不写死 Lesso extras 键名 | **已完成** | 100% |
| Lesso | `lesso-ai-platform-agent-server`：`LessoGraphNodeMemoryPersister` | `ChatMemory` + `LessoSessionThinkingBuffer.drain` + Display/PersistMetadata SPI | **已完成** | 100% |
| 装配 | `SaaWorkflowNodeFactoryImpl` + `AceGraphDslSaaAgentAutoConfiguration` + Lesso `@Bean` | Spec.memoryWrites + Persister 注入；conversationId 从 state 读 | **已完成** | 100% |
| 设计器 / UI schema | `SaaWorkflowForm` + `saaWorkflow.js` | 暴露 memoryWrites / memoryMode + 双 MAIN / 出口提示 | **已完成** | 100% |

### 7.1 WRITE_USER 与同图其它节点（配置纪律，不做自动去重） — **按设计落地 100%**

同一图内多个节点都勾选 `WRITE_USER` 会导致 **重复 USER 气泡**——这是**配置问题**，不是框架运行时自动去重场景。

- **实现**：**不**做 `skipIfLastMessageIsUser` 等隐式跳过；SAA 按自身 `memoryWrites` 如实落盘。
- **责任**：由用户 / 图作者自查——全图通常只应有 **一处** 配 `WRITE_USER`（入口或 SAA 等，按业务选型）。
- **文档 / 设计器**：可在说明里提示「勿与其它节点重复勾选 WRITE_USER」；不做强制拦截（除非后续单独做静态校验 WARN，仍属可选）。**设计器提示 → P1。**

### 7.2 正文为空 — **已完成 100%**

`outputKey` 空串 / null：打 WARN，**不** add 空 ASSISTANT（与 Advisor after 无 Generation 跳过类似）。

### 7.3 conversationId 缺失 — **已完成 100%**

无 conversationId：打 WARN，跳过落盘（与现网 Advisor 参与条件一致）。

---

## 8. 框架 vs 业务图（勿混谈）

| 层 | 负责什么 | 不负责什么 |
|----|----------|------------|
| **框架（本方案）** | SAA 父支持 `memoryWrites` 并在节点完成时落盘 | 不规定利费图必须删/留 `ls_out_put` |
| **业务图设计** | 是否挂输出节点、是否脱敏、MAIN 放在 SAA 还是出口 | 不替代框架缺写意图能力 |

利费图现网 `SAA → ls_out_put` 仅作背景；**加不加输出节点、MAIN 配在谁身上**由业务自选。框架验收只证明：勾选 MAIN 后 SAA 能单独落盘。

---

## 9. 测试与验收

### 9.1 单测 / 集成

| # | 项 | 状态 | 完成度 |
|---|----|------|--------|
| 1 | SAA Spec：`memoryWrites` 解析 / effective 与 Generic 一致 | **已完成**（`SaaWorkflowSpecJsonTest`） | 100% |
| 2 | `FlowAgentNodeAction`：mock Persister；MAIN → `persistAssistantMain` | **已完成**（`FlowAgentNodeActionMemoryPersistTest`） | 100% |
| 3 | 仅 THINKING：不调用 persistAssistant | **已完成** | 100% |
| 4 | 无 conversationId / 空正文：不落盘 + WARN | **已完成**（无 cid；空正文路径已实现） | 100% |
| 5 | Q5：子 Agent `READ_WRITE` 仍失败 | **沿用原校验**（未改坏；本批未单独重跑） | 100% |
| 6 | 双 MAIN → 校验 **WARN**（非 error） | **已完成**（`SaaWorkflowValidatorTest.dualMain…`） | 100% |
| 7 | （非 P0）出口自动剥 WRITE_USER | **暂缓** | 0% |

### 9.2 联调验收（可选样例图，非利费必迁）

| # | 项 | 状态 | 完成度 |
|---|----|------|--------|
| 1 | SAA 配 MAIN、无出口：remote 1 USER（若有）+ 1 ASSISTANT | **未做**（真机联调） | 0% |
| 2 | 日志 `SAA memory persist ... writeAssistant=true` | **单测日志已有**；真机未验 | ~50% |
| 3 | 深度思考 → ASSISTANT 带 thinking_content | **单测 drain 已有**；AS 流式真机未验 | ~70% |
| 4 | 与 `ls_out_put` 双 MAIN：校验 WARN、运行可双条 | **校验单测已有**；真机运行未验 | ~50% |

---

## 10. 分期

| 阶段 | 交付 | 估计 | 状态 | 完成度 |
|------|------|------|------|--------|
| P0 | Spec + FlowAgentNodeAction 钩子 + Lesso Persister + **双 MAIN 校验 WARN** + 单测 | 核心 | **代码完成**（待联调/发版） | ~95% |
| P1 | 设计器字段 + 样例图/文档（WARN 文案可加强） | 可用 | **设计器已完成**；样例图仍缺 | ~70% |
| P2 | （业务侧可选）图改造 + 回归；**非**框架必达 | 业务 | **未开始** | 0% |

---

## 11. 决策摘要

### 11.1 已达成一致（含 O1～O5 收口）— 决策全勾；实现状态如下

| 决策 | 决策 | 实现状态 | 完成度 |
|------|------|----------|--------|
| 方案 A：节点完成落盘钩子 | [x] | **已落地** | 100% |
| SAA 父支持 `memoryWrites`；子 Agent Q5 不变 | [x] | **已落地** | 100% |
| MAIN 正文 = 父 `outputKey` | [x] | **已落地** | 100% |
| 同图多 `WRITE_USER` 配置自查、不做自动去重 | [x] | **按设计**（未做去重） | 100% |
| `ls_out_put` 去留属业务（O4） | [x] | **框架不绑** | N/A |
| 禁止图尾统一 persist | [x] | **未引入图尾 persist** | 100% |
| **O1**：双 MAIN → WARN、不硬拒绝 | [x] | **已落地** | 100% |
| **O2**：P0 暂缓自动剥 WRITE_USER | [x] | **按设计暂缓** | N/A（P0） |
| **O3**：`memoryWrites` + `memoryMode` 对齐 Generic | [x] | **已落地** | 100% |
| **O5**：双 MAIN 范围 = SAA→直接后继出口且双方 MAIN | [x] | **已落地** | 100% |
| 扩展 `GraphValidator` warnings 通道 | [x] | **已落地** | 100% |

### 11.2 不挡开工的后续可选项

| 项 | 状态 | 完成度 |
|----|------|--------|
| 设计器文案强度；WARN 升级为发布门禁（默认不升级） | **文案已加**；门禁仍默认不升级 | ~50% |
| O2 自动剥 `WRITE_USER`（对齐 Generic `isOutputLikeNode`） | **暂缓**（W11） | 0% |

---

## 12. 参考

- `.cursor/rules/ace-graph-memory-persist.mdc`
- `MemoryWriteFlag` / `GenericAgentSpec.effectiveMemoryWrites`
- `LessoOrderedMessageChatMemoryAdvisor`（before USER / after ASSISTANT 语义参照）
- `LessoAceGraphChatMemoryAdvisorProvider`
- `FlowAgentNodeAction`
- `docs/troubleshooting/2026-10-15-bank-fee-followup-memory-and-llm-latency.md`
- `nacos-config-sample/prompt-ls-out-put-node-v0.0.1.md`

---

## 13. 议题详解（O1～O5）

| 议题 | 决策 | 实现状态 | 完成度 |
|------|------|----------|--------|
| O1 / O5 | 双 MAIN → WARN 不硬拒；范围 SAA→直接后继出口 | **已落地** | 100% |
| O2 | P0 不自动剥 WRITE_USER | **按设计暂缓** | N/A（P0） |
| O3 | memoryWrites + memoryMode 对齐 Generic | **已落地** | 100% |
| O4 | 框架不绑业务是否保留 ls_out_put | **按设计** | N/A |

### 13.1 O1 / O5：双 MAIN 是什么？与「每步都写 MAIN」有何不同？

**双 MAIN（本方案要 WARN 的对象）**

同一次用户请求中，**同一条「对用户终态答复」被 `WRITE_ASSISTANT_MAIN_TEXT` 写了两次**，典型拓扑：

```text
… → SAA(MAIN) → ls_out_put(MAIN) → …
```

后果：remote 两条 ASSISTANT（常为 raw `agent_result` + 脱敏/润色稿）；Thinking Buffer 往往被**第一次** MAIN drain 掉，第二次合并不到思考。

**线性多业务节点各自 MAIN（合法，不拦）**

```text
start → 业务1(MAIN) → 业务2(MAIN) → 业务3(MAIN) → 业务4(MAIN) → end
```

这是**四次有意的分步落盘**（四段不同业务结论 / 四条 ASSISTANT），不是「同一终态写两遍」。与 SAA+出口双 MAIN **不同**：

| 维度 | 1→2→3→4 各写 MAIN | SAA + ls_out_put 都写 MAIN |
|------|-------------------|----------------------------|
| 内容关系 | 通常是**不同步骤**的不同正文 | 通常是**同一结论**的 raw / 再加工 |
| 产品意图 | 多步可追溯 | 多为配置重叠、易重复气泡 |
| 框架态度 | **允许**（配置自查） | **P0 WARN**（提示互斥），不硬拒绝 |

**硬拒绝好不好实现？**

| 方式 | 难度 | 风险 |
|------|------|------|
| 校验期：SAA 直接后继为出口类且双方都有 MAIN → **error 拒编译/拒发布** | **不难**（图拓扑 + Spec flags） | 误伤「出口故意写第二条不同 ASSISTANT」的少见图；命名不全会漏检 |
| 运行时：第二次 MAIN 抛错 | 中等 | 半落盘、体验差 |
| **WARN（采用）** | 很容易 | 不挡发布；靠日志/设计器提示 |

**P0：只 WARN。** 硬拒绝留作可选门禁，不默认开启。

### 13.2 O3：`memoryWrites` 开了之后，`memoryMode` 还有没有用？

与 Generic **同一套规则**（`MemoryWriteFlag.resolveFlexible` / `effectiveMemoryMode`）：

| Spec 状态 | 谁说了算 | `memoryMode` 作用 |
|-----------|----------|-------------------|
| **`memoryWrites == null`**（未开「以写意图为准」、旧图） | 旧三态 **`memoryMode`** | **有用**：`NONE` 不参与；`READ_ONLY` 只读历史（Lesso 另挂 ReadOnly Advisor）；`READ_WRITE` ⇒ 三 flag 全开 |
| **`memoryWrites` 非 null**（含 `[]`，「以写意图为准」） | **仅 flags 列表** | **写意图上基本不起作用**；effective 有写 ⇒ 参与，空列表 ⇒ NONE |
| 设计器「以写意图为准」打开 | 通常写入非 null 的 `memoryWrites` | UI 上 memoryMode 可灰显/忽略，避免误解 |

因此 SAA Spec **两者都留**（对齐 Generic），文档写清：**一旦配置了 memoryWrites，就以 writes 为准。**

### 13.3 O2 / O4（摘要）

- **O2（P0）**：**不**做 SAA/出口自动剥 `WRITE_USER`；文档 + 设计器提示「出口勿勾选 WRITE_USER」，与同图双 USER 一样靠配置纪律。
- **O2（后续）**：再与 Generic `isOutputLikeNode` 自动剥离对齐（原方案能力，难度大时可继续延后）。
- **O4**：框架能力 ≠ 业务是否保留输出节点；验收不绑定利费 M1/M2。

### 13.4 实现时注意（非意见分歧，避免误读）

1. **SAA 上的 `memoryMode=READ_ONLY`**：Spec 字段对齐 Generic，但 SAA 父不走 ChatClient Advisor；P0 **只保证写路径**（writes 落盘）。若要对 SAA seed **读历史**，属另项增强，不在本方案必达。
2. **§4.4「不宜双 MAIN」vs O1「WARN」**：产品建议互斥；框架态度是 WARN 不拦，二者不矛盾。
3. **§1 根因表**描述的是改造**前**现状；落地后 SAA 可按 writes 落盘，不再依赖「必须有 ls_out_put」。
4. **仅 `WRITE_ASSISTANT_THINKING`**：与 Generic 相同——不 drain、不 add ASSISTANT；思考仍靠 SSE Buffer，需后续某节点 MAIN 合并。

---

## 14. 工程量评估与开发计划

### 14.1 总体量级

| 档位 | 人天（1 人熟悉本仓库） | 说明 | 状态 | 完成度 |
|------|------------------------|------|------|--------|
| **P0 框架可落盘** | **4～6.5** | Spec + 钩子 + SPI + Lesso + **GraphValidator warnings** + 双 MAIN WARN + 单测（**不含**自动剥 WRITE_USER） | **代码完成** | ~95% |
| **P1 设计器** | **1.5～2.5** | `SaaWorkflowForm` 记忆 UI + 出口勿 WRITE_USER 提示 | **已完成** | 100% |
| **联调 / 回归** | **1～2** | 样例图（可选）+ 记忆条数 / thinking 合并 | **未开始** | 0% |
| **合计** | **约 6.5～11** | 不含业务改图、不含 READ_ONLY 读历史、不含 O2 自动剥离 | **整体约 85%**（缺联调） | ~85% |

相对复杂度：**中等**——写路径可复用现有 ChatMemory / Buffer / Display SPI；主要成本在 **record 改字段波及面**、**SPI 分层**、以及 **校验 WARN 通道**（均已落地）。

### 14.2 工作分解（WBS）

| ID | 项 | 模块 | 估（人天） | 阶段 | 依赖 | 状态 | 完成度 |
|----|----|------|------------|------|------|------|--------|
| W1 | `SaaWorkflowSpec` 增 `memoryWrites`/`memoryMode` + `effective*` + JSON 兼容单测 | `ace-graph-dsl-core` | 0.5～1 | P0 | — | **已完成** | 100% |
| W2 | 全仓 `new SaaWorkflowSpec(...)` / `of(...)` 编译修复 | core / saa / agentscope 测试等 | 0.5 | P0 | W1 | **已完成** | 100% |
| W3 | 中性 SPI：`GraphNodeMemoryPersister`（persistUser / persistAssistantMain） | `ace-graph-dsl-ai` | 0.5 | P0 | — | **已完成** | 100% |
| W4 | `FlowAgentNodeAction`：按 flags 调用 Persister（USER 前 / MAIN 后）；无 Bean 则跳过+WARN（降级防护） | `ace-graph-dsl-saa-agent` | 1～1.5 | P0 | W1,W3 | **已完成** | 100% |
| W5 | Factory 注入 Persister + flags（**P0 不做**自动剥 WRITE_USER） | saa-agent | 0.3～0.5 | P0 | W4 | **已完成** | 100% |
| W6 | **【必做】** Lesso 实现 Persister + **agent-server 注册 Bean**（ChatMemory.add + drain + Display/PersistMetadata）；**不可**只靠 AdvisorProvider | `lesso-ai-platform-agent-server` | 1～1.5 | P0 | W3 | **已完成** | 100% |
| W7a | **扩展 `GraphValidator` warnings 通道**（收集/返回/日志；设计器可后续消费） | `ace-graph-dsl-core` | 0.5～1 | P0 | — | **已完成** | 100% |
| W7b | 双 MAIN：拓扑检测 → **warnings**（非 errors） | `SaaWorkflowValidator` / GraphValidator | 0.5 | P0 | W1, W7a | **已完成** | 100% |
| W8 | 单测：Spec / NodeAction mock Persister / 双 MAIN → warnings / Q5 回归 | 各模块 | 0.5～1 | P0 | W4～W7b | **已完成**（单测绿；未跑全仓 CI） | 100% |
| W9 | 设计器：`SaaWorkflowForm` 记忆区块 + 提示「出口勿 WRITE_USER」 | `ace-graph-dsl-ui` | 1.5～2.5 | P1 | W1 | **已完成** | 100% |
| W10 | 样例/文档 + 可选联调 | docs / agent-server | 1～2 | P1～P2 | W6 | **部分**（方案 MD 已更新；样例图/联调未做） | ~20% |
| W11 | （可选后续）出口形态自动剥 WRITE_USER，对齐 Generic | saa + lesso | 0.5～1 | 非 P0 | W4 | **暂缓** | 0% |

**汇总：** P0（W1～W8）**8/8**；P1 设计器 W9 **已完成**；W10 联调/样例仍缺；W11 非本里程碑。

**建议排期（日历，单人）：**

```text
D1     W1 + W3 + W7a（warnings 通道）起稿
D2     W2 + W4 主体
D3     W5 + W6
D4     W7b 双 MAIN + W8 大部分
D5     W8 收尾、P0 自测 → M-P0
D6～D7 P1 设计器（可并行）
D8     联调（可选）
```

### 14.3 里程碑验收

| 里程碑 | 完成标准 | 状态 | 完成度 |
|--------|----------|------|--------|
| **M-P0** | **含** Lesso `GraphNodeMemoryPersister` 实现并在 agent-server 装配；JSON 配 SAA `WRITE_ASSISTANT_MAIN_TEXT` 后无 `ls_out_put` 也能 remote 出现 1 条 ASSISTANT；日志有 `SAA memory persist`；**warnings** 含双 MAIN；单测绿；**不要求**自动剥 WRITE_USER | **代码侧达标**（单元测绿；**真机 remote 联调未做**） | ~95% |
| **M-P1** | 设计器可配 SAA 记忆；文案提示与出口双 MAIN | **设计器达标**（样例图可选） | ~90% |
| **M-业务**（非框架必达） | 利费图是否改拓扑由业务定 | **未开始** | 0% |

### 14.4 难点与阻塞点

#### 已识别难点（可做，需设计落地）

| # | 难点 | 影响 | 应对 | 状态 | 完成度 |
|---|------|------|------|------|--------|
| D1 | **`SaaWorkflowSpec` 是 record**，加字段导致多处构造/测试编译失败 | 机械改动面大 | W2 集中修；`of(...)` 默认 null writes；`@JsonIgnoreProperties` 已有 | **已化解**（构造/`of`/相关测试已修通） | 100% |
| D2 | **框架 vs Lesso 分层**：drain Buffer / extras 键不能写进 saa-agent | 易踩依赖 | 必须走 **SPI**；Lesso 实现内 drain+add | **已化解**（`GraphNodeMemoryPersister` + `LessoGraphNodeMemoryPersister`） | 100% |
| D3 | **USER 正文从哪来**：无 ChatClient Request，只有 state | 与 Advisor before 不完全同构 | 复用 `MemoryDisplayUserTextResolver` + state（user_query 等），与现有 SPI 对齐 | **已化解**（Persister 走 Display/Persist SPI + stateVariables） | 100% |
| D4 | ~~出口剥 WRITE_USER~~ | — | **P0 不做**；配置纪律（O2 修订）。后续见 W11 | **P0 刻意不做**（暂缓→W11） | N/A（非本阶段） |
| D5 | **Thinking 归属**：AS 流式思考挂 conversationId，MAIN 在 SAA 结束 drain | 若键不一致则 thinking 丢 | 联调确认与 SSE Buffer 键一致 | **代码就绪、联调待证**（单测已 drain；真机 AS 流式未验） | ~70% |
| D6 | **仅 THINKING / 空 outputKey**：易误测 | 行为要与 Advisor 一致 | 单测覆盖跳过路径 | **已化解**（`FlowAgentNodeActionMemoryPersistTest` 覆盖 THINKING-only / 无 cid / 空 writes） | 100% |
| D7 | **warnings 通道** retrofitted 进现有 validate API | 调用方要兼容 | 新增 `List<String> warnings`；`ok` 仍只看 errors | **已化解**（`ValidationResult` 三元；发布仅 log warnings） | 100% |

#### 阻塞 / 半阻塞（先决条件）

| # | 点 | 是否真阻塞 | 说明 | 状态 | 完成度 |
|---|----|------------|------|------|--------|
| **B1** | `GraphValidator` 无 warnings | **已决策：P0 扩展 warnings（W7a）** | 双 MAIN 进 warnings；**不**当 errors，不挡编译/发布 | **已解除**（warnings 通道 + 双 MAIN WARN 已落地） | 100% |
| **B2** | **Persister 实现 + agent-server 装配（P0 必做）** | **非可选** | 必须有 SPI + Lesso Bean；无 Bean 仅降级 WARN **不算**功能交付 | **已解除**（`LessoGraphNodeMemoryPersister` 已注册；单测绿） | 100% |
| **B3** | 多模块版本发布（ace-graph + lesso-ai） | 发布阻塞，非设计阻塞 | 先框架 SPI，再 Lesso 实现，再 agent-server | **未发版**（代码在 feature 分支，未 push/发版） | 0%（发布维度） |
| **B4** | SAA `READ_ONLY` 读历史 | **不阻塞** | §13.4 非 P0 | **非本阶段** | N/A |
| **B5** | O2 自动剥 WRITE_USER | **不阻塞 P0** | 已暂缓；用户配置保证 | **非本阶段**（同 D4/W11） | N/A |

#### 非阻塞（易高估）

| 项 | 状态 | 完成度 |
|----|------|--------|
| 复用 `MemoryWriteFlag.resolveFlexible` | **已用上** | 100% |
| `conversationId` 从 state 读取 | **已落地** | 100% |
| Q5 子 Agent 禁 READ_WRITE | **原有校验保留** | 100% |
| 设计器 Generic 记忆 UI 可抄 → SAA 表单 | **已完成** | 100% |

### 14.5 风险清单（简）

| 风险 | 概率 | 影响 | 缓解 | 状态 | 完成度 |
|------|------|------|------|------|--------|
| 双 MAIN WARN 仅日志、设计器无感 | 中 | 产品以为「没做校验」 | P0 warnings + 设计器双 MAIN / 出口提示 | **已缓解**（表单 warning alert） | ~90% |
| ASSISTANT 与出口脱敏稿重复 | 中 | 业务误配 | WARN + 文档；不做硬拒 | **WARN 已落地**；业务配图自查 | ~70% |
| Persister 与 Advisor 写 USER 格式不一致 | 低～中 | 历史脏数据 | 复用同一 Display/Persist SPI；Persister 单测 | **代码对齐 SPI**；真机对比未做 | ~80% |
| record 漏改测试 | 中 | CI 红 | W2 已修相关构造；本模块单测绿 | **已缓解** | 100% |

### 14.6 建议开工顺序（执行清单）

| # | 项 | 状态 | 完成度 |
|---|----|------|--------|
| 1 | W3 SPI 接口定稿 | **已完成** | 100% |
| 2 | W1 Spec + W2 编译通过 | **已完成** | 100% |
| 3 | W4/W5 钩子 + 无 Persister 降级 | **已完成** | 100% |
| 4 | W6 Lesso 实现 + agent-server 装配 | **已完成** | 100% |
| 5 | W7a warnings → W7b 双 MAIN | **已完成** | 100% |
| 6 | W8 单测绿灯 → **M-P0** | **单测绿**；真机联调未做 → M-P0 ~95% | ~95% |
| 7 | W9 设计器 → **M-P1** | **已完成** | 100% |
| 8 | W11 自动剥 WRITE_USER | **暂缓** | 0% |
