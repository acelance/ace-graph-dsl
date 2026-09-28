# ACE Graph DSL 多智能体内核选型与集成建议

> 范围：ACE Graph DSL 是否已具备多智能体编排、如何接入高阶模式库、SAA Agent Framework 与 AgentScope Java 如何取舍  
> 依据：仓库代码（`ace-graph-dsl` / `lesso-ai-project`）+ Spring AI Alibaba / AgentScope Java 公开能力  
> 更新日期：2026-09-18

---

## 1. 结论摘要

1. **ACE 目前没有使用** SAA Agent Framework 的 `SequentialAgent` / `ParallelAgent` / `RoutingAgent` / `LoopAgent`。
2. ACE 集成的是 **`spring-ai-alibaba-graph-core`**（`StateGraph` → `CompiledGraph`），用 **JSON Graph DSL + GenericAgent 节点 + 设计器** 做编排。
3. **设计器并不缺失。** 缺口是 Framework 式「多智能体组合子」这一层，不是画布本身。
4. 若补「高阶节点内核 / 多智能体模式库」：**优先集成 SAA Agent Framework**；**AgentScope Java 作为可选的更强 Agent 运行时**，不要用它替换 Graph DSL。

**一句话：**

> 图管拓扑与资产（ACE）；模式库管节点内多智能体（SAA Framework）；AgentScope 只做更强的子 Agent 实现（可选）。

---

## 2. ACE 现状：有什么、缺什么

### 2.1 依赖事实

| 项 | 现状 |
|----|------|
| Graph 运行时 | `spring-ai-alibaba-graph-core`（与现网 BOM 同族，约 1.1.2.x） |
| Agent Framework | **未引入**（无 `SequentialAgent` 等产品代码引用） |
| AgentScope Java | **未作为主编排内核** |
| LLM 路径 | Spring AI Chat + 自建 `GenericAgentNode` / `StreamingLlmTemplate` |
| 编译链 | `GraphDefinition` → `DynamicGraphBuilder` → `StateGraph` → `CompiledGraph` |

### 2.2 已有编排 vs Framework 模式

ACE 的「多 Agent」= **多个 `GENERIC_AGENT` 节点用边连起来**，属于 **图级原语**，不是 Framework 组合子。

| SAA Agent Framework | ACE 现状 | 是否等同 |
|---------------------|----------|----------|
| `SequentialAgent` | 多条 `normal` 边串起多个节点 | **否** |
| `ParallelAgent` | DSL `parallel` + 自研 `FanOutNodeAction` | **否** |
| `RoutingAgent` / `LlmRoutingAgent` | 条件边 `conditional` + dispatcher / 脚本 | **否** |
| `LoopAgent` / `ReactAgent` | 节点内工具循环、脚本自环等 | **否** |
| 设计器 | `ace-graph-dsl-ui`：拖拽、条件边、并行、子图、校验、试运行、发布 | **已有** |

业务样例 `ztc-service-agent.json` 是「意图 → 业务 → 输出」三个 GenericAgent + 普通边。语义像 Sequential，但是 **DSL 边编排**，不是 `SequentialAgent`。

每个节点是 **`GenericAgentSpec`（单节点规格）**：prompt / model / MCP / Skill / memory 按 key 挂载，再编译进 `StateGraph`。

### 2.3 关键扩展点（后续集成可复用）

| 扩展点 | 用途 |
|--------|------|
| `GraphNodeDescriptor` category | 新增 `SAA_AGENT` / `SAA_WORKFLOW` |
| `DynamicGraphBuilder` | 按 category 走新适配器分支 |
| `GenericAgentNodeFactory` 模式 | 仿此做 `SaaAgentNodeFactory`（core 接口 + 可选实现模块） |
| `ObjectProvider<T>` | 未引入 Framework 模块时行为与今天一致 |
| 设计器 `NodePanel` | 增加模式分组与属性表单 |

### 2.4 分层关系

```text
SAA Agent Framework：高层多智能体模式（Sequential / Parallel / Routing / Loop…）
        ↑ 这一层 ACE 目前没有

SAA Graph Core：StateGraph / 边 / 状态 / CompiledGraph
        ↑ ACE 用的是这一层 + 自研 JSON DSL / FanOut / GenericAgent / 设计器
```

---

## 3. SAA Agent Framework 如何集成

### 3.1 角色定位

