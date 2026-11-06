# HITL 可拖节点工厂 — 缺口与优化方案

| 项 | 内容 |
|---|---|
| 状态 | **已合主线收口**（Issue 1）；§9 **#1～#6 已拍板**；后端 **1.1.32** mesrelease；UI **1.0.6** npm；本地+测环境验收通过（2026-11-06） |
| Issue 标题（建议） | `[HITL] 可拖 HITL 节点工厂：人工注入 key + 与 interruptBefore/连线校验对齐` |
| 框架仓库 | `ace-graph-dsl` |
| 框架分支 | `feature-hitl-node-factory` |
| 框架版本 | 后端 **`1.1.32`**；UI **`@acelance/graph-dsl-ui@1.0.6`**（npm 已有 1.0.5，HITL 工厂递增；由人工发 npm） |
| 业务仓库 | `lesso-ai-project`（`lesso-ai-platform-agent-server` + 设计器跟版） |
| 业务分支 | `feature-hitl-node-factory-host-bump`（**仅跟版/样例**，不对位做新协议） |
| 前置已落地 | 顶层 `interruptBefore` + 宿主 resume + 平台结束帧 interrupt（见宿主 `HITL-resume-WebFlux缺口与方案.md`，**已落地**） |
| 关联评估 | `Agent工程三层评估` → G1 / G5 / L7 |

---

## 1. 一句话结论

**图级 `compile.interruptBefore` 已能断点 + resume**；缺的是画布上**可拖、可声明「人工注入哪些 state key」**的真正 HITL 节点，使：

1. 入边享受现有 **CATEGORY_HITL 连线参数可达性豁免**（人工键不必由上游 Agent `outputKey` 产出）；
2. 出边下游能把这些键算作**可达**（写入校验用的 `outputKeys`）；
3. 与 `interruptBefore` **正交但可自动挂钩**，避免只靠「对业务 Agent 节点勾中断」的隐式用法。

本刀**默认不改**宿主 resume / 平台 SSE 协议（§9 #6：遇阻塞点停刀重评，禁止静默扩 scope）；业务侧只跟框架版 + 可选改样例图。

---

## 2. 名词当场解释（只读本文即可）

| 说法 | 是什么 | 不做会怎样 |
|---|---|---|
| **图级 `interruptBefore`** | 图 JSON `compile.interruptBefore: ["节点id",…]`；引擎在**执行该节点之前**暂停，checkpoint 的 `next` 指向该节点 | 无法断点；或只能对普通 Agent 节点勾选中断 |
| **`interruptContent`** | 同页 Compile 配置的中断提示文案；宿主平台 SSE **结束帧** `interrupt_content` 读取 | 走宿主 fallback 文案 |
| **`CATEGORY_HITL`** | 节点描述里的类别字符串 `"HITL"`；前后端连线校验：**指向 HITL 的入边不检查上游是否产出目标 `inputKeys`** | 人工键若配在普通 Agent 的 `inputKeys` 上，发布校验失败（上游产不出） |
| **人工注入 key** | 人在 resume 的 `updates` 里写入的扁平 state 键（如 `approved_text`）。**不是** Agent `outputKey` 自动拆 JSON | 键不进 `keyStrategies` 可能被 `updateState` 丢弃；不进下游 `inputKeys`/Prompt 占位则模型看不到 |
| **HITL 节点（本刀要做）** | 画布可拖节点：类别 HITL；声明 `injectKeys`；运行时在 resume 写入之后作为「闸门」节点被执行（多为透传/回声） | 继续只有「对 Agent 勾 interruptBefore」的隐式 HITL |
| **宿主 resume** | `POST /api/ace-graph/{graphId}/resume`，body：`threadId` + `updates`；**已落地** | 本刀不重做 |
| **业务跟版分支** | `feature-hitl-node-factory-host-bump`：升依赖、部署、可选改 `test-HITL-graph` | 无业务分支则测环境设计器仍是旧包，拖不出新节点 |

**正交关系（必读）：**

