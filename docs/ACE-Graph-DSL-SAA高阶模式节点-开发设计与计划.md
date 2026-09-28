# ACE Graph DSL：SAA Agent Framework 高阶模式节点 — 开发设计与计划

> 文档类型：开发设计 + 实施计划  
> 状态：草案（可评审 / 可排期）  
> 日期：2026-09-28  
> 范围：`SAA_WORKFLOW` 高阶节点（Sequential / Parallel / Routing / Loop）  
>  
> **口径前提（必读）：**  
> - [FAQ 与统一口径](./ACE-Graph-DSL-多智能体FAQ与统一口径.md)  
> - [高阶模式集成目标说明](./ACE-Graph-DSL-高阶模式集成目标说明.md)  
>  
> **上游文档：**  
> - [中期规划](./ACE-Graph-DSL-多智能体中期规划.md)  
> - [初步技术方案](./ACE-Graph-DSL-多智能体初步技术方案.md)  
> - [内核选型](./ACE-Graph-DSL-多智能体内核选型.md)

---

## 1. 目标与边界

### 1.1 目标

在 **不改变 ACE 图主编排模型** 的前提下，新增可选高阶节点类型：

| 目标 | 说明 |
|------|------|
| 产品 | 设计器可拖入「多智能体模式」节点，配置四种 pattern + 子 Agent 引用 |
| 运行时 | 编译期由框架组装 SAA FlowAgent，业务无需手写 `*.builder()` |
| 兼容 | 旧图零迁移；未引入实现模块时行为与今天一致 |
| 资源 | 子 Agent 能力 Spec 在 ACE 目录；模型/Prompt/MCP/Skill **按 key** 经 SPI 解析（Nacos/K8s 由宿主实现，框架不绑死） |

### 1.2 非目标（本计划不做）

- 用 Framework / AgentScope Pipeline 替换图边主编排  
- 整图（如 `ztc-service-agent`）收成一个 SequentialAgent  
- 编译期把高阶节点展开成多条普通边  
- `subAgents` 内嵌完整 `GenericAgentSpec`  
- 中期交付 Supervisor / Debate / A2A / 模式市场  
- 父节点内嵌子画布编辑  

### 1.3 成功标准（整体）

对齐中期规划 AC-1～AC-7，摘要为：

1. 旧图无改可编译执行  
2. 四种 pattern 可配置、可校验、可试运行、可发布  
3. 子 Agent 默认 `generic:{id}`，资源 key 与单节点路径一致  
4. 文档与 UI 文案能说清：**图管阶段 / 节点管内协作**  

---

## 2. 架构设计

### 2.1 分层

```text
┌─────────────────────────────────────────────────────────────┐
│  设计器 UI                                                   │
│  高阶模式节点 + pattern 表单 + 子 Agent 引用下拉               │
└────────────────────────────┬────────────────────────────────┘
                             │ GraphDefinition JSON
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  ace-graph-dsl-core                                          │
│  SaaWorkflowSpec / Validator / SaaWorkflowNodeFactory(接口)  │
│  SubAgentResolver(接口) / DynamicGraphBuilder 分支           │
└────────────────────────────┬────────────────────────────────┘
                             │ ObjectProvider（可选）
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  ace-graph-dsl-saa-agent（新，可选模块）                       │
│  FactoryImpl → Sequential/Parallel/Routing/LoopAgent         │
│  GenericAgentSubAgentResolver → 包装 GraphBoundAgentNode     │
└────────────────────────────┬────────────────────────────────┘
                             │ 子 Agent 执行时
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  已有资源 SPI（宿主实现）                                      │
│  ModelMountResolver / PromptContentResolver /                │
│  McpToolResolver / Skill…  ← 按 key 取资源（Nacos 或 K8s 等） │
└─────────────────────────────────────────────────────────────┘
```

**原则：** core 不依赖 `agent-framework`；宿主显式引入 `ace-graph-dsl-saa-agent` 才启用高阶节点。

### 2.2 与图级原语的关系（防混）

| 能力 | 实现位置 | 本计划 |
|------|----------|--------|
| 阶段串行 / FanOut / 条件边 | 已有图边 | **不改、不替代** |
| 节点内 Sequential/Parallel/Routing/Loop | 本计划高阶节点 | **新增** |
| 子 Agent 节点 Spec | ACE GenericAgent 注册目录 | **复用** |
| 模型等资源 | 按 key → 宿主 Resolver | **复用，不新建目录** |

### 2.3 Spec 三层（父 / 子 / 绑定）

```text
saaSpec（父）           pattern + inputKeys + outputKey + Loop/Routing 编排字段
subAgents[].ref         → ACE 目录 GenericAgentSpec（能力：prompt/model/mcp/skill keys）
subAgents[] 绑定字段     name / instruction / outputKey / impl
```

资源解析：**业务编排只配 key；宿主提供按 key 获取的实现**（Nacos 或其它注册中心对框架透明）。

---

## 3. 模块与包设计

### 3.1 Maven 模块

```text
ace-graph-dsl-backend/
  ace-graph-dsl-core              // 规格、SPI、校验接口、Builder 分支（无 Framework 依赖）
  ace-graph-dsl-ai                // 现有 GenericAgent（被子 Resolver 复用）
  ace-graph-dsl-saa-agent         // 【新建】依赖 spring-ai-alibaba-agent-framework
  ace-graph-dsl-agentscope-agent  // 【可选，M3】AgentScope 子 Agent 实现
  ace-graph-dsl-spring-boot-starter // 不强制传递 saa-agent；宿主按需依赖
ace-graph-dsl-ui                  // 节点面板 + 属性表单
```

`pom` 约束：

- `saa-agent` 与现有 `graph-core` **同一 BOM**  
- starter 默认 **不** 传递 `ace-graph-dsl-saa-agent`  
- 未引入模块时：图含 `SAA_WORKFLOW` → 编译期明确错误：「未启用多智能体高阶节点模块」

### 3.2 core 新增类型（建议包路径）

| 类型 | 包 / 类 | 职责 |
|------|---------|------|
| 常量 | `GraphNodeDescriptor.CATEGORY_SAA_WORKFLOW` | 节点 category |
| 规格 | `definition.SaaWorkflowSpec` | pattern、键、subAgents、Loop/Routing 字段 |
| 子规格 | `definition.SaaSubAgentRef` | name/impl/ref/instruction/outputKey |
| 枚举 | `definition.SaaWorkflowPattern` | SEQUENTIAL / PARALLEL / ROUTING / LOOP |
| 工厂 SPI | `agent.SaaWorkflowNodeFactory` | `create(...)` → 可挂载节点 |
| 解析 SPI | `agent.SubAgentResolver` | `supports` / `resolve` |
| 绑定 | `agent.SubAgentBinding` | name、outputKey、可执行句柄 |
| 校验 | `validation.SaaWorkflowValidator` | 接入现有图校验链 |
| 挂载 | `agent.SaaWorkflowNode`（或适配结果） | **现行：`toAction()` → NodeAction（方式 A）**；方式 B 仅作后续增强 |

`NodeRef`：增加可选 `saaSpec`；与 `agentSpec` 互斥（`SAA_WORKFLOW` 只读 `saaSpec`）。

### 3.3 saa-agent 模块职责

