# 有工具时真流式：开发计划

| 项 | 内容 |
|---|---|
| 状态 | **框架已落地（1.0.9 已 deploy mesrelease）** |
| 日期 | 2026-09-23 |
| 前置评估 | [streaming-with-tools-true-stream-evaluation.md](./streaming-with-tools-true-stream-evaluation.md) §10 |
| 目标 | 多节点 ace-graph：`streaming=true` 且 `tools` 非空时，终答轮真流式 |
| 实现要点 | Spring AI 1.1.x `ToolCallAdvisor.adviseStream` **未实现** → 流式+工具改为 Template **手动多轮** + `stream().chatResponse()` + `StreamingToolCallMergingManager`；同步路径仍用 ToolCallAdvisor；终答后 `EchoAssistantChatModel` 触发记忆 Advisor |
| 已知后续 | 业务侧 MCP/ztc 冒烟；可选通道 B `progressFrame`（本计划不排） |

---

## 0. 定案一览（评审冻结）

### 0.1 技术 / 边界

| 条目 | 定案 |
|---|---|
| 4.1 分片 | 框架自研轻量 Merging（按 toolCall id）；协议归业务；不改 Vertical Merging |
| 4.2 API | 有工具真流式 → `stream().chatResponse()`；无工具可暂留 `content()` |
| 4.3 终稿 | 通道 A：`visible` 与 `outputKey` 同源；`join(bridge)==outputKey` |
| 4.4 记忆 | `adviseStream` 完成态落盘；每节点即时 remote；thinking extras ↔ bizParam，≠ deepThinking |
| 4.5 心跳 | 业务已有 `withKeepAlive`；统一空帧；非 Template 范围 |
| 4.6 进度 | **框架不做、不拦截**通道 B；业务可选 `progressFrame`（可 `thinking:true`）；本计划不排 |
| 4.7 | SSE thinking ⊥ 模型 deepThinking（规则已落盘） |
| 4.8 | Spring AI 2.0 **不做** |

### 0.2 O1 / O2 / O3

| # | 定案 |
|---|---|
| **O1** | **通道 A** 默认稳健版：模型工具轮碎字不自动进可见正文 / `outputKey`。**不拦截通道 B**（工具执行 + 业务 `progressFrame`）。Phase 0 只核实 Advisor 是否已滤干净。 |
| **O2** | **本刀交付** emit 同步观察入口；业务挂 ThinkingBuffer；框架不解析 thinking 协议 |
| **O3** | **不保留** `CALL_THEN_CHUNK` 回滚开关；出问题版本回退 |

```text
通道 A（O1 / Template）: 模型流 → 过滤 → GraphStreamBridge → Adapter → delta/deltaThinking
通道 B（业务可选）:     ToolCallback wrap → progressFrame / 自推 SSE（可 thinking:true）
```

相关规则：

- `.cursor/rules/ace-graph-memory-persist.mdc`
- `.cursor/rules/ace-graph-thinking-vs-deepthinking.mdc`
- `.cursor/rules/ace-graph-sse-frame-kinds.mdc`

---

## 1. 目标与非目标

### 1.1 目标

1. 有工具 + 流式：终答轮经 `GraphStreamBridge` **边生成边推**（通道 A）。
2. 工具多轮仍由 Spring AI Advisor 完成；`outputKey` = 通道 A 可见终答全文。
3. 记忆：节点完成即落盘；`adviseStream` 结束后写 ASSISTANT；O2 支撑 thinking buffer 时序。
4. 无工具路径行为不变（或等价）。
5. **不拦截**工具执行与业务通道 B 进度帧能力。
6. 单测 + ZTC/smoke 挂 MCP 冒烟通过。

### 1.2 非目标

- 框架代做工具进度 / `tool_start` / `tool_end`（业务另开，见 §7）。
- Spring AI / SAA 大版本升级。
- Vertical 图尾统一 persist；改 Vertical 真流式 / Merging（无硬交集时）。
- 前端协议大改；框架定义 SSE `thinking` 字段。
- 用 deepThinking / ChatOptions 控制 SSE 是否 thinking。
- 运行时 `CALL_THEN_CHUNK` 双路径开关。

### 1.3 成功标准（验收）

| # | 标准 |
|---|---|
| A1 | 有工具流式主路径不再「先 call 再切片推终稿」 |
| A2 | 通道 A：终答开始后持续非空 chunk；拼接 == `outputKey` |
| A3 | 假模型多轮 tool→final；无 `toolName == null` NPE（Merging） |
| A4 | READ_WRITE：USER（SPI）+ ASSISTANT；bizParam.thinking 时 extras 可非空（依赖 O2+业务挂接） |
| A5 | 出口 `writeUser=false` 不写假 USER |
| A6 | 无工具流式回归绿 |
| A7 | 工具仍可执行；框架路径不阻断业务侧后续接入 `progressFrame`（通道 B 不在本刀实现，但不得被 Template 过滤逻辑误伤） |
| A8 | TokenChunk attrs 原样透传；不按 deepThinking 打 thinking 标记 |