```
interruptBefore  ── 决定「在哪个节点前停」
CATEGORY_HITL    ── 决定「连线校验怎么豁免 / 面板分类」
HITL 节点工厂    ── 提供可拖节点，把两者在产品上绑在一起（本刀）
```

对 `agent:final_reply` 勾 `interruptBefore` **不必**该节点是 HITL 类别；本刀也**不废除**该用法。

---

## 3. 现状缺口

| 能力 | 现状 | 痛点 |
|---|---|---|
| 断点 / resume | ✅ 顶层已通 | — |
| 面板 HITL 配色 / i18n | ✅ 有壳 | 节点面板**没有**可拖 HITL 实现 |
| 注册表 `CATEGORY_HITL` | ✅ 常量 + 校验豁免 | **无**可拖 HITL 产品节点（本刀用 `NodeRef.hitlSpec` 结构节点，非固定注册 id） |
| 人工键过校验 | ❌ | `approved_text` 放进下游 `inputKeys` → 上游 Agent 只出 `draft` → **连线校验失败** |
| Agent `outputKey` | 仅 **String** 全文 | 不能一次吐两键；Prompt **不支持** `obj.prop` 嵌套 |
| 自动纳入 `interruptBefore` | ❌ | 易漏配：画了审核节点却忘了勾中断 |

真机旁证（本刀前序）：`test-HITL-graph` resume 写回 `draft` 成功，但 `final_reply` 未绑 `inputKeys`/占位时模型仍说「缺字段」——属图配置；人工第二键则卡在**校验 vs 注入**产品缺口，正是本刀要解的。

---

## 4. 目标行为（验收可读）

### 4.1 设计器

1. 节点面板出现 **HITL / 人工审核** 类可拖节点（或「添加 HITL」入口，与脚本/结构节点同级可发现）。
2. 选中该节点：属性面板可编辑：
   - **注入键列表 `injectKeys`**（字符串数组，如 `approved_text`；必填至少一个）；
   - 展示名；
   - （只读提示）保存/发布会将本节点 id **自动并入** `interruptBefore`；勿从中断点列表中移除，否则 publish 校验失败。
3. 保存草稿 / 发布：图 JSON 中该节点可反序列化还原 `injectKeys`。
4. **连线**：`起草 Agent → HITL → 定稿 Agent`：
   - HITL 入边：不因 `injectKeys` 未在上游产出而报错；
   - 定稿 `inputKeys` 含 `draft` + `injectKeys` 中某键：在 HITL 声明产出后，**校验通过**。

### 4.2 运行时（仍用现有宿主 resume）

推荐拓扑：

```
START → agent:draft_reply(outputKey=draft)
      → hitl:…（injectKeys=["approved_text"]）
      → agent:final_reply(inputKeys=[draft, approved_text])
      → END
```

Compile：

```json
{
  "interruptBefore": ["hitl:…"],
  "saver": "memory",
  "interruptContent": "草稿已生成，请填写 approved_text 后继续。"
}
```

跑法：

1. stream 至断点 → `state.next` = HITL 节点 id；`draft` 已在 state。  
2. `POST .../resume`：

```json
{
  "threadId": "<自备>",
  "updates": {
    "approved_text": "人工定稿正文"
  }
}
```

3. 引擎执行 HITL 节点（透传/回声）→ 定稿 Agent → END；定稿 Prompt 使用 `{{draft}}`、`{{approved_text}}`。

### 4.3 业务侧

- 升 `ace-graph-dsl`（含 UI 包若分离）到本刀发版号；设计器能拖到节点。  
- **本刀不做**新 WebFlux API、不做 Lesso 聊天 resume。

---

## 5. 方案设计

### 5.1 节点形态：结构节点 + `hitlSpec`（多实例友好）

**已拍板（§9 #1）：采用 NodeRef 结构字段 `hitlSpec`，不采用单一 `human_review` 固定注册 id。**

对外：节点面板仍可提供一个「人工审核」拖拽入口（体验像组件）。  
对内：每个画布实例独立 `nodeId` + `hitlSpec`（对齐 SAA 的 `saaSpec` / Agent 的 `agentSpec`）。