| 层 | 谁负责 | 角色 |
|----|--------|------|
| **ACE DSL + 设计器** | 平台产品 | 图怎么画、怎么存、怎么发、怎么挂 MCP / Skill |
| **Agent Framework** | 节点实现 | **单个 / 一组 Agent 怎么跑**（ReAct、顺序、并行、路由、循环） |
| **Graph Core** | 运行时底座 | 状态、边、checkpoint、子图、流式 |

**不要**把 Framework 当成第二套设计器。  
**不要**用一个 `SequentialAgent` 替换整张业务图（会丢掉 DSL 资产治理优势）。

```text
┌─────────────────────────────────────────────────────────┐
│  ACE Graph DSL（保留）                                   │
│  设计器 / JSON 图资产 / 版本发布 / 注册目录 / 校验试跑     │
│  DynamicGraphBuilder → StateGraph → CompiledGraph        │
└───────────────────────────┬─────────────────────────────┘
                            │ 编译时把节点落到…
┌───────────────────────────▼─────────────────────────────┐
│  节点实现层（可插拔）                                      │
│  ├─ GENERIC_AGENT      → 现有 StreamingLlmTemplate        │
│  ├─ SCRIPT / SUBGRAPH  → 现有能力                         │
│  └─ SAA_*（新增）      → Agent Framework 适配器           │
│       ReactAgent / Sequential / Parallel / Routing / Loop │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│  spring-ai-alibaba-graph-core（已有）                     │
│  + spring-ai-alibaba-agent-framework（新增，可选模块）    │
└─────────────────────────────────────────────────────────┘
```

官方关系：Graph 是 Framework 的底层运行时；`FlowAgent`（Sequential / Parallel / Loop / Routing / Supervisor）会把子 Agent **编译成 StateGraph**。这与 ACE 的编译链同构，适合做成高阶节点。

### 3.2 Framework 能补的能力

| Framework 能力 | 在 ACE 中的价值 | 与现有能力关系 |
|----------------|-----------------|----------------|
| **ReactAgent** | 标准工具循环（调工具 → 再推理 → 结束） | 可增强 / 部分替代 GenericAgent 内自建循环 |
| **SequentialAgent** | 「固定多步 Agent 链」封装为一个复合节点 | 边 = 跨节点业务编排；Sequential = 节点内模式 |
| **ParallelAgent** | 节点内多 Agent 并行再汇聚 | FanOut = 图级扇出；Parallel = Agent 组合子 |
| **Routing / LlmRoutingAgent** | 节点内意图分流到子 Agent | 条件边 = 图级路由；Routing = 节点内路由 |
| **LoopAgent** | 达标 / 未达标循环 | 补齐目前较弱的一等 Loop 模式 |
| **Supervisor**（版本支持时） | 督导-工人模式 | 可做成复合节点或子图模板 |

### 3.3 设计原则

1. **可选依赖**：新模块如 `ace-graph-dsl-saa-agent`；未引入时与今天行为一致。
2. **新 category，不破坏旧 JSON**：`GENERIC_AGENT` 继续可用。
3. **走现有扩展点**：Factory + `DynamicGraphBuilder` 分支 + 设计器节点面板。
4. **状态契约对齐**：输入 / 输出映射到 `OverAllState` 的 `inputKeys` / `outputKey` / KeyStrategy。
5. **子 Agent 优先引用注册目录**（已有 GenericAgent / 平台 Agent），避免再维护一套配置。

### 3.4 建议节点模型

可用两个 category，或一个 category + `pattern` 字段。推荐后者，设计器更好做。

```json
{
  "nodeId": "biz_router",
  "category": "SAA_WORKFLOW",
  "saaSpec": {
    "pattern": "ROUTING",
    "modelConfigKey": "nacos_agent_node",
    "inputKeys": "user_query",
    "outputKey": "route_result",
    "subAgents": [
      { "name": "hr", "ref": "generic:hr-agent" },
      { "name": "it", "ref": "generic:it-agent" }
    ],
    "mcpKeys": ["time-mcp"],
    "skillKeys": []
  }
}
```

`pattern` 建议取值：`REACT` / `SEQUENTIAL` / `PARALLEL` / `ROUTING` / `LOOP` / `SUPERVISOR`。

### 3.5 编译挂载方式

| 方式 | 适用 | 做法 |
|------|------|------|
| **A. NodeAction 适配器** | Framework 只暴露 invoke / stream | `SaaAgentNode.toAction()` 内调用，读写 `OverAllState` |
| **B. 子图挂载** | Framework 能导出 `CompiledGraph` / `StateGraph` | 与现有 `SUBGRAPH` 一样：`stateGraph.addNode(id, compiled)` |