| 类（建议） | 职责 |
|------------|------|
| `SaaWorkflowNodeFactoryImpl` | 按 pattern 组装 FlowAgent + **包成 NodeAction（方式 A）**；预留 B 升级点 |
| `GenericAgentSubAgentResolver` | `impl=GENERIC_AGENT`，`ref=generic:{id}` |
| `FlowAgentStateBridge` | OverAllState ↔ Framework 输入/输出键映射 |
| `SaaAgentAutoConfiguration` | 注册 Factory / Resolver；日志打印模块启用 |
| `*PatternAssembler` | Sequential / Parallel / Routing / Loop 组装（可内聚在 Factory） |

关键日志（必须）：

- 模块启用 / 未启用  
- 编译高阶节点：`graphId`、`nodeId`、`pattern`、子 Agent 列表  
- 每个子 Agent 开始/结束：name、耗时、成功/失败、outputKey  
- 写回父 `outputKey` 前后  
- 校验失败原因  

---

## 4. DSL 与编译设计

### 4.1 节点 JSON 骨架

```json
{
  "nodeId": "sql_quality",
  "category": "SAA_WORKFLOW",
  "config": { "label": "SQL 生成与评分" },
  "saaSpec": {
    "pattern": "SEQUENTIAL",
    "inputKeys": "user_query",
    "outputKey": "sql_score",
    "streamResponseKind": "BIZ",
    "modelConfigKey": "nacos_agent_node",
    "maxIterations": 3,
    "exitConditionKey": "score",
    "exitConditionOp": "GT",
    "exitConditionValue": "0.5",
    "subAgents": [
      {
        "name": "sql_generator",
        "impl": "GENERIC_AGENT",
        "ref": "generic:sql-gen",
        "instruction": "{user_query}",
        "outputKey": "sql"
      },
      {
        "name": "sql_rater",
        "impl": "GENERIC_AGENT",
        "ref": "generic:sql-rater",
        "instruction": "SQL:\n{sql}\n请求:\n{user_query}",
        "outputKey": "score"
      }
    ]
  }
}
```

字段适用性：

| 字段 | pattern | 说明 |
|------|---------|------|
| `pattern` / `inputKeys` / `outputKey` / `subAgents` | 全部 | 必填骨架 |
| `modelConfigKey` | 主要 ROUTING | 路由器模型；子模型仍走各自目录 Spec |
| `maxIterations` / `exitCondition*` | LOOP | 上限默认 3，硬上限 10 |
| `streamResponseKind` | 全部 | 父节点统一 BIZ/OUTPUT |

### 4.2 DynamicGraphBuilder 分支

位置：现有 GenericAgent 判断之后、通用 `nodeRegistry.get` 之前。

```text
if category == SAA_WORKFLOW:
    factory = ObjectProvider<SaaWorkflowNodeFactory>
    if empty → 抛「模块未启用」
    node = factory.create(graphId, nodeId, saaSpec, ctx)
    return node_async(node.toAction(nodeCtx))   // 【定案】方式 A；勿在 M1 改走 B
```

### 4.3 编译步骤（FactoryImpl）

1. `SaaWorkflowValidator` 校验规格  
2. 逐个 `SubAgentResolver.resolve` → 有序 `SubAgentBinding`  
3. 按 pattern 构建 FlowAgent：  
   - `SEQUENTIAL` → `SequentialAgent`  
   - `PARALLEL` → `ParallelAgent`  
   - `ROUTING` → **Framework 自带路由类**（Q3 定案；常见名 `LlmRoutingAgent`，以 BOM 为准，见 §4.6）  
   - `LOOP` → `LoopAgent`（body = 单子或内嵌 Sequential）  
4. **方式 A：** 将 FlowAgent 包成 `NodeAction`（见 §4.4）  
5. 状态桥：进入读 `inputKeys`，退出写父 `outputKey`  
6. 子中间键：可写入同一 `OverAllState`；下游边只应依赖父 `outputKey`（读子键 → 警告）  

### 4.4 挂载方式定案（Q1）：先 A，确认可导出再升 B

> **产品形态不变：** 高阶模式节点仍是 ACE 图上的 **一个** `addNode`；  
> Q1 **不是**「要不要挂进图 / 另走一套编排」，只是编译时的 **技术胶水** 选 A 还是 B。

#### 定案（2026-09-28）

| 项 | 结论 |
|----|------|
| **现行交付（M0～M2 必达）** | **方式 A：`NodeAction` 适配器** |
| **后续增强（不阻塞模式交付）** | 确认 FlowAgent 可 `asNode()` / 导出 `CompiledGraph` 后，再升 **方式 B** |
| **DSL / 设计器** | **不随 A→B 变更**；仅运行时挂载实现切换 |
| **禁止理解** | 「先 A」≠「高阶节点不进 ACE 图」；「升 B」≠「换成另一套主编排」 |

#### 两种方式对照（防偏）

```text
ACE StateGraph（主编排，始终只有这一张业务图）
   │
   ├─ intent_node      → NodeAction(GenericAgent)     // 已有
   ├─ sql_quality      → ???                          // SAA_WORKFLOW
   │                      │
   │                      ├─【A 现行】NodeAction {
   │                      │     读 OverAllState.inputKeys
   │                      │     flowAgent.invoke / stream(...)
   │                      │     写回 OverAllState.outputKey
   │                      │   }
   │                      │
   │                      └─【B 增强】addNode(id, flowAgent 导出的 CompiledGraph)
   │                            与现有 SUBGRAPH 挂载同类
   └─ output_node      → NodeAction(...)
```

| | **A. NodeAction 适配器（现行）** | **B. 子图 CompiledGraph（增强）** |
|--|--------------------------------|----------------------------------|
| 调用面 | Framework 暴露 `invoke` / `stream` 即可 | 需 FlowAgent 暴露 `asNode()` 或可导出 `CompiledGraph` |
| ACE 所见 | 普通异步 `NodeAction`，与 GenericAgent 同挂载风格 | 嵌套 `CompiledGraph`，与 `SUBGRAPH` 同类 |
| 优点 | 落地快、BOM API 要求低、不阻塞四模式 | checkpoint / 子图轨迹更易与 Framework 内部图对齐 |
| 代价 | 子步骤轨迹需自建桥（M1 日志，M4 UI）；内部图对 ACE checkpoint 较「黑盒」 | 依赖官方导出 API；适配与版本绑定更紧 |
| 何时用 | **默认；M1 起按此实现** | **仅当** spike/调研确认可导出且收益明确后再升 |

#### 方式 A 适配器职责（实现要点）

1. `apply(OverAllState)`（或 async 等价）：按父 `inputKeys` 组装 FlowAgent 输入。  
2. 调用 `SequentialAgent` / `ParallelAgent` / … 的 `invoke` 或 `stream`。  
3. 从 Framework 结果 / 内部 state 取出约定值，`return Map` 写入父 `outputKey`（及允许的子中间键）。  
4. 异常须带 `graphId`、`nodeId`、`pattern`、子 Agent `name`。  
5. 流式：以父 `streamResponseKind` 为准聚合，避免每个子 Agent 各推一条 OUTPUT。  

#### M0 Spike 调整后的目标

M0 **不再**「二选一才开工」，而是：