| 字段 | 含义 |
|---|---|
| `NodeRef.category` | `"HITL"`（或与现有 category 字段一致） |
| `NodeRef.hitlSpec.injectKeys` | `List<String>`，人工可写、且对下游宣称产出的扁平键 |
| 画布 `nodeId` | 用户可改，如 `hitl:review_reply`；须进入 `interruptBefore` |

**运行时 `toAction`（KISS；缺 key 策略见 §5.1.2 / 已拍板 §9 #4）：**

```text
对 injectKeys 中每个 key：
  若 state 已有值 → 原样放入返回 Map（回声，便于日志与 REPLACE 策略显式）
  若缺失 → 不抛错、不写入（缺键留给下游）
不调用 LLM。
```

日志（关键点必打）：`graphId`、`nodeId`、`injectKeys`、各键是否存在、`runId`/`threadId`（若 state 可读）。

**不做（本刀）：** 在 HITL 节点内弹 UI；解析 Agent JSON 多字段；`obj.prop` 渲染。

#### 5.1.1 议题 #1 追问纪要：结构节点 vs 固定注册 id

评审追问：两种形态在 UI、编排、内部逻辑上差在哪？最大差异与优劣？为何推荐结构节点？

**拍板用的一句话（最大差异）：**

| | 结构节点 + `hitlSpec` | 固定注册 id 节点 |
|---|---|---|
| **最大差异** | 配置与产出键属于 **「图上这个节点实例」** | 实现与键属于 **「全局那一个模板 id」** |

因此：要不要「一张图多个审核点、每点不同注入键」，直接决定形态。要 → 结构节点；只要全局单闸门且键写死 → 固定 id 才勉强够用。

**各自是什么（对照现网）**

| | 结构节点 + `hitlSpec` | 固定注册 id |
|---|---|---|
| 现网近亲 | `SUBGRAPH` / `SAA_WORKFLOW` / `GENERIC_AGENT`（`agentSpec`） | Spring `@Component` 的 `RegisteredGraphNode`，目录里一个固定 `nodeId`（如想象中的 `human_review`） |
| 身份 | 每个画布实例自己的 `nodeId`（如 `hitl:review_1`） | 注册表里一个（或少数）固定 `nodeId` |
| 配置存哪 | 图 JSON：`NodeRef.hitlSpec.injectKeys` | 主要在 `GraphNodeDescriptor`；动态键很难按实例区分 |
| 构建入口 | `DynamicGraphBuilder` **特判** HITL（类似 SAA） | `nodeRegistry.get(nodeId).toAction(...)` |

**UI 设计器交互**

- **结构节点**：面板常作「结构件/组件」入口；拖一次 = **新建一个实例**；可改本节点注入键与展示名，可 rename 成 `hitl:finance` / `hitl:legal`；一张图多个 HITL **不抢 id**；属性面板绑的是**当前选中实例**的 `hitlSpec`；Compile `interruptBefore` 勾的是这些**实例 id**。
- **固定注册 id**：出现在已注册节点列表，拖的是目录里那一个 id；同一图画第二次易 **id 冲突**，或改名后 registry 按新 id **找不到**实现（现网 builder 常按 `ref.nodeId` 查表）；`injectKeys` 写在 descriptor 上则 **全图同一套键**，很难「节点 A 注入 `approved_text`、节点 B 注入 `reject_reason`」。若靠「每建一个就再注册一条」硬撑多实例，交互像工厂，实现已不是「纯固定 id」。

**编排（图怎么画）**

- **结构节点**示例：`draft → hitl:approve_a → …` 与 `→ hitl:approve_b → …` 可并存；各点 `injectKeys` 可不同；`interruptBefore` 可只中断其中几个；下游 `inputKeys` 对齐**该实例**宣称的产出键。
- **固定注册 id**示例：`draft → human_review → final`；适合**全图一个审核闸门**；多闸门表达力弱。

**内部处理逻辑**