---

## 2. 依赖与约束

| 约束 | 说明 |
|---|---|
| Spring AI | **1.1.2**；必须 Merging 流式 toolCall 分片 |
| 工具执行 | `internalToolExecutionEnabled(false)` + Advisor |
| 记忆 | 每节点即时 remote |
| 设计 §6.4.2 | 通道 A 工具轮默认可不可见；终答下发 |
| 心跳 | 已在 `AceGraphExecutionController#withKeepAlive`，本计划不改 |

只读对照（不硬耦合、不改 Vertical）：

- Lesso `MergingToolCallingManager` 测例行为
- `VerticalAgentChatTemplate#stream().chatResponse()`
- `LessoOrderedMessageChatMemoryAdvisor#adviseStream`
- `LessoSseStream.progressFrame`（业务 backlog 参考）

---

## 3. 阶段划分

```text
Phase 0 摸底 ──► Phase 1 Merging ──► Phase 2 Template 真流式 + O2 观察入口
                                              │
                                              ▼
                                    Phase 3 记忆 / thinking 回归
                                              │
                                              ▼
                                    Phase 4 测试发布
                                              
业务可选（非本计划）: 通道 B progressFrame
```

### Phase 0 — 行为摸底（0.5～1 人日）

| 任务 | 说明 |
|---|---|
| 0.1 | 假 ChatModel：有工具 `stream().chatResponse()` 各轮形态 |
| 0.2 | 裸 Manager vs Merging：是否 NPE |
| 0.3 | Advisor 外层是否已滤工具轮（核实用；实现仍默认 O1 稳健版） |

**出口**：摸底记录附评估修订；确认 Merging 必要；O1 稳健版落地不变。

---

### Phase 1 — 流式 ToolCall 合并（0.5～1.5 人日）

| 任务 | 验收 |
|---|---|
| 1.1 框架自研轻量 Merging（按 id）；无 SSE/协议逻辑 | 单测分片合并 |
| 1.2 `prepareSpec` 使用该 Manager（call/stream 共用） | 有工具路径绿 |
| 1.3 （可选）`ObjectProvider` 覆盖入口 | 非必须 |

不修改 lesso Vertical 内 Merging 类。

---

### Phase 2 — Template 真流式 + O2（2～3 人日）

| 任务 | 说明 |
|---|---|
| 2.1 | `streamCallWithTools`：`spec.stream().chatResponse()` |
| 2.2 | O1 通道 A：`hasToolCalls` 轮不进 `visible`/不自动 emit；终答 text → emit；`outputKey = visible` |
| 2.3 | `finally` → `FINISHED`；attrs 原样透传 |
| 2.4 | `wantStream && tools非空` 走 2.x；去掉主路径 `syncCall + emitTextChunks` |
| 2.5 | **O2**：emit 同步观察 SPI（如 `TokenChunkObserver` / bridge 钩子）；业务可挂 ThinkingBuffer；框架不解析 thinking |
| 2.6 | 无 `CALL_THEN_CHUNK` 开关 |

```text
if (wantStream && tools.isEmpty())   → streamCall(content)      // 现有
if (wantStream && !tools.isEmpty()) → streamCallWithTools(...) // 新
else                                → syncCall
```

**出口**：假模型多轮流式绿；观察入口可单测「同步回调先于 flux complete」。

---

### Phase 3 — 记忆 / thinking 回归（1～1.5 人日）

| 任务 | 说明 |
|---|---|
| 3.1 | streaming+hasTools 仍挂 Ordered Advisor |
| 3.2 | ASSISTANT 在 stream 完成后写入；业务若挂 O2 → buffer 与 drain 时序正确 |
| 3.3 | 多节点：biz READ_WRITE + out_put `writeUser=false` |
| 3.4 | SSE：`delta` / `deltaThinking` 仍仅业务 Adapter + bizParam |
| 3.5 | 未改 Vertical 单节点路径 |

禁止改为图尾一次性 persist。

---

### Phase 4 — 测试、文档、发布（1～1.5 人日）

| 任务 | 说明 |
|---|---|
| 4.1 | `ChatClientToolCallingTemplateTest`：streaming+工具；bridge 拼接 == outputKey |
| 4.2 | 无工具流式、同步有工具回归 |
| 4.3 | 联调 ls_biz_node / ztc：终答真流式体感；工具可执行 |
| 4.4 | 更新实现计划 P3.2、CHANGELOG、评估状态→实施中/已实施 |
| 4.5 | 升版 deploy；agent-server 依赖同步 |