| 必达 | 可选（记入附录 A，不阻塞 M1） |
|------|------------------------------|
| 用 **方式 A** 跑通：SequentialAgent + 2 stub 子 Agent → ACE StateGraph → 写回 OverAllState | 探测当前 BOM 是否已有 `asNode` / 导出 `CompiledGraph`；有则记「可升 B」条件与风险 |
| 第二步能读第一步写入的 key；异常含子 Agent 名 | — |
| 不引入 AgentScope；结论写入附录 A | — |

**未通过「方式 A 可跑」则不排 M1 UI；「尚不能导出 B」不阻 M1。**

### 4.5 校验规则（发布阻断）

| 规则 | 级别 |
|------|------|
| pattern 不在开放集合 | 错误 |
| subAgents 空 | 错误 |
| ref 前缀与 impl 不一致 | 错误 |
| `generic:{id}` 不存在或未启用 | 错误 |
| LOOP 缺退出条件 / maxIterations | 错误 |
| maxIterations > 10 | 错误 |
| 子 outputKey 重名 | 错误 |
| ROUTING 子 Agent &lt; 2 | 错误 |
| 下游读子中间键 | 警告 |
| impl=AGENTSCOPE 无 Resolver | 错误 |
| 模块未启用却使用 SAA_WORKFLOW | 错误 |

### 4.6 Q3 定案：Routing 用 Framework 自带路由类（不造轮子）

> **直白话：** `pattern=ROUTING` 时，Factory 里组装的是 **SAA 自带的路由 Agent**，  
> 不是自己写一套「LLM 选子 Agent」，也不是拿 **图条件边** 冒充节点内 Routing。  
> 文档里的 `LlmRoutingAgent` 是常见叫法；**确切类全名以所用 BOM 为准**，M2 开工前写入附录 A。

#### 定案（2026-09-28）

| 项 | 结论 |
|----|------|
| **实现原则** | **只用** `spring-ai-alibaba-agent-framework`（同 BOM）提供的 Routing / LlmRouting 类 |
| **禁止** | 自研「路由 NodeAction」重复实现选路；用图 `conditional` 边「包一层」冒充 `SAA_WORKFLOW`+ROUTING |
| **类名落地** | M2 前对照 BOM 确认 FQCN（可能是 `LlmRoutingAgent` 或文档/源码中的等价类），记入附录 A；**原则已定，不因类名微调重开产品讨论** |
| **DSL / 设计器** | 仍只暴露 `pattern=ROUTING` + 候选 `subAgents` + 路由器 `modelConfigKey`；不暴露「自研/官方」开关 |

#### 与图条件边的边界（防偏）

```text
图级条件边（已有，继续用）:
  意图节点 ─cond─► HR 业务节点 / IT 业务节点     ← 分流的是【图上阶段节点】

节点内 Routing（本计划，用 Framework 类）:
  SAA_WORKFLOW pattern=ROUTING
       官方 RoutingAgent ─选─► subAgent hr | it   ← 分流的是【同一节点内的子 Agent】
```

| 做法 | 是否允许 | 说明 |
|------|----------|------|
| Factory → Framework `LlmRoutingAgent`（或 BOM 等价类）+ Q2 包装后的 subAgents | **现行必达** | 正道 |
| 自写 LLM 调用，解析 JSON 再 if-else 调子 Agent | **禁止作 ROUTING 实现** | 造轮子，与 SAA 升级脱节 |
| 图上 conditional 边完成阶段分流 | **允许，但是图级能力** | 不要标成 `SAA_WORKFLOW`/`ROUTING` |
| 「条件边 + 空高阶节点」假装节点内 Routing | **禁止** | 分层与观测都会乱 |

#### Factory 组装要点（ROUTING）

1. `pattern=ROUTING` → 实例化 **BOM 确认的 Framework 路由类**（builder）。  
2. 路由器模型：父 `saaSpec.modelConfigKey` → 宿主 ModelMountResolver（与单节点相同按 key）。  
3. 候选：`subAgents[]` 经 Q2 官方包装后传入；校验候选 ≥ 2。  
4. 选中子 Agent 执行后，结果写入父 `outputKey`（及约定键）。  
5. 日志：路由决策结果（选中了哪个 `name`）、耗时、失败原因。  

#### M2 对 Q3 的落地检查

| 项 | 动作 |
|----|------|
| 打开所用 BOM 的 Framework 源码/文档 | 记下 Routing 类全名与 builder API |
| 写入附录 A「Routing 类 FQCN」 | 与代码 import 一致 |
| 代码审查 | 禁止出现自研选路主路径；条件边仅用于图级场景 |

---

## 5. 子 Agent 与资源解析设计

### 5.1 GENERIC_AGENT（默认，M1）

```text
ref = generic:{registeredNodeId}
  → GenericAgentNodeService / Registry
  → GraphBoundAgentNode.withGraphId(graphId)
  → 【Q2 定案】实现官方 Agent（或 BOM 文档要求的 subAgent 类型）的薄适配器
  → instruction 占位符从 state 渲染
  → 内部仍调 GenericAgent 执行路径（MCP/Prompt/Model 按 key 走宿主 SPI）
  → 结果写入绑定 outputKey
```

- **不复制** 目录里的 prompt/mcpKeys  
- **禁止** 中期内联完整 agentSpec 作子 Agent  
- MCP session / threadId **与父图共用**（禁止子 Agent 孤立会话）  

### 5.2 Q2 定案：GenericAgent → FlowAgent.subAgent（优先官方 Agent 接口）

> **直白话：** SAA 的 `subAgents(...)` 要的是 Framework 认的类型，不是 ACE 的 `GenericAgentNode`。  
> Q2 = 用哪张「包装纸」把 GenericAgent 塞进列表。  
> **与 Q1 无关：** Q1 = 整个高阶节点怎么挂进 ACE 图；Q2 = 节点肚子里每个子 Agent 怎么变身。

#### 定案（2026-09-28）

| 项 | 结论 |
|----|------|
| **优先路径** | 实现 SAA **官方 `Agent`（或当前 BOM 文档规定的 subAgent 接口/基类）** 的适配器；内部委托现有 GenericAgent |
| **禁止作主路径** | 反射调用 Framework 内部类、依赖未文档化 API、复制一套 Prompt/MCP 逻辑绕过 GenericAgent |
| **兜底** | 仅当 M0 证明官方接口在所用 BOM 上无法满足（无法实现 / 无法传 instruction·outputKey / 无法共用 MCP session）时，才评估受控变通，并 **必须** 写入附录 A、单独立项评审 |
| **产品/DSL** | **不变**：仍是 `ref=generic:{id}` + 绑定层 instruction/outputKey |

#### 对照（防偏）

```text
ACE 图
  └─ SAA_WORKFLOW          ← Q1：外面 NodeAction 挂 FlowAgent（已定 A）
        └─ SequentialAgent
              ├─ subAgent A  ← Q2：外面 = 官方 Agent 形态
              │                 里面 = GenericAgent（目录 Spec + 按 key 取资源）
              └─ subAgent B  ← 同上
```