- **结构节点**：反序列化带 `category=HITL` + `hitlSpec` → Builder 走 HITL 分支生成透传/回声 Action → 可达性入边豁免、`outputKeysOf` = **该 Ref 的 `injectKeys`** → 校验该 `nodeId` ∈ `interruptBefore` → resume 写入后执行该实例再往后。
- **固定注册 id**：启动时 registry 一条静态 descriptor → 普通 `toAction` → 可达性读 **descriptor 静态键**（现网对在册 id 即如此）→ 若实例级键只放在 UI config、未进 descriptor，**校验与宣称产出会对不齐** → 多实例几乎必然滑向动态注册或改校验读 NodeRef，工作量接近结构节点却多一层 registry 生命周期。

**优劣**

| | 结构节点 + `hitlSpec` | 固定注册 id |
|---|---|---|
| 优势 | 多实例自然；键随节点；对齐 Spec 体系；校验可读 Ref；与自动并入 `interruptBefore` 干净 | 表面简单；像普通内置节点；「单审核点 + 键写死」首版够用 |
| 劣势 | Builder/校验/UI 要特判；前端多一套结构拖拽；概念略重 | 多实例/每点不同键几乎做不好；改名易与 registry 脱节；动态键与静态 descriptor 打架；硬撑多实例会演变成「伪工厂」，后期还要迁模型 |

**推荐结论（已拍板）：结构节点，而非纯固定注册 id。**

理由：Issue 1 的核心痛点是人工注入键要过连线校验，且常与上游 `draft` 等并存、还可能多段人工——这更像 `agentSpec` / `saaSpec`，不像全局单例模板。

**产品折中（已采纳）：** 对外面板一个「人工审核」拖拽入口（体验像固定组件）；**对内落盘仍是每实例 `hitlSpec`（结构）**。若产品曾明确「一张图最多一个 HITL 且键写死」，固定 id 可更短，但一旦要第二审核点就要推翻——故本刀不选。

#### 5.1.2 议题 #4 说明：HITL 执行时缺 key 为何「不抛错」

**已拍板（§9 #4）：缺 `injectKeys` 中某键时 HITL 节点不抛错；有则回声，无则跳过；缺键留给下游。**

**场景：** `interruptBefore` 停在 HITL **之前**。resume 时调用方本应在 `updates` 里写入 `injectKeys`（如 `approved_text`）。续跑后引擎**执行** HITL 节点时，正常情况 state 里已有这些键；HITL 只做回声，不调 LLM。

**若仍缺键**（忘传 `updates`、键名写错、未进 `keyStrategies` 被丢弃等），两种做法：

| 做法 | 表现 | 问题 |
|---|---|---|
| HITL **抛错中断** | 图在闸门节点失败；SSE/日志直接报缺 key | 把「人没填/调错 API」收成框架硬失败；与「人工可分步补全、下游再校验」松耦合相反；调试时不易区分「resume 没写上」vs「下游 Prompt 没绑」 |
| HITL **不抛错**（本刀） | 缺的键不写入返回 Map；图继续跑到下游 Agent | 下游 `inputKeys` 取不到则为空；Prompt 占位变空串或模型自述缺字段——**暴露在业务节点**，与现网 Agent 缺变量行为一致 |

**推荐不抛错的理由：**

1. **职责分离**：HITL 是闸门/键契约与校验豁免，不是业务必填校验器；必填应在编排（下游 Prompt/`inputKeys`）或调用方 API 约束上体现。  
2. **对齐 resume 时序**：值的权威来源是 resume `updateState`，不是 HITL `toAction` 新造；节点缺键多半是上游调用问题，用 ERROR 帧打死整图过重。  
3. **可观测**：日志已打「各键是否存在」；排障看 HITL 日志 + 下游空占位即可，不必再造一套 HITL 专用异常协议。  
4. **若将来要严**：可加节点开关 `strictInjectKeys=true`（缺则失败）——**本刀不做**，避免首版范围膨胀。

**使用表现举例：**

- resume 带了 `approved_text` → HITL 回声该键 → 定稿 Agent `{{approved_text}}` 有值。  
- resume 未带 → HITL 打 warn 日志「键 approved_text 缺失」但不炸 → 定稿 Agent 读到空 → 模型可能胡说/要字段（与今天样例图 Prompt 未绑时的现象同类）——**应修调用方 updates 或下游 Prompt，而不是指望 HITL 抛错**。

