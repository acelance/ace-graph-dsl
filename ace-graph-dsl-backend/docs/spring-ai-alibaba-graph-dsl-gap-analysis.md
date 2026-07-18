# spring-ai-alibaba-graph 能力 × ace-graph-dsl 可视化覆盖度调研

> 调研日期：2026-07-19
> 库版本：`com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.2`
> 方法：反编译本地 jar（`javap`）+ 通读 `ace-graph-dsl` 后端/前端源码 + demo `spring-ai-alibaba-demo-graph`

---

## 一、先回答核心问题

### Q1：spring-ai-alibaba-graph 是否支持 graph 嵌套 graph / 子图 / subagent？

| 能力 | 是否支持 | 证据（库 API） |
|------|---------|----------------|
| **graph 嵌套 graph（子图）** | ✅ 支持 | `StateGraph.addNode(String, StateGraph)` 与 `addNode(String, CompiledGraph)`；对应 `SubGraphNode` 接口，实现类 `SubStateGraphNode` / `SubCompiledGraphNode` |
| **子图内 HITL（可中断/恢复）** | ✅ 支持 | `ResumableSubGraphAction` + `SubGraphInterruptionException` |
| **并行（fan-out / fan-in）** | ✅ 支持 | `addEdge(String, List<String>)`、`addEdge(List<String>, String)`；`addParallelConditionalEdges(...)`；`ParallelNode`（含 `MAX_CONCURRENCY_KEY` 并发控制 + `NodeAggregationStrategy` ALL_OF/ANY_OF 聚合）；`ConditionalParallelNode` |
| **Agent（智能体循环/工具调用）** | ✅ 支持（但非独立节点） | `Command` / `MultiCommand` 动作记录（gotoNode + update）；`AsyncCommandAction` / `AsyncMultiCommandAction` 节点动作；`AgentInstructionMessage`、`AgentStateFactory`、`ScheduledAgentManager` |
| **SubAgent（子智能体，一等公民节点）** | ❌ 不支持 | jar 中**不存在** `SubAgent` / `SubAgentNode` 类。所谓 "subagent" 是**组合出来的模式**：用 (a) 一个运行 agent 循环的 StateGraph 作为子图嵌套进来，或 (b) 把另一个 `CompiledGraph`/`StateGraph` 当作节点 `addNode` 进来。库本身没有专门的 "子智能体" 节点类型。 |

**结论：**
- **graph 嵌套 graph / 子图：库正式支持**，且子图内部还能再做 HITL 中断恢复。
- **subagent：库没有一等公民的 "子智能体节点"**。它提供的是"拼装 subagent 的能力积木"——子图嵌套 + Agent 循环（Command 动作）+ 调度（`ScheduledAgentManager`）。你在 DSL 里要表达 subagent，正确做法就是"用子图包一个 agent 循环"。
- **并行：库支持多种形态**（fan-out 边、并行条件边、显式 ParallelNode）。

> 注：当前 demo `spring-ai-alibaba-demo-graph` 实际只用了 **HITL（interruptBefore）+ 普通并行边**，并没有真正写子图/agent 循环。所以 demo 不是能力边界的证据，库本身远超 demo 用法。

---

## 二、ace-graph-dsl 当前支持范围（实测）

### 节点（4 类 + 2 特殊 + 1 视觉容器）
- **NORMAL**（矩形）、**ROUTER**（六边形，条件路由）、**MERGE**（并行合并）、**HITL**（人工介入）
- **START / END**（圆形，保留字 `__START__` / `__END__`）
- **GROUP**（虚线圆角容器，**仅视觉分组，无连接锚点，无运行时语义** —— 不是子图）

节点来源：`BUILTIN`（Java Bean）/ `SCRIPT`（持久化脚本节点）。DSL 文档 `NODE_FLEXIBILITY_EXPLORATION.md` 里规划的第三类 `COMPOSITE`（子图引用）标注为 **"远期"**。

### 边（2 类）
- **normal**（普通边）
- **conditional**（条件边：Java Dispatcher 或脚本路由表达式，引擎 aviator/spel/groovy）
- 并行在 DSL 里是**隐式**的：从同一源拉多条 normal 边 = fan-out；汇到同一目标是 fan-in。没有"并行块"概念。