| | **官方 Agent 接口（现行优先）** | **非官方变通（仅兜底）** |
|--|-------------------------------|-------------------------|
| 做法 | 类实现/包装为 Framework 公开的 `Agent`（或文档要求类型），`call`/`invoke` 内走 GenericAgent | 反射、内部 Builder、或绕过 GenericAgent 自拼 ReactAgent |
| 优点 | 升级 BOM 可预期；与 SAA 示例同构；MCP/记忆边界清晰 | 短期可能「能跑」 |
| 代价 | M0 需确认接口方法与状态键如何映射 | 易碎、难测、难升级；禁止默认定为主路径 |
| 何时用 | **默认；M1 起按此实现** | 仅附录 A 记录「官方不可行」且评审通过后 |

#### 适配器职责（实现要点）

1. **对外：** 满足 `SequentialAgent.builder().subAgents(...)`（及 Parallel/Routing/Loop）对元素类型的编译期要求。  
2. **对内：** 解析 `generic:{id}` → `GraphBoundAgentNode.withGraphId`；**不**重新实现 StreamingLlmTemplate。  
3. **协作字段：** 渲染绑定层 `instruction` 的 `{stateKey}`；把 GenericAgent 输出映射到绑定 `outputKey`。  
4. **资源：** 继续走 GenericAgent 已有 Model/Prompt/MCP/Skill Resolver（按 key）。  
5. **会话：** 与父图共用 MCP session / threadId。  
6. **日志：** 子 Agent `name`、开始/结束、耗时、失败原因（含子名）。  

#### M0 对 Q2 的验证项

| 必达 | 说明 |
|------|------|
| 能用官方类型把 **stub 或真实 GenericAgent** 放进 `SequentialAgent.subAgents` | 编译 + 运行通过 |
| 第二子 Agent 能读第一子写入的 key | 与方式 A spike 同一条链 |
| MCP key 仍由 ACE 目录注入，无第二套连接 | 抽查日志/断点 |
| 附录 A 写明：实际用的官方类型全名（如 `com.alibaba...Agent`） | 随 BOM 固定 |

若官方接口不可行 → 附录 A 勾「需兜底」+ 原因；**不得静默改用反射上线。**

### 5.3 资源 key → 注册中心（宿主 SPI）

```text
GenericAgentSpec 上的 key
        │
        ▼
core 调用 ModelMountResolver / PromptContentResolver / McpToolResolver …
        │
        ▼
宿主实现：Nacos 或 K8s 或本地 —— 框架与编排同学不关心
```

高阶节点路径与单节点 GenericAgent **同一套 Resolver**，不另起 Nacos 专用 Spec 拉取。

### 5.4 AgentScope（M3，可选）与 Q4 定案

- `impl=AGENTSCOPE`，`ref=agentscope:{id}`  
- id 仍指向 ACE 注册定义；仅执行引擎切换  
- 记忆：M3 仅 `NONE`（Q5：不做子级 `READ_WRITE`；只读沿用父 thread 为可选弱能力）

#### Q4 定案：桥接坐标优先官方 `spring-ai-alibaba-starter-agentscope`

> **直白话：** 若子 Agent 选用 AgentScope，Maven **优先引官方 starter**，  
> 少自研「Msg ↔ OverAllState」协议；**不是**用 AgentScope 替换 ACE 图主编排。  
> 「坐标」= 依赖 `groupId:artifactId`，不是画布坐标。

##### 定案（2026-09-28）

| 项 | 结论 |
|----|------|
| **优先依赖** | **`spring-ai-alibaba-starter-agentscope`**（与现有 `graph-core` / agent-framework **同一 SAA BOM**） |
| **备选** | 仅当 starter 在所用 BOM 上缺失关键能力（无法作为 FlowAgent subAgent / 无法桥 MCP session）时，再评估直接 `agentscope-core` + 自研适配；**须附录 A 记录原因并评审** |
| **禁止** | 把 AgentScope Pipeline / MsgHub 做成第二套图边编排；另建一套 AgentScope 专用资源目录 |
| **模块** | 可选模块（如 `ace-graph-dsl-agentscope-agent`）；宿主显式引入；未引入时 `impl=AGENTSCOPE` 编译期明确报错 |
| **DSL** | 仅 `impl` / `ref` 前缀；**不**因换桥改 pattern 或图结构 |

##### 对照（防偏）

```text
高阶节点 subAgents
  ├─ impl=GENERIC_AGENT  → ace-graph-dsl-ai（M1，默认）
  └─ impl=AGENTSCOPE     → Q4：优先 starter-agentscope 包装
                              内：仍读 ACE 目录 Spec + 按 key 取 Model/MCP/Skill
```

| 路径 | 含义 | 地位 |
|------|------|------|
| `spring-ai-alibaba-starter-agentscope` | 官方把 AgentScope Agent 接到 SAA 图/Agent 生态 | **现行优先（M3）** |
| 直接 `agentscope-core` + 自写桥 | 自己映射 Msg、工具、流式 | **仅兜底**；禁止默认可发布 |
| AgentScope 当主编排 | Pipeline 替换图边 | **禁止**（中期非目标） |

##### M3 实现要点

1. 版本锁定在现有 **SAA BOM**，禁止散装冲突版本。  
2. Resolver：`impl=AGENTSCOPE` → starter 提供的包装类型（如 `AgentScopeAgent` 等，以 BOM 文档为准）→ 作为 FlowAgent subAgent（衔接 Q2 同类原则）。  
3. 资源：prompt/model/mcp/skill **仍按 ACE key** → 宿主 SPI；不在 AgentScope 侧另建连接池。  
4. 日志：打印所用桥接坐标（artifactId）、BOM 版本、子 Agent `name`、成败。  
5. 能力探测：设计器仅在模块启用时展示 AgentScope 选项。  

##### M3 对 Q4 的落地检查

| 项 | 动作 |
|----|------|
| pom 声明 | 优先 `starter-agentscope`；附录 A 写明实际坐标与版本 |
| 代码审查 | 无「默认可发布」的纯 `agentscope-core` 主路径（除非附录已批备选） |
| 回归 | 关闭 agentscope 模块时，`GENERIC_AGENT` 路径与旧图不受影响 |

### 5.5 Q5 定案：子 Agent `READ_WRITE` 记忆 — 中期先不做

> **直白话：** 高阶节点里的每个子 Agent，**先不要**各自以 `READ_WRITE` 往 remote 记忆里写 USER/ASSISTANT。  
> 避免和父图 thread、多节点即时落盘约定拧成「双记忆源 / 假 USER」。  
> 中期默认：**子 Agent 记忆 = `NONE`**（或仅只读沿用父图 thread，不写回）。

#### 定案（2026-09-28）

| 项 | 结论 |
|----|------|
| **中期范围** | **不做** 子 Agent 级 `READ_WRITE` 记忆合并方案 |
| **默认行为** | 子 Agent `memoryMode` / 等价配置 → **`NONE`** |
| **允许的弱能力（可选）** | 「只读沿用父图 thread」——可读、**不写** remote；若实现成本高，M1～M3 可一律 NONE |
| **明确禁止（本计划内）** | 子 Agent 独立 `READ_WRITE` 落盘；把下游材料块写成 USER；为「对齐 Vertical 图尾落盘」推迟父/子写入 |
| **父图 / 高阶节点本身** | 仍遵守项目记忆约定：**按节点即时 remote**；角色语义对齐 Vertical，**时机不跟 Vertical 图尾** |
| **何时再开** | 中期后单独立项：子与父 thread 合并、writeUser、display_content、thinking extras 等专项设计 |

