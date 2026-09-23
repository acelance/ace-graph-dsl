# 有工具时真流式：技术评估

| 项 | 内容 |
|---|---|
| 状态 | 方案已定案（§10）；框架已实施并发布 **1.0.9**（见 plan） |
| 日期 | 2026-09-23 |
| 范围 | 多节点 ace-graph：`StreamingLlmTemplate` 有工具真流式；**不改**旧单节点 Vertical（无硬交集时） |
| 关联 | [streaming-llm-implementation-plan.md](./streaming-llm-implementation-plan.md) P3.2 · [streaming-llm-node-template-design.md](./streaming-llm-node-template-design.md) §6.4.2 · 开发计划 [streaming-with-tools-true-stream-plan.md](./streaming-with-tools-true-stream-plan.md) |
| 依赖栈 | Spring AI **1.1.2** / spring-ai-alibaba **1.1.2.2** |

---

## 1. 结论摘要

| 问题 | 结论 |
|---|---|
| 有工具时能否走真流式？ | **能**。不是框架禁令，是 P3.2 为稳妥选了 `call` + 64 字假流式。 |
| 最大阻力 | Spring AI **1.1.x** 流式 tool-call **分片** + `MessageAggregator` 易触发 `toolName == null` NPE；需合并分片。 |
| 第二阻力 | 记忆 Advisor / thinking 缓冲与「边推边生成」时序要对齐（当前伪流是 **先落盘 ASSISTANT，再切片推 SSE**）。 |
| 改造性质 | **仅多节点 ace-graph 路径**（`StreamingLlmTemplate` / GenericAgent）；**无交集则不改**旧单节点 Vertical。 |
| 定案（2026-09-23） | 见 §10；Spring AI 2.0 **不做**；SSE thinking ≠ deepThinking（见项目规则）。 |

---

## 2. 现状基线

### 2.1 Template 三分支

实现：`ace-graph-dsl-ai` → `StreamingLlmTemplate`。

```text
wantStream = streaming && runId 非空
├─ 无工具 → streamCall：spec.stream().content() → GraphStreamBridge 真吐字
├─ 有工具 → syncCall：spec.call().content() → emitTextChunks(64) 假流式
└─ 非流式 → syncCall，不推桥
```

有工具日志：`流式+工具：先 ChatClient.call 多轮，再切片推送终稿`。

### 2.2 工具装配要点

- `ToolCallAdvisor` + `DefaultToolCallingManager`
- `ToolCallingChatOptions.internalToolExecutionEnabled(false)`（执行交给 Advisor，不走 Model 内置）
- 多轮闭环由 Spring AI 完成；Template **不自写** tool 循环（与设计 §6.4.2 一致）

### 2.3 产品语义（设计已定）

设计文档 §6.4.2：

- 流式场景下，**工具调用轮次默认可不对用户可见**；
- 只有**最终回答轮**的 token 经 `GraphStreamBridge` 下发；
- 「正在加载 skill」等过程可见为 **可选** BIZ 片段。

因此「真流式」首要目标是：**终答轮边生成边推**，不是必须把每一轮 tool_call JSON 推给前端。

### 2.4 对照：Vertical / Lesso 已走通的路径

| 能力 | 位置 | 说明 |
|---|---|---|
| 真 `stream().chatResponse()` + 工具 | `VerticalAgentChatTemplate` | 不走 ace 的 call+切片 |
| 流式 toolCall 按 id 合并 | `MergingToolCallingManager` | 修 1.1.x 分片 NPE |
| 工具进度 SSE | `PlatformVerticalGraphStreamSupport.withToolProgress` | wrap ToolCallback → progress 帧 |
| 记忆时序 | Vertical 常图尾 persist；ace 每节点即时 | **角色对齐、时机不对齐**（见 `.cursor/rules/ace-graph-memory-persist.mdc`） |

评估结论：业务侧已有可参考实现；ace-graph 框架侧是「刻意简化」，不是「无解」。

---

## 3. 为何当年选 call（背景，非禁令）

| 动机 | 说明 |
|---|---|
| 多轮闭环稳 | `call()` + ToolCallAdvisor 在 1.1.2 上验证成本低；P3.2 验收只需「能调工具拿到终稿」。 |
| 规避流式分片坑 | 1.1.x `MessageAggregator` 对流式 toolCall 拆条 → `toolName == null` NPE（Lesso README / compat 文档已记）。 |
| 契约简单 | 一次拿齐 `outputKey` 终稿，再假推流，SSE 协议与 Formatter 不用区分「中间轮 / 终答轮」。 |
| 工具本身阻塞 | MCP/本地工具是同步回调；即使用 stream，工具执行期仍无字可吐，首包延迟仍在。 |