---

### 业务可选（非本框架计划）— 通道 B 工具进度

- 协议：`LessoSseStream.progressFrame`（可 `thinking:true`）。
- 对齐参考：Vertical `emitToolProgress` / `withToolProgress`。
- **仅业务侧立项**；框架不代做、不拦截。

---

## 4. 任务看板（框架首刀）

| ID | 阶段 | 任务 | 预估 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T0 | 0 | 流式+工具 chunk 摸底 | 0.5～1d | — | **完成**（adviseStream 未实现） |
| T1 | 1 | Merging + 单测 | 0.5～1.5d | T0 | **完成** |
| T2 | 2 | `streamCallWithTools` 手动多轮 + O1 | 1.5～2.5d | T1 | **完成** |
| T3 | 2 | O2 emit 同步观察入口 | 0.5d | T2 | **完成** |
| T4 | 3 | 记忆 / thinking / 多节点回归 | 1～1.5d | T2,T3 | **完成**（中间轮跳过记忆；终答 Echo 落盘） |
| T5 | 4 | 单测扩展 + MCP 冒烟 | 1～1.5d | T2,T4 | **部分完成**（Merging/O2/stream+tools 单测绿；MCP 冒烟待业务） |
| T6 | 4 | 文档 + 升版 deploy | 0.5d | T5 | **完成**（1.0.9 → mesrelease；agent-server 依赖已升） |

---

## 5. 测试计划

### 5.1 单测

| 用例 | 要点 |
|---|---|
| 分片合并 | 同 id 跨 chunk → 一次执行且 name/args 完整 |
| 流式多轮 | tool→final；通道 A 拼接 == outputKey |
| 工具轮不自动泄漏 | 带 toolCalls 的模型碎字默认不进 bridge |
| O2 同步观察 | emit 时观察者被同步调用 |
| 无工具流式 | 与现网一致 |
| 同步有工具 | `streaming=false` 仍 call |

### 5.2 集成 / 手工

| 场景 | 观察 |
|---|---|
| ZTC biz 挂 MCP | 工具段可阻塞；终答开始后逐字 delta；工具确实被调用 |
| bizParam.thinking=true | deltaThinking；O2+业务挂接后 extras.thinking |
| deepThinking 开、bizParam.thinking=false | Options 生效；SSE 普通 delta |
| 仅出口 READ_WRITE | 无假 USER |
| 心跳 | 工具长空闲仍有空心跳（现网 withKeepAlive，回归即可） |

### 5.3 回滚

无运行时双路径。异常 → 框架版本回退或修主路径。

---

## 6. 里程碑

| 里程碑 | 内容 | 建议节奏 |
|---|---|---|
| M1 | Phase 0～1 Merging | 第 1～2 天 |
| M2 | Phase 2 真流式 + O2 | 第 3～5 天 |
| M3 | Phase 3～4 联调 + 1.0.9 deploy | 第 6～8 天 |
| — | 业务通道 B 进度帧 | 产品另立，不绑 M3 |

---

## 7. 后续 backlog（非本刀）

| 项 | 归属 | 说明 |
|---|---|---|
| 工具进度 `progressFrame` | **业务** | 可 thinking:true；wrap ToolCallback |
| 无工具统一 `chatResponse()` | 框架 | 减两套订阅 |
| Spring AI 2.0 | 独立 RFC | 深度评估后另开 |
| 工具轮数上限可配 | 框架 | 设计建议 ≤5 |

---

## 8. 评审检查清单

- [x] 范围：多节点 ace-graph；不改 Vertical（无硬交集）
- [x] Merging 框架自研；协议归业务
- [x] API = `chatResponse`
- [x] O1 通道 A 稳健 / 不拦通道 B
- [x] O2 本刀交付观察入口
- [x] O3 无回滚双路径开关
- [x] 4.5 心跳归业务 SSE（已有）
- [x] 4.6 进度归业务可选
- [x] thinking ≠ deepThinking；规则已补
- [x] 不做 Spring AI 2.0
- [x] 记忆每节点即时落盘

**结论：业务分歧已一致；按 Phase 0 开工。**

---

## 9. 修订记录

| 日期 | 说明 |
|---|---|
| 2026-09-23 | 初稿，与评估文档同步 |
| 2026-09-23 | 冻结 O1～O3、4.5/4.6、通道 A/B；去掉回滚开关；O2 纳入看板；业务进度剔出本计划 |
| 2026-09-23 | 落地：Merging、ObservingGraphStreamBridge、streamCallWithTools 手动多轮；单测绿；流式+工具记忆 Advisor 待收口 |