#### 为何先不做（防偏）

```text
父图 thread / 高阶节点落盘     ← 已有约定，继续用
子 Agent A READ_WRITE ──┐
子 Agent B READ_WRITE ──┼─► 易出现：双源、假 USER、重复 ASSISTANT、难排查
子 Agent C READ_WRITE ──┘
```

| 易混说法 | 纠正 |
|----------|------|
| 「子 Agent 也要像业务节点一样 READ_WRITE」 | 中期不做；默认 NONE |
| 「先不做 = 父节点也不能写记忆」 | 错。父图/出口节点记忆策略不变 |
| 「用 Vertical 图尾一次 persist 代替」 | 禁止搬到 ace-graph（见记忆落盘约定） |
| 「AgentScope 路径可以单独开 READ_WRITE」 | 否。M3 同样先不做 |

#### 实现约束（M1～M3）

1. `GenericAgentSubAgentResolver` / AgentScope Resolver：构造子 Agent 时 **强制或默认 NONE**。  
2. 设计器：子 Agent 行 **不提供** READ_WRITE 选项（或灰显并提示「中期未开放」）。  
3. 校验：若 JSON 出现子级 `READ_WRITE` → **错误或警告降级为 NONE**（建议 M1 起直接错误，避免静默双源）。  
4. 日志：子 Agent 启动时打印 `memoryMode=NONE`（或实际只读策略）。  

#### 与项目记忆约定的关系

- 本文只定 **子 Agent 不写 READ_WRITE**。  
- 父节点 / 图上其它 GenericAgent 的记忆时机与 `writeUser`，仍以仓库 **ace-graph 记忆落盘约定** 为准，不在本 Q5 改写。

---

## 6. 设计器设计

| 项 | 行为 |
|----|------|
| 节点面板 | 分组「高阶多智能体（节点内）」≠「图编排并行/条件」 |
| 拖入 | `category=SAA_WORKFLOW` |
| pattern | 下拉四选一；按 pattern 切换表单 |
| 子 Agent 表 | name、impl、ref（已注册 GenericAgent）、instruction、outputKey |
| Loop | maxIterations、退出键、比较符、阈值 |
| Routing | 强调候选 ≥2；路由器 modelConfigKey |
| 徽章 | 角标显示 pattern |
| 帮助 | 「图边管阶段；本节点管阶段内部协作」 |
| 跳转 | 点击 ref → 通用 Agent 编辑页 |

中期不做：父节点内子画布、业务自注册第五种 pattern。

能力探测：后端暴露「是否启用 saa-agent / agentscope」；UI 据此显示 impl 选项与禁用提示。

---

## 7. 运行时、流式与观测

| 项 | M1 | M4 |
|----|----|----|
| 对外协议 | **不改 Execution / SSE 契约**（Q6） | **是否**为子步骤扩展 SSE → **M4 再定**；未定前不得先改协议 |
| 流式内容 | 父 `streamResponseKind` 统一；子增量可聚为父 BIZ | 若 M4 决定扩展协议，再设计子步骤事件；否则继续父聚合 + 试运行摘要 |
| 日志 | graphId/nodeId/pattern/子 name/耗时/成败（**M1 必达**） | 同左 + 可结构化 |
| 试运行 UI | 可选仅看父输出 | 展示子步骤树状摘要（**可不依赖 SSE 扩展**） |
| 记忆 | **子默认 NONE（Q5）**；不引入假 USER | 子级 READ_WRITE 中期后专项；父图仍按节点即时落盘 |

### 7.1 Q6 定案：SSE 子步骤事件 — M1 不改协议；是否扩展留 M4 再定

> **直白话：** 客户端现在靠 SSE 收父节点流式增量。  
> Q6 = 要不要改 SSE，让前端还能收到「子 Agent A 开始/结束」这类事件。  
> **定案：** M1 **只打服务端日志**，**不修改 SSE 协议**；扩不扩展放到 **M4 再定**（可先做试运行摘要 UI，不必先改协议）。

#### 定案（2026-09-28）

| 项 | 结论 |
|----|------|
| **M1** | 子步骤可见性 = **日志**（含 pattern、子 name、耗时、成败）；流式仍走父节点既有 BIZ/OUTPUT |
| **M1 禁止** | 新增/修改 SSE 事件类型或字段以承载子步骤；每个子 Agent 各推一条 OUTPUT |
| **M4** | **再决策**是否扩展 SSE；若扩展，须单独评审契约与兼容；**默认不预设必须扩展** |
| **M4 可并行** | 试运行面板子步骤树：可读执行日志/轨迹存储，**不强制**依赖 SSE 扩展 |
| **DSL / 设计器配置** | 不因此增加业务配置项 |

#### 对照（防偏）

```text
M1（已定）:
  SSE ──► 父节点增量（现有协议，不改）
  日志 ──► 子步骤开始/结束/耗时     ← 排障够用

M4（再定）:
  方案甲：仍不改 SSE + 试运行摘要 UI（推荐先评估）
  方案乙：扩展 SSE 子步骤事件（须契约评审）
```

| 易混说法 | 纠正 |
|----------|------|
| 「没有 SSE 子事件 = 看不到子步骤」 | 错。M1 有日志；M4 可有试运行树 |
| 「M1 先改一点 SSE 字段再说」 | **禁止**。M1 冻结协议 |
| 「M4 一定要改 SSE」 | 错。M4 **再定**；可只做 UI/日志结构化 |
| 「每个子 Agent 推一条 OUTPUT 更直观」 | 禁止。以父 `streamResponseKind` 聚合 |

#### M1 日志最低字段

`graphId`、`nodeId`、`pattern`、子 Agent `name`、开始/结束、耗时、成功/失败、错误信息（含字名）。

#### M4 决策检查清单（到点再填）

| 问题 | 结论（M4 填） |
|------|----------------|
| 线上对话 UI 是否必须实时展示子步骤？ | □ 是 → 评估 SSE 扩展 □ 否 → 可维持协议 + 试运行摘要 |
| 扩展方案与现有 `StreamingChunkFormatter` 如何兼容？ | （待填） |
| 是否需要版本协商 / 兼容旧客户端？ | （待填） |

---

## 8. 实施计划

### 8.1 阶段总览

```text
M0 Spike（方式 A 跑通；探测升 B 可选，不阻塞）
    │
    ▼
M1 内核 + SEQUENTIAL + GenericAgent 子引用 + 最小设计器（挂载固定方式 A）
    │
    ├──────────────► M3 AgentScope 子 Agent（可并行后半，不阻塞 M2）
    ▼
M2 PARALLEL / ROUTING / LOOP + 完整校验与样例
    │
    ▼
M4 轨迹 / 示例图 / 培训文案 / 可运营
    │
    └─（可选增强）确认 FlowAgent 可导出后升方式 B，不改 DSL
```

建议日历（可按人力调整，比例优先于绝对周数）：