实现计划原文（P3.2）：「流式无工具真流式；有工具先 call 再切片推送」——属**已交付策略**，可被后续迭代取代。

---

## 4. 重点与难点

### 4.1 【P0】流式 tool-call 分片 — **定案 B（§10）**

框架自研轻量 Merging；协议归业务；不改 Vertical 既有 Merging。

### 4.2 【P0】API — **定案 `chatResponse()`（§10）**

有工具真流式用 `stream().chatResponse()`；无工具可暂留 `content()`。

### 4.3 【P0】终稿与 state — **建议见 §10.1**

`visible` 与 `outputKey` 同源；工具轮静默；单测 `join(bridge)==outputKey`。

### 4.4 【P1】记忆 / thinking 时序 — **建议见 §10.2**

`adviseStream` 完成态落盘；thinking buffer 建议 emit 同步点由业务按 bizParam 填充（与 deepThinking 无关）。

### 4.5 【P1】SSE / 平台协议 — **保活 ≠ 带 thinking 的假 delta（定案）**

| 通道 | 注意点 |
|---|---|
| 框架默认 Formatter | `AGENT_MODEL_STREAMING` / `FINISHED`；空 token + `last=true` 表示段结束 |
| Lesso Adapter | 空 token 丢弃；整轮结束靠 `endFrame` |
| thinking 正文 | bizParam → `deltaThinking`（与 deepThinking 无关） |
| **工具长空闲保活** | 协议内心跳：`is_end=false`、**无 content / 无 node**；前端**忽略**、不断流 |

**易混点澄清**：单节点/多节点保活都不是「跟上一段一样再发一条 thinking:true 或正文空包」。  
Lesso 约定（`sse-json-protocol.md` / `LessoSseFrame.heartbeat`）是**统一心跳帧**，与上一段是思考还是正文**无关**。  
多节点 ace-graph 出口已套 `LessoSseStream.withKeepAlive`（`AceGraphExecutionController`），属**业务 SSE 层**，**不进**框架 Template 真流式改造范围。

工具期「无字可吐」时：靠心跳保连接；要「正在调工具」文案 → 见 4.6（进度帧），不是心跳。

### 4.6 【P2】工具进度可见 — **框架不做；协议有则业务可选（定案）**

- 框架 Template **不**发明 / 不强制 `tool_start` / `tool_end`。
- Lesso 协议**已有** `progressFrame`（`[progress]` + 常带 `thinking:true`）；Vertical 在 `emitToolProgress` 时用。
- 多节点 ace-graph：若产品要对齐 Vertical 进度展示 → **仅业务侧**（wrap ToolCallback / Adapter）另开任务；协议未要求本路径必做则**不做**。
- **本优化计划不排 4.6**。

### 4.7 【P2】深度思考 / ChatOptions — **澄清：与 SSE thinking 正交（定案）**

| | 模型 deepThinking | SSE / extras「thinking」 |
|---|---|---|
| 开关 | state `deepThinking` ∧ 节点 `applyDeepThinking` → ChatOptions | 节点 bizParam.`thinking` → Adapter 选 `deltaThinking` |
| 框架 | Customizer SPI | 只透传 `TokenChunk.attrs` |
| 真流式回归 | Options 仍能挂上即可 | **不要**用 deepThinking 推断 chunk 是否思考段 |

规则文件：`.cursor/rules/ace-graph-thinking-vs-deepthinking.mdc`。评估原文曾把「stream chunk 是否出现 thinking 字段」绑到 ChatOptions，**已更正**。

### 4.8 【P3】升级 Spring AI 2.0 — **本次不做**

需单独深度评估；不纳入本优化计划。

---

## 5. 改造成本评估

### 5.1 工作量（人日，框架侧为主）

| 项 | 人日 | 说明 |
|---|---|---|
| 摸底：1.1.2 有工具 `stream().chatResponse()` 行为钉死（含/不含 Merging） | 0.5～1 | 假模型 + 真实 MCP 各一条 |
| Template：有工具分支改真流式 + 终答过滤 | 1.5～2.5 | 核心代码 |
| ToolCallingManager 合并（自研或 SPI） | 0.5～1.5 | 对齐 Lesso 测例 |
| 记忆 / thinking / WRITE 时序回归 | 1～1.5 | 含 Lesso adapter 联调 |
| 单测 + 冒烟（biz 节点带 MCP） | 1～1.5 | |
| 文档 / 变更说明 / 版本发布 | 0.5 | |
| **合计（首刀：终答真流式）** | **约 5～8 人日** | |
| 可选：工具进度帧 | — | **剔出本计划**（业务可选） |
| 可选：升 Spring AI 2.0 | — | **不做** |

### 5.2 风险矩阵