### 5.2 连线校验改造

| 规则 | 本刀 |
|---|---|
| 边 `* → HITL` | 继续豁免（已有 `CATEGORY_HITL` / 扩展：识别 `hitlSpec`） |
| `outputKeysOf(HITL)` | = `hitlSpec.injectKeys`（空则校验报「HITL 节点未配置 injectKeys」） |
| 边 `HITL → 下游` | 照常：下游 `inputKeys` ⊆ 上游累积 keys（含 HITL 注入键 + 更上游的 `draft` 等） |
| 前端 `edgeParamValidation.js` | 与后端规则对齐 |

### 5.3 与 `interruptBefore` 自动挂钩

**已拍板（§9 #2）：保存/发布时自动并入全部 HITL 节点 id；缺则 validate 失败。本刀不做「HITL 仅占位、不中断」开关。**

| 选项 | 含义 | 本刀决策 |
|---|---|---|
| A. 仅文档提示，用户手勾 | 实现最简单 | **不做为唯一手段** |
| B. 保存/发布时：所有 HITL 节点 id **并入** `interruptBefore`（去重，不删用户其它中断点） | 防漏配 | **做（已拍板）** |
| C. 节点属性「不中断，仅占位」开关 | 灵活 | **延后**；本刀所有 HITL 节点都应在中断点中 |

并入算法：`interruptBefore = unique(existing + allHitlNodeIds)`；设计器保存与后端 publish 校验一致（缺则 **校验错误**，草稿可不拦，与现网 draft 不跑可达性一致）。

#### 5.3.1 议题 #2 追问纪要：为何自动并入、含义与使用表现

评审追问：为何推荐「保存/发布自动并入全部 HITL 节点 id；缺则 validate 失败」？「自动并入」是什么意思？使用时是什么表现？

**推荐理由**

HITL **结构节点**的产品语义是「人工闸门」：画上去就表示要在这里停、等人改 state 再续跑。  
真正让引擎停住的却是另一处配置——图级 `compile.interruptBefore`（节点 id 列表）。两处若只靠人手对齐，最常见事故是：

- 画了 `hitl:review`，连线、注入键都对了；
- **忘了**把该 id 加进 Compile「中断点」；
- 发布成功 → 一跑图 **不停、直接冲过闸门** → 误判 HITL「坏了」。

「自动并入 + 缺则 validate 失败」把 **「有 HITL 节点 ⇒ 必须中断在它前面」** 收成硬约定，少靠记忆。

**「自动并入全部 HITL 节点 id」含义**

不是改引擎中断算法，而是 **写图 / 保存 / 发布时维护** `compile.interruptBefore` 这个字符串数组：

```text
interruptBefore_新 = 去重( interruptBefore_旧 ∪ 图上所有 HITL 节点的 nodeId )
```

示例：

| 时刻 | 画布 HITL | 用户手勾的 interruptBefore | 保存后实际写入 |
|---|---|---|---|
| 初始 | 无 | `[]` | `[]` |
| 拖入 `hitl:review_a` | `hitl:review_a` | `[]` | `["hitl:review_a"]` |
| 再拖 `hitl:review_b` | a、b | `["hitl:review_a"]` | `["hitl:review_a","hitl:review_b"]` |
| 用户另勾了 `agent:final_reply` | a、b | `["hitl:review_a","agent:final_reply"]` | `["hitl:review_a","agent:final_reply","hitl:review_b"]`（**不删**用户其它中断点） |

要点：

- **并入** = 并集，**不覆盖、不清空**用户对其它节点（含 Agent）的中断配置。
- **全部 HITL** = 图上每个 HITL 实例 id 都要在列表里（本刀：不允许「只占位、不中断」）。
- **缺则 validate 失败** = 发布/校验时若某 HITL id 不在 `interruptBefore` → **拒绝发布**，提示将 xxx 加入中断点或重新保存以自动并入。草稿是否拦：与现网「draft 不跑全量校验」一致——**draft 可松、publish 必严**。

**使用时表现**

设计器：