| 阶段 | 建议周期 | 人力侧重 |
|------|----------|----------|
| M0 | 3～5 人日 | 平台后端 |
| M1 | 2～3 周 | 后端为主 + UI 最小表单 |
| M2 | 2～3 周 | 后端 pattern + UI 表单切换 |
| M3 | 1.5～2 周 | 后端适配（可与 M2 后半并行） |
| M4 | 1～1.5 周 | 前后端 + 文档 |

### 8.2 M0 · Spike（门禁）

| 项 | 内容 |
|----|------|
| 交付 | **方式 A** + **官方 Agent 包装 GenericAgent（或 stub）** 进 SequentialAgent.subAgents，写回 OverAllState |
| 决策（已定） | **挂载 = A（Q1）**；**子 Agent 包装优先官方 Agent 接口（Q2）** |
| M0 验证 | A 可跑；官方接口可把子 Agent 塞进 FlowAgent；可选探测升 B |
| 不做 | 设计器、正式 Factory、AgentScope、默认可走反射主路径、不以 B 为 M1 前置 |
| 退出 | 方式 A + 官方包装链路通过；附录 A 写明官方类型全名；**A 或官方包装失败则不排 M1 UI**；不能导出 B **不阻** M1 |

### 8.3 M1 · 顺序模式打通

| 项 | 内容 |
|----|------|
| 后端 | `SaaWorkflowSpec`、Validator（SEQUENTIAL）、FactoryImpl、GenericAgentSubAgentResolver、Builder 分支、AutoConfiguration |
| 设计器 | 可拖入；pattern 暂可固定/仅 SEQUENTIAL；子 Agent 引用 + input/output key |
| 样例 | 一条 Sequential 样例图（SQL 生成→评分或等价 stub） |
| 验证 | 试运行写约定 outputKey；日志见两子步骤；关闭模块旧图仍编译 |
| 不做 | Parallel/Routing/Loop、AgentScope |

**退出标准：** 样例通过；无 Framework 依赖的构建仍通过；关键路径日志齐全。

### 8.4 M2 · 四种模式补齐

| 项 | 内容 |
|----|------|
| 后端 | PARALLEL / **ROUTING（Framework 路由类，§4.6）** / LOOP 组装 + 全量校验规则 |
| 设计器 | 按 pattern 切换表单（并行无序、路由候选、循环条件） |
| 样例 | 每种 pattern 至少一条可发布样例 JSON |
| 测试 | 四种 pattern 自动化测试；保存 JSON 可被后端原样编译 |

**退出标准：** AC 中与四模式相关的项满足；文案区分图级并行/条件 vs 节点内模式。

### 8.5 M3 · AgentScope 子 Agent（可选开关）

| 项 | 内容 |
|----|------|
| 交付 | `impl=GENERIC_AGENT \| AGENTSCOPE`；图拓扑不变 |
| **桥接（Q4）** | **优先 `spring-ai-alibaba-starter-agentscope`**（同 BOM）；直接 `agentscope-core` 仅作评审后兜底 |
| 运行时 | 可选模块；未引入时 `impl=AGENTSCOPE` 报错清晰 |
| 资源 | 仍按 ACE key → 宿主 Resolver；不新建 AgentScope 资源中心 |
| 验证 | 同一 Sequential 样例切换 impl，主 outputKey 结构一致 |
| 退出 | 关闭依赖可构建；GenericAgent 路径回归；附录 A 写明实际 Maven 坐标 |

### 8.6 M4 · 可运营

| 项 | 内容 |
|----|------|
| 轨迹 UI | 试运行子步骤摘要（可读日志/轨迹存储；**不预设必须改 SSE**） |
| **Q6 决策点** | 评审是否扩展 SSE 子步骤事件；结论写入附录 A / 修订记录 |
| 资产 | 4 个内置示例图 + 设计器短说明 |
| 培训 | 一页「何时用图边、何时用高阶节点」（可链 FAQ） |
| 不做（除非 Q6 决定扩展） | 擅自改 Execution/SSE 契约 |
| 退出 | 非作者能搭 Routing/Loop 样例；Q6 有书面结论（扩展或不扩展） |

**退出标准：** 非作者按文档能搭 Routing 或 Loop 样例并试运行成功。

---

## 9. 任务分解（WBS）

### 9.1 后端

| ID | 任务 | 阶段 | 依赖 |
|----|------|------|------|
| BE-0.1 | BOM 对齐 agent-framework；spike 工程/测试 | M0 | — |
| BE-0.2 | 附录 A：记录方式 A 跑通结果 + 可选「能否升 B」探测；**不重新二选一** | M0 | BE-0.1 |
| BE-1.1 | core：`SaaWorkflowSpec` / Pattern / SubAgentRef / NodeRef 字段 | M1 | BE-0.2 |
| BE-1.2 | SPI：`SaaWorkflowNodeFactory` / `SubAgentResolver` / Binding | M1 | BE-1.1 |
| BE-1.3 | 新模块 saa-agent + AutoConfiguration | M1 | BE-1.2 |
| BE-1.4 | GenericAgentSubAgentResolver（**官方 Agent 包装**）+ StateBridge | M1 | BE-1.3 |
| BE-1.5 | Sequential **方式 A** 组装 + Builder 分支 + 未启用报错 | M1 | BE-1.4 |
| BE-1.6 | SaaWorkflowValidator（SEQUENTIAL 子集） | M1 | BE-1.1 |
| BE-1.7 | 集成测试 + 关键日志 | M1 | BE-1.5 |
| BE-2.1 | Parallel / **Routing（Framework 类，禁止自研选路）** / Loop 组装 | M2 | BE-1.7 |
| BE-2.2 | 校验规则补全 | M2 | BE-2.1 |
| BE-2.3 | 四模式样例 JSON + 测试 | M2 | BE-2.2 |
| BE-3.1 | AgentScope 模块：依赖 **starter-agentscope（Q4）** + Resolver | M3 | BE-1.4 |
| BE-4.1 | 子步骤日志/轨迹结构化 + 试运行数据；**SSE 扩展仅当 Q6=是** | M4 | BE-2.3 |
| BE-4.2 | **Q6 书面结论**（扩或不扩 SSE）写入附录 A | M4 | BE-4.1 评估 |

### 9.2 前端（设计器）

| ID | 任务 | 阶段 | 依赖 |
|----|------|------|------|
| FE-1.1 | 节点面板「高阶多智能体」+ category | M1 | BE-1.1 字段冻结 |
| FE-1.2 | 属性面板：SEQUENTIAL + 子 Agent 表 | M1 | FE-1.1 |
| FE-1.3 | 能力探测（模块是否启用）提示 | M1 | 后端探测 API 或配置 |
| FE-2.1 | pattern 切换与 Parallel/Routing/Loop 表单项 | M2 | FE-1.2 |
| FE-2.2 | 角标、帮助文案、ref 跳转 | M2 | FE-2.1 |
| FE-4.1 | 试运行子步骤树 | M4 | BE-4.1 |

### 9.3 文档与验收

| ID | 任务 | 阶段 |
|----|------|------|
| DOC-1 | 附录 A spike 结论 | M0 |
| DOC-2 | 设计器内嵌帮助链到 FAQ | M2 |
| QA-1 | 旧图回归 | 每阶段 |
| QA-2 | 中期 AC-1～AC-7 清单勾选 | M4 |

---

## 10. 风险与对策