| 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|
| 流式分片 NPE | 节点直接失败 | 高（无 Merging） | 首刀强制 Merging / 等价逻辑 |
| 中间轮文本泄漏到 SSE | 用户看到乱码/JSON | 中 | 仅推终答轮；单测锁定 |
| `outputKey` 缺字/多重 | 下游节点材料错 | 中 | 聚合策略单测 |
| ASSISTANT 落盘过早/过晚 | 历史角色/thinking 错乱 | 中 | 坚持 adviseStream 完成态落盘；对照记忆规则 |
| 工具执行长阻塞无反馈 | 体感「卡住」 | 高（产品） | 心跳保连接（已有）；进度文案属业务 4.6，本计划不排 |
| 与 Vertical 行为不一致 | 同平台两套体感 | 低～中 | 对齐终答真流 + 可选进度 |

### 5.3 不改动的边界（降低成本）

- 不改图编译 / KeyStrategy；
- 不改「每节点即时 remote」记忆节奏；
- 不强制前端协议大改（仍用现有 TokenChunk / Lesso delta）；
- 无工具路径保持现有 `stream().content()`（或择机统一为 chatResponse，非必须）。

---

## 6. 方案对比

| 方案 | 描述 | 体感 | 成本 | 推荐 |
|---|---|---|---|---|
| **0. 维持现状** | call + 64 字切片 | 工具多轮结束后才开始「打字」 | 0 | 可接受则暂缓 |
| **1. 终答真流式（推荐首刀）** | 有工具也 `stream`；工具轮静默；终答边推 | 工具跑完后立即逐字出答案 | 5～8 人日 | ✅ |
| **2. 方案1 + 工具进度** | 另发 BIZ/progress | 工具期也有「正在调用」 | +1.5～2.5 | 二期 |
| **3. 升 Spring AI 2.0 再流式** | 版本跃迁 | 长期更干净 | 很高 | ❌ 不绑本需求 |
| **4. 自写多轮循环 + 每轮 stream** | 关掉 auto ToolCallAdvisor，手驱 | 可控但重复造轮子 | 高 | 仅当 Advisor 流式不可用时兜底 |

---

## 7. 建议定案（供评审）

1. **目标体感**：有工具时，工具执行结束后，**终答轮真流式**推送到 `GraphStreamBridge`；工具轮默认不可见。
2. **技术路径**：`stream().chatResponse()` + 流式 toolCall **按 id 合并** + 现有 `ToolCallAdvisor` / `internalToolExecutionEnabled(false)` 保留；记忆继续走 `adviseStream` 完成态落盘。
3. **不做（首刀）**：工具进度帧、Spring AI 大版本升级、把 Vertical 图尾落盘搬进 ace-graph。
4. **发布**：框架升小版本（如 1.0.9）；业务依赖同步；回归 ZTC biz 节点（`agent:ls_biz_node` 一类挂 MCP 的图）。

---

## 8. 参考路径（代码 / 文档）

| 类型 | 路径 |
|---|---|
| 现状分支 | `ace-graph-dsl-ai/.../StreamingLlmTemplate.java`（wantStream / syncCall / streamCall / emitTextChunks） |
| 节点入参 | `ace-graph-dsl-ai/.../GenericAgentNode.java`（streaming / tools） |
| SSE 桥 | `ace-graph-dsl-core/.../GraphStreamBridge.java` · `ReactorGraphStreamBridge` |
| 实现计划原文 | `docs/streaming-llm-implementation-plan.md` P3.2 |
| 设计 §6.4.2 | `docs/streaming-llm-node-template-design.md` |
| Lesso 合并 | `lesso-ai-adapter-spring-ai-alibaba/.../MergingToolCallingManager.java` |
| Vertical 真流 | `lesso-ai-agent-runtime/.../VerticalAgentChatTemplate.java` |
| 记忆流式 | `lesso-ai-memory/.../LessoOrderedMessageChatMemoryAdvisor.java` `#adviseStream` |
| 记忆落盘约定 | `.cursor/rules/ace-graph-memory-persist.mdc` |

---

## 9. 修订记录

| 日期 | 说明 |
|---|---|
| 2026-09-23 | 初稿：基于现状代码与 Lesso/Vertical 对照评估 |
| 2026-09-23 | 评审定案：§10；补 thinking≠deepThinking 规则；范围限多节点 ace-graph |

---

## 10. 评审定案（2026-09-23）

**范围约束**：本优化只服务「整合 ace-graph-dsl 后的多节点 agent」。与旧单节点 Vertical **无无法分离的交集时，禁止改动 Vertical 实现**（含其自有 Merging / 真流式路径）。