1. 拖一个「人工审核」→ 得到如 `hitl:review_1`。
2. 保存草稿 / 发布前规范化：Compile「中断点」**自动出现并勾上**该 id（若 UI 实时同步，拖完即可看见）。
3. 仍可额外勾 `agent:xxx`；不会被冲掉。
4. 若有人从中断点 **手工去掉** HITL id 再发布 → **校验失败**，须加回或删除该 HITL 节点。

运行时（宿主顶层 resume **已有**，行为不变）：

1. stream 跑到该 HITL **之前**暂停（`interruptBefore`：停在执行该节点前）。
2. `state.next` = 该 HITL 的 `nodeId`。
3. `POST .../resume`，`updates` 写入 `injectKeys`（如 `approved_text`）。
4. 续跑时 **先执行** 该 HITL（透传/回声）→ 再往后。

一张图两个 HITL 且都在 `interruptBefore` 中：按路径 **依次** 在各自节点前停（第一段 resume 后跑到第二段再停）——这是引擎多中断点的正常表现，不是自动并入的副作用。

**与「只手勾、不自动并入」对比**

| | 自动并入 + 缺则失败（已拍板） | 仅文档提示手勾 |
|---|---|---|
| 漏配中断 | 难（保存补上；发布还卡） | 很容易漏 |
| 多 HITL | 每个实例都会进列表 | 更容易漏某一个 |
| 灵活性 | 本刀：**不允许**「HITL 但不中断」 | 更灵活，也更易配错 |
| 心智 | 拖 HITL ≈ 声明闸门 | 拖节点 + 再记去勾 Compile，两步 |

若以后要「HITL 只做校验占位、故意不中断」，再加节点开关「不自动中断」——**延后**，本刀不做。

**一句话：** 自动并入 = 保存/发布时把所有 HITL 的 `nodeId` **写进** `interruptBefore`（与现有项合并）；使用上「拖了审核节点就会停在它前面」，不必再单独记勾中断点；故意拿掉则发布失败。

### 5.4 `keyStrategies`

每个 `injectKeys` 项若图根 `keyStrategies` 缺失：**保存/发布规范化时自动补 `REPLACE`**（与 SAA 未声明键自动补同思路；对齐 §7.1.5）。

| 选项 | 本刀决策 |
|---|---|
| 自动补 REPLACE | **做**（唯一手段） |
| 仅 warn / 仅校验失败要求手补 | **不做** |

### 5.5 设计器 UI

| 位置 | 控件 |
|---|---|
| 节点面板 | HITL 分类可拖（紫配色沿用现网） |
| 节点属性页 | 「人工注入键」多行/标签输入 → `injectKeys`；禁止空列表保存发布 |
| Compile 页 | 现有 `interruptBefore` / `interruptContent`；HITL 并入后多选框应能看到该节点 id |

i18n：`zh-CN` / `en-US` 补标签与校验文案。

### 5.6 框架改动面（预期文件）

```
ace-graph-dsl-core
  + definition/HitlNodeSpec.java（或 record injectKeys）
  ~ definition/NodeRef.java              + hitlSpec
  ~ builder/DynamicGraphBuilder.java     识别 HITL，挂透传 NodeAction
  ~ builder/EdgeParamReachabilityValidator.java
  ~ builder/GraphValidator.java          HITL 须在 interruptBefore；injectKeys 非空
  + nodes/HitlReviewNodeAction 或等价
  单测：可达性、并入 interruptBefore、round-trip JSON

ace-graph-dsl-ui
  ~ NodePanel / DspNode / PropertyPanel
  ~ edgeParamValidation.js / graphEditor 序列化
  ~ i18n
```

**默认不改（§9 #6）：** 宿主 `AceGraphExecutionController.resume` 语义、平台结束帧协议、子图 resume；遇阻塞点重评后再动。

### 5.7 跨仓依赖顺序与宿主改动边界

```
1. 切 ace-graph-dsl → feature-hitl-node-factory → 按 §5 实现 → 单测 → 发版
2. 切 lesso-ai-project → feature-hitl-node-factory-host-bump
   → 升 pom / 设计器 npm →（可选）改 test-HITL-graph 拓扑为 §4.2
   → 测环境部署烟测
```