| 风险 | 级别 | 对策 |
|------|------|------|
| Framework 与 graph-core 版本错位 | 高 | 只走现有 SAA BOM，禁止散装版本 |
| FlowAgent 无法干净挂进 StateGraph | 高 | **已定方式 A**；M0 验证 A 可跑，失败则调 BOM/适配，**不以 B 为交付前置** |
| 与 FanOut/条件边概念混淆 | 中 | UI 分组 + FAQ 文案；评审对照防偏表 |
| 子 Agent 记忆/流式双通道 | 中 | **Q5 已定**：子记忆 NONE；流式以父为准；READ_WRITE 中期后专项 |
| 范围膨胀到 Supervisor/Debate | 中 | pattern 枚举只开放四个；其余校验拒绝 |
| 误从 Nacos 拉整份节点 Spec | 中 | 坚持 ACE 目录 + key→SPI；同步导入另立项 |
| 自研路由 / 条件边冒充 ROUTING | 中 | **Q3 已定**：只用 Framework 路由类；评审对照 §4.6 |
| 直接 agentscope-core 自研桥 / AgentScope 当主编排 | 中 | **Q4 已定**：优先 starter-agentscope；主编排仍是 ACE 图 |

---

## 11. 待决问题（继承并跟踪）

| 编号 | 问题 | 状态 | 决定时机 | 结论 / 倾向 |
|------|------|------|----------|-------------|
| **Q1** | FlowAgent 是否暴露 asNode / CompiledGraph | **已定案（策略）** | 2026-09-28 | **现行用 A（NodeAction）**；确认可导出后再升 **B**。详见 **§4.4** |
| **Q2** | GenericAgent 如何变成 FlowAgent 的 subAgent | **已定案（策略）** | 2026-09-28 | **优先官方 Agent 接口** 薄适配，内委 GenericAgent；反射等仅兜底且须附录 A+评审。详见 **§5.2** |
| **Q3** | Routing 用哪个实现 | **已定案（策略）** | 2026-09-28 | **用 Framework 自带路由类，不造轮子**；FQCN 以 BOM 为准于 M2 写入附录 A。详见 **§4.6** |
| **Q4** | AgentScope 桥接用哪条 Maven 依赖 | **已定案（策略）** | 2026-09-28 | **优先 `spring-ai-alibaba-starter-agentscope`**（同 BOM）；直接 `agentscope-core` 仅兜底。详见 **§5.4** |
| **Q5** | 子 Agent `READ_WRITE` 记忆 | **已定案（不做）** | 2026-09-28 | **中期先不做**；子默认 `NONE`；合并方案中期后专项。详见 **§5.5** |
| **Q6** | SSE 是否扩展子步骤事件 | **已定案（分阶段）** | 2026-09-28 | **M1：只日志、不改 SSE**；**是否扩展留 M4 再定**（不预设必须扩展）。详见 **§7.1** |

**防偏摘要：**

- **Q1**：高阶节点怎么挂进 ACE 图（外面）。  
- **Q2**：节点里每个子 Agent 怎么由 GenericAgent 变成 Framework 要的类型（里面）。  
- **Q3**：`ROUTING` 用 SAA 自带路由类，不用自研选路、不用图条件边冒充。  
- **Q4**：AgentScope 子 Agent 优先官方 starter 桥，不是换成 AgentScope 主编排。  
- **Q5**：子 Agent 不写 READ_WRITE 记忆（先不做）；父图记忆约定不变。  
- **Q6**：M1 不改 SSE；子步骤先靠日志；扩不扩展协议 M4 再定。  
- 都 **不是**「要不要做高阶节点 / 另开主编排」。

---

## 12. 验收清单（发布门禁）

- [ ] **挂载为方式 A（NodeAction）**；未在未定案情况下改走 B  
- [ ] **子 Agent 经官方 Agent 接口包装 GenericAgent**；未将反射/内部 API 作默认可发布路径  
- [ ] **ROUTING 使用 Framework 自带路由类**；无自研选路主路径；未用图条件边冒充节点内 Routing  
- [ ] **AgentScope 路径（若启用）优先 `starter-agentscope`**；未默认可发布直接 `agentscope-core` 自研桥；未把 AgentScope 当主编排  
- [ ] **子 Agent 无 READ_WRITE 记忆主路径（Q5）**；默认 NONE；未引入假 USER / 双记忆源  
- [ ] **M1 未修改 SSE 协议（Q6）**；子步骤有日志；未让每个子 Agent 各推 OUTPUT  
- [ ] 未引入 `ace-graph-dsl-saa-agent`：旧图通过；含 `SAA_WORKFLOW` 的图报错清晰  
- [ ] 引入后：SEQUENTIAL 样例试运行 + 发布成功  
- [ ] M2：四 pattern 各至少一条样例 + 自动化测试  
- [ ] 设计器文案区分图级并行/条件 vs 节点内模式  
- [ ] 子 Agent 仅引用注册目录；改目录配置后重新编译生效  
- [ ] 资源仍按 key 走宿主 Resolver（不出现框架写死 Nacos API）  
- [ ] 关键节点日志可排查：编译、子开始/结束、写回 outputKey、失败原因  
- [ ] 不修改 `ztc-service-agent` 边结构作为迁移样板  

---

## 13. 文档关系

| 文档 | 角色 |
|------|------|
| 本文 | **开发设计 + 排期 WBS + 验收**；**Q1→§4.4，Q2→§5.2，Q3→§4.6，Q4→§5.4，Q5→§5.5，Q6→§7.1** |
| 高阶模式集成目标说明 | 产品/分层语义（图 vs 节点内） |
| FAQ 与统一口径 | 评审问答；含 Q1～Q6 |
| 初步技术方案 | DSL/SPI 细节补充（与本文冲突时以 **更新时间 + 评审纪要** 为准，并回写） |
| 内核选型 | 为何选 SAA Framework / AgentScope 角色 |
| 中期规划 | 产品目标与 AC |

---

## 附录 A · M0 Spike 结论

### A.1 挂载与子 Agent 包装策略（已定，非 spike 再选）