优先做 **A**（落地快）。若确认可导出 Graph，再上 **B**（与 checkpoint、执行轨迹更一致）。`FlowAgent` 公开资料表明其内部就是编成 StateGraph，B 路径值得在 P0 做一次 API 验证。

### 3.6 模块划分

```text
ace-graph-dsl-core          // SaaAgentSpec、SaaAgentNodeFactory 接口（不强依赖 Framework）
ace-graph-dsl-saa-agent     // 依赖 agent-framework，适配器 + AutoConfiguration
ace-graph-dsl-ui            // 新节点类型与属性表单
```

### 3.7 和现有能力的分工

| 场景 | 用什么 |
|------|--------|
| 跨业务阶段编排（意图 → 业务 → 输出）、版本发布 | **ACE 图边**（保持主叙事） |
| 图级并行、条件跳转、子图复用 | **ACE FanOut / conditional / SUBGRAPH** |
| 单点「会调工具的智能体」 | `GENERIC_AGENT`，或升级为 **SAA ReactAgent** |
| 一组固定协作模式、希望少画边、可复用 | **SAA Sequential / Parallel / Routing / Loop 复合节点** |
| MCP / Skill / Prompt / Model 平台目录 | **仍由 ACE 注册式挂载**，注入 Framework 构造参数 |

### 3.8 分阶段落地

| 阶段 | 内容 | 产出 |
|------|------|------|
| **P0** | 依赖探测 + `ReactAgent` 适配为可选节点 | 证明集成可行；可与 GenericAgent A/B |
| **P1** | Sequential / Parallel 复合节点 + 设计器表单 | 补齐「多智能体编排」对外话术 |
| **P2** | Routing / Loop + 子 Agent 引用注册目录 | 复杂业务少画边 |
| **P3** | 轨迹 / 观测对齐、模式模板库 | 缩小与 SAA Admin 的体验差距 |

### 3.9 风险

- Framework 与 `graph-core` **必须同一 BOM**。
- 状态键、流式（BIZ / OUTPUT）、记忆、MCP session 要在适配器里显式桥接。
- 不要把整张 `ztc-service-agent` 改成一个 SequentialAgent。
- 对外口径：「ACE = 图资产平台；内嵌 SAA Framework 模式节点」，不要说成「已全面采用 Framework 编排」。

---

## 4. AgentScope Java vs SAA Framework

### 4.1 选型结论

| 角色 | 更合适的选择 |
|------|----------------|
| **图上的模式库**（Sequential / Parallel / Routing / Loop） | **SAA Agent Framework** |
| **节点内 Agent 能力**（ReAct、消息协作、A2A、沙箱等） | **AgentScope Java（可选增强）** |
| 与 ACE 当前栈的第一优先集成 | **SAA Framework** |

官方也在走组合而不是二选一：

- `spring-ai-alibaba-starter-agentscope` 把 AgentScope `ReActAgent` 包成图里可用的 `AgentScopeAgent`。
- AgentScope 文档中的 Pipeline 示例，编排层用的是 SAA 的 `SequentialAgent` / `ParallelAgent` / `LoopAgent`，子 Agent 才是 AgentScope。

### 4.2 对比表

| 维度 | **SAA Agent Framework** | **AgentScope Java** |
|------|-------------------------|---------------------|
| 与 ACE 已有底座 | **同族**。FlowAgent 编译成 StateGraph | **另一套运行时**（Msg / Reactor / Agent 中心），需适配器进图 |
| 状态模型 | `OverAllState` + KeyStrategy，与 ACE DSL 对齐 | `Msg` / Memory 为主，要映射到 OverAllState |
| 模式库形态 | Sequential / Parallel / Loop / Routing / Supervisor，**就是高阶节点语义** | Pipeline（Sequential / Fanout）+ MsgHub / Handoffs / Debate / Subagents，**模式更丰富** |
| 和设计器 / DSL | 一个复合节点 ≈ 一个 FlowAgent | 更像换一套 Agent 引擎，模式进 DSL 成本更高 |
| 依赖与版本 | 与已用 `graph-core` 同 BOM | 另引 `agentscope-core`；可用 SAA `starter-agentscope` 桥接 |
| 集成成本 | **低** | **中高**（消息、流式、记忆、MCP 双栈） |
| 对外叙事 | 与「多智能体模式」话术一致 | 能力更强，但要多解释一层 |
| Agent 运行时深度 | ReactAgent + Context / HITL，偏 Spring AI 生态 | ReAct、Hook、Skill、A2A、沙箱、分布式等往往更强 |