| 条目 | 定案 |
|---|---|
| **4.1 分片合并** | **B. 框架自研轻量 Merging**（按 toolCall id 合并）。**协议格式一律业务侧**；框架最多提供接入口，不解析 `thinking` 等。对照 Lesso 测例，**不改** Vertical 内 Merging。 |
| **4.2 API** | 有工具真流式用 **`stream().chatResponse()`**。因 1.1.x `ToolCallAdvisor.adviseStream` 未实现，改为 Template **手动多轮**（同步仍用 Advisor）。 |
| **4.3 终稿与 state** | 见 §10.1。 |
| **4.4 记忆 / thinking 时序** | 见 §10.2；thinking 与 bizParam 对齐，**不**绑 deepThinking。 |
| **4.5 心跳保活** | **业务 SSE 层**已有 `withKeepAlive`；心跳=无 content 统一帧，**不**跟上一帧 thinking 状态走。非框架 Template 范围。 |
| **4.6 工具进度** | **框架不做**；协议已有 `progressFrame` 时由**业务可选**另开；本计划不排。 |
| **4.7 deepThinking vs SSE thinking** | **正交**。规则：`.cursor/rules/ace-graph-thinking-vs-deepthinking.mdc`。 |
| **4.8 Spring AI 2.0** | **本次不做**。 |

### 10.3 开工前议题 — 定案（2026-09-23）

| # | 议题 | 定案 |
|---|---|---|
| **O1** | 终答判定 | **通道 A 默认稳健版**（模型工具轮碎字不自动进可见正文/`outputKey`）。**不拦截通道 B**（业务 `progressFrame` / 工具 wrap）。见 §10.3.1。 |
| **O2** | emit 同步观察入口 | **本刀交付**：框架提供同步 SPI/钩子；业务挂 `ThinkingBuffer.append`（框架不解析 thinking 协议）。 |
| **O3** | `CALL_THEN_CHUNK` 回滚开关 | **砍掉**：不保留双路径配置。出问题用版本回退 / 修缺陷；避免测试矩阵翻倍。 |

#### 10.3.1 O1 白话说明（避免再混）

有工具时模型会多轮：例如「先说要调工具 → 调工具 → 再写出最终回答」。

| 说法 | 意思 |
|---|---|
| **稳健版（默认做）** | **仅针对「模型 chatResponse → Template 决定推不推 bridge」这条默认路径**：还在「要调工具」的模型碎字默认**不**自动当用户可见正文 / 不进 `outputKey`；终答字才自动推。 |
| **简化版** | 若摸底发现 Advisor 外层已只剩终答字，稳健判断近乎空操作，仍可保留。 |

**与业务工具进度的关系（重要）**：

```text
通道 A（O1 管这里）:  模型流 → Template 过滤 → GraphStreamBridge → Adapter → delta/deltaThinking
通道 B（O1 不管）:    ToolCallback wrap → progressFrame / 业务自推 SSE → 可带 thinking:true
```

- 框架**代做**进度帧 → 不做。  
- 框架也**不得拦截**通道 B：工具照常执行；业务 wrap 后发 `progressFrame`（或自管 SSE）必须能出去，否则 biz_node 看不到「正在调工具」。  
- O1 稳健版**只**约束通道 A 的「模型碎字是否自动当正文」，**不是**把工具调用整条链路掐掉。

若业务以后要把进度也打进 `GraphStreamBridge`，应走**业务主动 emit**（观察入口/自有 sink），**不要**经过「hasToolCalls → 丢弃」那一段自动过滤。

验收：`join(通道A 自动推送) == outputKey`；通道 B 有无进度与 O1 正交。

### 10.1 【4.3】终稿与 state — 建议

原则：**可见终答** 与 **`outputKey`** 同源。

1. 订阅 `chatResponse()`；`StringBuilder visible`。
2. `hasToolCalls()` 的轮次不累进 `visible`（工具轮静默）。
3. 无 tool calls 且有 text → 追加 `visible` 并 `GraphStreamBridge.emit`。
4. 结束：`outputKey = visible.toString()`。
5. 单测：`join(bridge tokens) == outputKey`。

若 Phase 0 证实 Advisor 已滤掉中间轮，可简化为「外层全部 text 即 visible」，仍以拼接相等为验收。

禁止：conversationHistory 整包当地 `outputKey`；真流+假流双累加。

### 10.2 【4.4】记忆 / thinking 时序 — 建议

1. `adviseStream` 完成后再写 ASSISTANT；多节点每节点即时 remote。
2. thinking extras 仅来自 bizParam 思考通道 → buffer → `after` drain；**与 deepThinking Options 无关**。
3. buffer `append` 建议挂在 **emit 同步点**（框架观察入口 + 业务实现），避免仅 SSE 异步晚于 drain。
4. 回滚 call+切片时接受 extras 可能仍空。