### 模型与构建
- `GraphEdge` 仅 `normal` / `conditional` 两种类型，无并行/聚合字段。
- `DynamicGraphBuilder.doBuildStateGraph()` **只**调用 `stateGraph.addNode(id, node_async(NodeAction))`，即只生成 `AsyncNodeAction`。不会生成子图节点、Command 节点、ParallelNode。
- `GraphDefinition.fromStateGraph()`（反向提取）只遍历**顶层** nodes/edges，且注释明确：`keyStrategies 暂不传递（反射无法获取 KeyStrategyFactory 内容）`。子图节点会被当作一个无类别的普通节点，且其**内部拓扑完全丢失**。

---

## 三、能力映射与缺口清单（重点）

| # | 库能力 | DSL 现状 | 缺口描述 | 优先级 |
|---|--------|----------|----------|--------|
| **G1** | 子图嵌套（graph-in-graph，`SubGraphNode`） | 仅 GROUP 视觉容器 | 无法表达"节点 = 一个嵌套图"、无法下钻/折叠子图内部、反向提取不进子图、预览无法渲染嵌套 | **🔴 高** |
| **G2** | 显式并行 `ParallelNode`（并发上限 + ALL_OF/ANY_OF 聚合）、`addParallelConditionalEdges` | 仅用多条 normal 边隐式表达 fan-out | 丢失并发控制与聚合策略；并行+条件组合边无法与纯条件边区分 | **🟠 中高** |
| **G3** | Agent 循环节点（`AsyncCommandAction`/`AsyncMultiCommandAction`、Command/MultiCommand） | 构建器只支持 `AsyncNodeAction`；无 Command 节点类型 | 无法可视化/编排 agent 工具循环、无 AgentInstructionMessage、无 `ScheduledAgentManager` 调度 | **🔴 高（若 agent 场景在范围）** |
| **G4** | 子图内 HITL（`ResumableSubGraphAction`） | HITL 仅顶层 `interruptBefore` | 不支持嵌套图里的中断/恢复 | **🟡 中（依赖 G1）** |
| **G5** | KeyStrategy（REPLACE/APPEND） | 模型里存了、构建时也用，但**反向提取丢失** | `fromStateGraph` 拿不到 KeyStrategyFactory 内容；无逐 key 策略的可视标注 | **🟡 中** |
| **G6** | 边语义丰富度（并行条件边 / 异常边 / 边级 Command） | 仅 normal/conditional | 无 ERROR 保留节点（库有 `StateGraph.ERROR`）的异常/错误处理边；无并行条件边类型 | **🟡 中** |
| **G7** | 流式/异步区分（`AsyncGenerator`、`ParallelGraphFlux`、`StreamMode`） | 不区分同步/流式节点 | 可视化无法表达 streaming 节点 | **🟢 低** |

### 关于 "subagent" 的落地建议（最关键）
库没有 SubAgent 节点，所以 DSL 若要做 "subagent 可视化"，正确路径是 **G1（子图）+ G3（agent 节点）的组合**：
1. 先把 **子图** 作为一等公民（COMPOSITE 节点 + 下钻编辑 + 边界渲染）；
2. 在子图内部允许放置 **Command/Agent 循环节点**（gotoNode 回环）；
3. 这样一个 "子图 + agent 循环" 就是一个 subagent，外层图 `addNode(subagentId, subStateGraph)` 直接挂上去即可。

---

## 四、优先级建议（按 ROI）

1. **G1 子图可视化 + COMPOSITE 节点**：你最关心的功能，库已支持、DSL 完全空白，且是 subagent/复用流程的基石。
2. **G3 Agent 循环节点**：若业务要上 subagent，这是必做；否则可后置。
3. **G2 并行块（含并发/聚合策略）**：把隐式并行升级为显式、可配置，体验提升明显。
4. **G5 KeyStrategy 导入补全 + G6 异常边**：补全覆盖度，工作量小。
5. **G4 / G7**：边角能力，按需。

---

## 五、一句话总结
`spring-ai-alibaba-graph 1.1.2.2` **正式支持子图嵌套、子图内 HITL、多种并行形态和 Agent 循环能力**；但 **ace-graph-dsl 目前只覆盖 NORMAL/ROUTER/MERGE/HITL 四类节点 + 普通/条件两类边**，子图（graph-in-graph）、显式并行块、Agent/Command 节点、KeyStrategy 导入、异常边**均未纳入可视化与构建实现**——其中"子图"和"Agent 节点"是和 subagent 诉求直接相关的两块最大缺口。