### 4.3 为什么模式库优先 SAA Framework

目标是：ACE 继续管图资产与设计器，节点内核提供 **可复用的多智能体模式**。

- Framework 与 `DynamicGraphBuilder` / checkpoint / 子图路径同构。
- Sequential / Parallel / Routing / Loop 直接对应高阶节点。
- 子 Agent 输出走 state key，好接现有 `inputKeys` / `outputKey`。

AgentScope 更强的是 **Agent 怎么思考与协作**，不是「怎么嵌进已有 Graph DSL 当一等模式节点」。把它当主模式库，等于在 ACE 里再养一套编排语义。

### 4.4 AgentScope 适合什么时候上

- 需要更强的 ReAct / Hook / Skill / 沙箱。
- 需要 MsgHub、Handoffs、Debate、A2A 等 Framework Flow 覆盖较弱的协作形态。
- 能接受 `AgentScopeAgent` / `starter-agentscope` 的桥接成本。

推荐叠法：

```text
ACE Graph DSL（图 + 设计器）
    └─ 高阶模式节点  ←── SAA Framework（Sequential / Parallel / Routing / Loop…）
            └─ 子 Agent 实现  ←── GENERIC_AGENT 或 AgentScope ReActAgent（可选）
```

---

## 5. 建议

### 5.1 产品与架构

1. **默认内核选 SAA Agent Framework**，做成可选模块和高阶节点类型。
2. **AgentScope 不作为主编排**，只作为可选子 Agent 运行时，经官方桥接挂入。
3. **不要双主**：图边仍是跨阶段编排的唯一主模型；节点内模式只走 Framework。
4. **保留 GenericAgent**：旧图不迁移也能跑；新节点逐步用 Framework 模式。
5. **注册目录不搬家**：MCP / Skill / Prompt / Model 继续由 ACE 注入，不另起一套 AgentScope 资源体系。

### 5.2 实施顺序

1. P0 验证 Framework 是否能导出 `CompiledGraph`（决定适配器还是子图挂载）。
2. 先接 `ReactAgent`，与现有 GenericAgent 对照试跑（工具循环、流式、MCP session）。
3. 再接 Sequential / Parallel，设计器只暴露 `pattern` + 子 Agent 引用。
4. Routing / Loop 放第二期。
5. 仅当业务明确需要 A2A / Debate / 沙箱时，再评估 `starter-agentscope`。

### 5.3 对外口径

- 现在：ACE = Graph DSL 资产平台；多 Agent = 图上多节点 + 图级并行 / 条件边；**不是** Framework 组合子。
- 规划：内嵌 SAA Framework 作为高阶节点内核，补齐 Sequential / Parallel / Routing / Loop。
- 不要表述成「ACE 已全面采用 AgentScope 或多智能体 Framework」。

### 5.4 不建议

| 做法 | 原因 |
|------|------|
| 用 AgentScope Pipeline 替换 ACE 图边 | 双编排模型，设计器、版本、状态键都会分裂 |
| 把 `ztc-service-agent` 整图收成一个 SequentialAgent | 丢掉可发布图资产与现网对齐优势 |
| 同时把 Framework 和 AgentScope 都做成「主编排」 | 集成与培训成本翻倍，收益重叠 |
| 为接 Framework 重写 GenericAgent | 破坏已有图；应并存、逐步迁移 |

---

## 6. 参考

| 资料 | 说明 |
|------|------|
| `ace-graph-dsl-core` 的 `DynamicGraphBuilder` / `GenericAgentSpec` | ACE 编译链与单节点规格 |
| `spring-ai-alibaba-graph-core` | 已集成的图运行时 |
| [Spring AI Alibaba Agent Framework](https://java2ai.com/docs/frameworks/agent-framework/advanced/workflow) | Sequential / Parallel / Routing / Loop |
| [spring-ai-alibaba-starter-agentscope](https://github.com/alibaba/spring-ai-alibaba) | AgentScope 接入 SAA 图的官方桥 |
| [AgentScope Java 多智能体概览](https://java.agentscope.io/v1/en/docs/multi-agent/overview.html) | Pipeline / Routing / Supervisor / Handoffs 等模式说明 |
| 同目录 `ACE-Graph-DSL-竞品对比.md` | 与 SAA / LangGraph4j / Archflow 等的产品竞品对比（本文不替代该文档） |