| 项 | 结论 |
|----|------|
| 现行挂载（Q1） | **方式 A：NodeAction 适配器** |
| 升 B 条件 | FlowAgent 可 `asNode()` / 导出 `CompiledGraph`，且 checkpoint/轨迹收益经评审确认 |
| 子 Agent 包装（Q2） | **优先官方 Agent（或 BOM 文档规定的 subAgent 类型）**；内委 GenericAgent |
| 非官方变通 | **禁止默认为主路径**；仅官方不可行时附录记录 + 评审 |
| Routing 实现（Q3） | **Framework 自带路由类**；不造轮子；不拿图条件边冒充 |
| Routing 类 FQCN（M2 填写） | `com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent`（BOM 1.1.2.2） |
| AgentScope 桥接（Q4） | **优先 `spring-ai-alibaba-starter-agentscope`**（同 BOM） |
| AgentScope 实际坐标/版本（M3 填写） | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-agentscope:1.1.2.2`（同 BOM）；包装类 `com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent` |
| 是否改用 agentscope-core 兜底 | □ 否（M3 已选否，主路径 starter） □ 是（原因须评审） |
| 子 Agent 记忆（Q5） | **中期不做 READ_WRITE**；默认 `NONE` |
| SSE 子步骤（Q6） | **M1 不改协议，只日志**；是否扩展 **M4 再定** |
| Q6 M4 结论（届时填） | **☑ 不扩展 SSE**（方案甲：试运行 / debug state 的 `ace.graph.dsl.saa.subSteps` + 日志；线上对话 UI 中期不强制实时子步骤） |
| DSL 影响 | **无**（Q1～Q6 均不把「子 READ_WRITE / 必改 SSE」写进中期必达） |

### A.2 Spike 实测（M0 · 2026-09-28）

| 项 | 结论 |
|----|------|
| BOM / Framework 版本 | `spring-ai-alibaba-bom` **1.1.2.2**；`spring-ai-alibaba-agent-framework` **1.1.2.2** |
| 方式 A 是否跑通 | **是**（`SequentialFlowAgentNodeAction` + ACE `StateGraph`） |
| 官方 subAgent 类型全名（Q2） | `com.alibaba.cloud.ai.graph.agent.ReactAgent`（经 `SequentialAgent.subAgents`） |
| 官方包装是否跑通（含 stub ChatModel） | **是**；第二步 prompt 含第一步 `sql`，`score=0.9` 写回 |
| 是否需启动非官方兜底 | **否** |
| 当前 BOM 是否已具备升 B API | **部分具备**：`Agent.getAndCompileGraph()` / ReactAgent 内部 `AgentSubGraphNode` 可继续调研；**不阻塞 M1** |
| 已知限制（流式/checkpoint/MCP session） | Spike 未覆盖真实 MCP/流式 SSE；ChatOptions 有 ToolCalling 告警（stub 可忽略） |
| 是否允许启动 M1 | **是**（取决于 **A + 官方包装** 均已通过） |
| 验证用例 | `ace-graph-dsl-saa-agent` → `SequentialAgentNodeActionSpikeTest` |

### A.3 M1 / M2 落地回填（2026-09-28）

| 项 | 结论 |
|----|------|
| M1 SEQUENTIAL | **已交付**：`SaaWorkflowNodeFactoryImpl` + `FlowAgentNodeAction`；样例 `docs/testdata/saa-sequential-sql-quality`；测试 `SequentialSaaWorkflowFactoryIntegrationTest` |
| M2 PARALLEL | **已交付**：`ParallelAgent` + `mergeOutputKey`；样例 `saa-parallel-dual-view`；测试 `parallelMergesTwoSubAgentOutputs` |
| M2 ROUTING | **已交付**：`LlmRoutingAgent`；路由器 `modelConfigKey` → `ModelMountResolver` + `ChatModelFactory`，缺省回落 ChatModel Bean / 桩端点；样例 `saa-routing-sql-or-chat` |
| M2 LOOP | **已交付**：`LoopAgent`；有 exitCondition* → `StateKeyExitLoopStrategy`，否则 `CountLoopStrategy`；样例 `saa-loop-score-until` |
| 挂载形态 | 统一 **方式 A**（`FlowAgentNodeAction`）；`SequentialFlowAgentNodeAction` 保留供 M0 spike |
| UI | 设计器 pattern 四选一 + ROUTING/LOOP 表单项（`SaaWorkflowForm.vue`） |
| 校验 | `SaaWorkflowValidator.OPEN_PATTERNS` = 四 pattern；PARALLEL/ROUTING ≥2 子 Agent；LOOP maxIterations ≤10 |
| SSE（Q6） | **未改**；子步骤仅服务端日志 |

### A.4 M3 AgentScope 子 Agent（2026-09-28）

| 项 | 结论 |
|----|------|
| Maven 坐标 | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-agentscope:1.1.2.2` |
| 包装类 FQCN | `com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent` |
| ACE 模块 | `ace-graph-dsl-agentscope-agent`（可选；starter 不传递） |
| Resolver | `AgentScopeSubAgentResolver`：`impl=AGENTSCOPE`，`ref=agentscope:{id}` |
| 资源 | id → ACE 注册 GenericAgentSpec；模型经 `AgentScopeModelFactory`（内联或 modelConfigKey） |
| 记忆（Q5） | 强制 NONE（`InMemoryMemory`）；READ_WRITE 校验/运行时报错 |
| 样例 | `docs/testdata/saa-agentscope-sequential-sql` |
| 测试 | `AgentScopeSequentialFactoryIntegrationTest` |
| 是否改用 agentscope-core 兜底 | **否**（主路径为官方 starter） |

### A.5 M4 可运营 / Q6 结论（2026-09-28）

| 项 | 结论 |
|----|------|
| Q6 SSE 扩展 | **不扩展**（中期） |
| 子步骤可见性 | ① SLF4J 日志 ② OverAllState `ace.graph.dsl.saa.subSteps` / `subStepsMeta` ③ 试运行面板子步骤树 |
| SSE 契约 | **未改**；`stripReserved` 对 `ace.graph.dsl.saa.*` 放行以便 debug_node.data 可见 |
| 资产 | `docs/testdata/README-SAA.md` 索引四模式 + AgentScope 样例 |
| 培训 | `docs/ACE-Graph-DSL-何时用图边何时用高阶节点.md`；设计器 SAA 表单提示该路径 |
| 后续若扩展 SSE | 须单独契约评审；不得默认真线改 Formatter |

---

## 附录 B · 修订记录

| 日期 | 说明 |
|------|------|
| 2026-09-28 | 初版：合并选型/规划/方案/FAQ 口径，输出可排期开发设计与 M0–M4 计划 |
| 2026-09-28 | **Q1 定案**：现行方式 A；确认可导出再升 B；§4.4 展开 |
| 2026-09-28 | **Q2 定案**：优先官方 Agent 接口包装 GenericAgent；§5.2 展开；M0/附录 A/验收同步 |
| 2026-09-28 | **Q3 定案**：Routing 用 Framework 自带类、不造轮子；§4.6 展开；附录 A 预留 FQCN |
| 2026-09-28 | **Q4 定案**：AgentScope 优先 `spring-ai-alibaba-starter-agentscope`；§5.4 展开 |
| 2026-09-28 | **Q5 定案**：子 Agent READ_WRITE 记忆中期先不做；默认 NONE；§5.5 展开 |
| 2026-09-28 | **Q6 定案**：M1 只日志、不改 SSE；是否扩展子步骤事件留 M4 再定；§7.1 展开 |
| 2026-09-28 | **M0 Spike 通过**：新增模块 `ace-graph-dsl-saa-agent`；方式 A + ReactAgent subAgent 链式写回 OverAllState；附录 A.2 回填；**允许启动 M1** |
| 2026-09-28 | **M1/M2 落地**：四 pattern Factory + 校验开放 + UI 切换 + 四模式样例；附录 A.3 回填 Routing FQCN=`LlmRoutingAgent` |
| 2026-09-28 | **M3 落地**：模块 `ace-graph-dsl-agentscope-agent`；Q4 starter-agentscope + `AgentScopeAgent`；附录 A.4；能力探测 `agentscopeEnabled` |
| 2026-09-28 | **M4 落地**：**Q6=不扩展 SSE**；子步骤轨迹键 + DryRun 子步骤树；样例索引与一页培训文案；附录 A.5 |