业务分支**禁止**塞进 Lesso 聊天 resume / 子图 metadata（那些是另 Issue）。

**已拍板（§9 #5）：** 业务侧仅跟版分支，不对位开大 Issue。  
**已拍板（§9 #6）：** 默认**不改**宿主 resume / 平台 SSE 等运行时代码；若实现或联调出现「不改宿主就无法完成 §7 验收」的阻塞点 → **停止扩 scope，重新评审 §9 #6** 后再动宿主逻辑（禁止静默加协议）。

---

## 6. 非目标

| 项 | 归属 |
|---|---|
| 子图 HITL state/resume | 另 Issue（框架契约 + 宿主 WebFlux） |
| 聊天 Lesso resume + threadId 透出 | 另 Issue |
| Plan Mode / 敏感工具运行时审批产品化 | 另议题 |
| Agent 多 outputKey / JSON 拆键 / `{{a.b}}` | 不做 |
| 持久化 Checkpoint saver | 另项 |
| 废除「对 Agent 勾 interruptBefore」 | **不废除**，双轨保留 |

---

## 7. 验收清单

### 7.1 框架

| # | 项 | 本地（2026-11-06） | 测环境（2026-11-06） |
|---|---|---|---|
| 1 | 可拖 HITL；`injectKeys` 保存/发布/拉回不丢 | ✅ | ✅（跟版图仍含 HITL） |
| 2 | `起草→HITL→定稿` 连线校验通过；定稿 `inputKeys` 含 `approved_text` | ✅（样例节点已改为 `approved_text,draft`） | ✅ |
| 3 | HITL 未进 `interruptBefore` → validate/publish 失败 | ✅（单测 + 保存自动并入） | ✅（断点 `next=hitl_…`） |
| 4 | `injectKeys` 空 → 校验失败 | ✅ | —（样例已配键） |
| 5 | 自动补 `keyStrategies` REPLACE | ✅ | ✅ |
| 6 | 单测：可达性豁免、HITL 跳过「节点未注册」、回声 | ✅ | — |

### 7.2 业务跟版

| # | 项 | 本地 | 测环境（2026-11-06） |
|---|---|---|---|
| 1 | 升版后设计器可见 HITL 节点 | ✅（曾 `file:`；正式 npm `1.0.6`） | ✅（宿主跟版已部署） |
| 2 | `test-HITL-graph`：stream 断在 HITL → resume 写 `approved_text` → END；定稿读到占位值 | ✅（见 §7.4） | ✅（见 §7.4.1） |

### 7.3 回归

- 无 HITL 节点的旧图（仅 Agent + `interruptBefore`）行为不变。  
- 顶层 resume、平台结束帧 interrupt 回归不破坏（本地续跑未改宿主协议）。

### 7.4 本地联调证据（2026-11-06）

| 项 | 内容 |
|---|---|
| 图 | `test-HITL-graph`：`draft_reply → HITL(injectKeys=[approved_text]) → final_reply`；`interruptBefore=[hitl_…]` |
| 通路 | stream 断在 HITL → `POST .../resume` + `updates.approved_text` → `__END__` |
| 定稿配置（关键） | 注册节点 `agent:final_reply`：`inputKeys=approved_text,draft`；Prompt 使用 `{{approved_text}}` / `{{draft}}`（**勿**只写「请读 state」） |
| 结果 | END `approved_text` = 人工稿；`final_reply` 以人工稿为主（可微调标点） |
| 踩坑 | ① `GraphValidator` 曾对 HITL 报「节点未注册」→ 已豁免结构节点；② resume/HITL 已写 state 但定稿未配 inputKeys/占位 → 模型口头「缺字段」——属节点配置，非 resume 失败 |

### 7.4.1 测环境证据（2026-11-06，`172.16.90.28` Nacos middleware）

