# ace-graph-dsl：state 键、inputKeys、连线校验与运行时缺值

> **给谁看**：维护 `ace-graph-dsl` / 设计器 / 业务宿主（如 lesso agent-server）的人与 Agent。  
> **一句话**：连线校验管「管道通不通」；运行时管「这次管子里有没有水」——没水不应炸管。

---

## 1. 先建立直觉

把图想成一条流水线，每个节点桌上有若干**水桶标签**（state 键名）：

| 概念 | 通俗说法 |
|------|----------|
| state 键 | 水桶上的名字，如 `user_query`、`fee_type`、`form_data` |
| 节点 `inputKeys` | 「这个工位打算从桌上哪些桶里舀水」 |
| 节点 `outputKey(s)` | 「这个工位往桌上哪些桶里倒水」 |
| 连线参数可达性校验 | 设计期检查：下游要舀的桶，上游路径上有没有人**声明过**会往这些桶里倒 |
| 运行时缺值 | 这次请求里某个桶是空的，或根本没人倒过——**允许**，提示词对应位置当空白即可，**不能**因此整条流水线停机崩溃 |

**不要混为一谈：**

- 「校验通过」≠「运行时每个 inputKey 都有非空值」
- 「运行时某个 key 为空」≠「连线配错了」

---

## 2. 设计期：连线校验在查什么

实现：`EdgeParamReachabilityValidator`（发布/点「校验」会跑；存草稿通常不拦）。

规则大意：

1. 从 `__START__` 沿边往前走，一路累积各节点声明的 **output 键名**。
2. 每到一个下游节点，看它的 **inputKeys** 是否都已出现在「累积集合」里。
3. **`__START__` 的产出集合视为空**：HTTP 入口写进 state 的字段（如 `user_query`、`media_refs`）**默认不算**「上游节点产出」。

因此常见做法：

- 入口字段若要给下游 Agent 用，且要通过校验：在 `__START__` 后加 **seed/透传脚本节点**，把这些键再 **Output Keys 声明一遍**（值可原样回写）。
- 或保证真正有 `outputKey` 的上游节点产出下游需要的名字。

条件边：一条边上写 **返回 mapping key 的表达式** + 多行 mapping；不是「每条出边各自写一段布尔条件」。

---

## 3. 运行期：缺值应该怎样

入口（业务宿主）把请求映射进 **OverAllState**，例如：

- `query` → `user_query`
- `images`/`files` → `media_refs`
- `extra_params` → `extra_params`
- 可选 query `fileNames` → `fileNames`

之后整图共享同一份 state：后面的节点**不会**因为只写了 `fee_type` 就把前面的 `user_query` 清掉（透传是运行时事实）。

节点执行时（GenericAgent）：

1. 按 `inputKeys` 从 state **尝试读取**；
2. 组成 `variables`，交给 `PromptRenderer` 填 `{{key}}`；
3. **某个 key 不存在或值为空**：提示词里该占位应变成空串（可打 warn），**不得**因 `Map.copyOf` 遇 null 而 NPE。

维护框架时必须守住这条不变量：

> **inputKeys 声明的是「读哪些名字」，不是「每个名字运行时都必须非 null」。**

---

## 4. 两阶段对照（给 Agent 做决策用）

| 问题 | 设计期（校验） | 运行期（执行） |
|------|----------------|----------------|
| 问什么 | 这个键名在路径上是否**可达/已声明** | 这次执行 state 里有没有值 |
| 失败表现 | 「连线参数不可达」「条件边需指定 condition」 | 提示词空、业务降级；**不应**整图 NPE |
| 入口 HTTP 字段 | 默认不计入 START 产出 | 已在 prepare 写入 state，后续节点可读 |
| 可选业务字段（如 `form_data`） | 若写进下游 inputKeys，需有人声明产出（常靠 seed） | 本次没传 → 空；图继续跑 |

---

## 5. 维护 ace-graph-dsl 时的硬规则

改 `GenericAgentNode` / `LlmCallRequest` / `PromptRenderer` / `EdgeParamReachabilityValidator` 时：

1. **禁止**假设 `variables` 里每个 inputKey 都有非 null value。  
2. 组装不可变 Map 时：**跳过 null value**，或根本不要 put 缺省 key（与 Prompt「缺 key → 空串」一致）。  
3. 收紧连线校验时：想清楚是否误伤「入口字段 + seed 声明」模式；不要把「运行时必须有值」写进校验器。  
4. 条件边脚本路由：`condition` 非空且返回 **mapping 的 key 字符串**；`dispatcher` 与 `condition` 二选一（有 dispatcher 时 dispatcher 优先）。  
5. 业务图排障：先分清是「校验红字」还是「运行 NPE/空结果」，再分别查边配置 vs Template/variables。

---

## 6. 相关代码与案例

| 主题 | 位置 |
|------|------|
| 连线可达性 | `ace-graph-dsl-core` → `EdgeParamReachabilityValidator` |
| 变量组装 | `ace-graph-dsl-ai` → `GenericAgentNode.toAction` |
| 请求封装 | `ace-graph-dsl-ai` → `LlmCallRequest`（须容忍缺值） |
| 提示词填充 | `ace-graph-dsl-core` → `PromptRenderer`（`{{key}}` / 缺值空串） |
| 案例（NPE） | 业务仓 `lesso-ai-platform-agent-server/docs/troubleshooting/2026-10-15-bank-fee-check-interest-rate-npe.md` |

---

## 7. 三句话背下来

1. **校验**：下游要的 key，上游路径有没有**报过名**。  
2. **运行**：这次 key 有没有值——可以没有。  
3. **框架**：没值只影响提示词内容，**不能**让整张图因 null 崩溃。