| 步骤 | 结果 |
|---|---|
| `POST .../stream-agent/test-HITL-graph/bank-interest-fee-verifier` | 200；起草流式完成 |
| `GET .../state/{threadId}`（断点） | `exists=true`，`next=hitl_1793925635037`，`node=agent:draft_reply`，`draft` 已在 state |
| `POST .../resume` + `updates.approved_text`（代金券人工稿） | 200；续跑至 `__END__`；`data.approved_text` = 人工稿；`final_reply` 以人工稿为主 |
| `GET .../state/{threadId}`（结束后） | `next=__END__`，`node=agent:final_reply`；`approved_text` / `final_reply` / `draft` 均在 |

样例 `threadId`：`f464d9f0-7c57-4109-882e-73677969dc46`。

### 7.5 发版收口（本刀剩余）

```
1. 框架：后端 1.1.32 → **已 deploy mesrelease**（2026-11-06）
2. 框架：UI **1.0.6** → **已 npm publish**
3. 宿主 feature-hitl-node-factory-host-bump：pom 1.1.32；设计器 npm **1.0.6** → **已上测并验收**
4. MR/PR 合入主线（框架 + 宿主）→ **已完成**
```

---

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 用户以为 HITL 节点会「自己弹出审核框」 | 文档写明：审核 UI = 设计器试跑 / 调用方 resume；聊天另 Issue |
| 只配 HITL 忘配 `interruptBefore` | §5.3 自动并入 + validate |
| `injectKeys` 未进 keyStrategies 丢更新 | §5.4 自动补 REPLACE |
| 与「中断在 final_reply 前」旧样例并存混淆 | 样例迁移说明；双轨保留 |
| 业务侧误开大分支改协议 | 分支名与 §5.7 限定跟版 |

---

## 9. 评审拍板（#1～#6 已齐）

| # | 议题 | 结论 | 详细依据 |
|---|---|---|---|
| 1 | 节点形态 | **结构节点**：`NodeRef.hitlSpec`（多实例）；**不做**纯固定注册 id。面板可做一个「人工审核」入口，对内仍按结构落盘 | **§5.1.1** |
| 2 | `interruptBefore` | **保存/发布自动并入**全部 HITL 节点 id（并集、不删用户其它中断点）；缺则 **validate/publish 失败**；本刀不做「不中断」开关 | **§5.3.1** |
| 3 | 缺省注入键 | **不设死**框架默认键；用户在节点上自填 `injectKeys`；样例图使用 `approved_text` | 方案默认，本轮确认 |
| 4 | HITL 执行缺 key | **不抛错**；有则回声、无则跳过；缺键留给下游；本刀不做 `strictInjectKeys` | **§5.1.2** |
| 5 | 业务侧 | **仅** `feature-hitl-node-factory-host-bump` 跟版（升依赖/可选改样例）；不对位大 Issue | **§5.7** |
| 6 | 宿主代码 | **默认不改** resume / 平台 SSE 等逻辑。若出现不改宿主则无法完成验收的**阻塞点** → **停刀并重新评审本条**，禁止静默扩宿主 scope | **§5.7** |

拍板已齐；框架实现与本地验收见文首状态与 §7.4；剩余发版见 §7.5。

---

## 10. 与已落地 HITL 文档的关系

| 文档 | 关系 |
|---|---|
| 宿主 `HITL-resume-WebFlux缺口与方案.md` | 顶层断点/resume/**已落地**；其 §11.1「HITL 类型节点工厂」= **本文**（本地已验，待发版合码） |
| 本文 | 补齐**可拖节点 + 注入键 + 校验**产品化；依赖前者，不重做 resume |

---

## 11. 建议 Issue 正文（粘贴用）

```markdown
## 背景
顶层 interruptBefore + 宿主 resume 已落地。CATEGORY_HITL 仅有校验豁免与 UI 壳，无可拖节点与人工注入 key 契约，导致 approved_text 等键无法干净通过连线校验。

## 目标
见方案 ace-graph-dsl/docs/HITL-节点工厂-方案.md §4～§5。
分支：feature-hitl-node-factory；业务跟版：feature-hitl-node-factory-host-bump。

## 非目标
子图 HITL、Lesso 聊天 resume、Agent 多 outputKey。

## 验收
方案 §7。
```
