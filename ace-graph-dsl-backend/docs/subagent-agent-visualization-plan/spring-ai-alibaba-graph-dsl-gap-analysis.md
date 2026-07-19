# spring-ai-alibaba-graph 能力 × ace-graph-dsl 可视化覆盖度调研

> 调研日期：2026-07-19
> 库版本：`com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.2`
> 方法：反编译本地 jar（`javap`）+ 通读 `ace-graph-dsl` 后端/前端源码 + demo `spring-ai-alibaba-demo-graph`
> 📌 修订（2026-07-19）：G5 KeyStrategy **已修复**——`fromStateGraph` 现经 `StateGraph.getKeyStrategyFactory()` 完整提取，`DynamicGraphBuilder.toStrategy` 补 MERGE 分支 + 未知策略降级（不再抛异常）；原"反射无法获取 KeyStrategyFactory"为误判。另：`AgentInstructionMessage` 类在 jar 中**不存在**，Agent 内核实为 `CommandAction` 自环模式，已全文订正。
> 📌 落实（2026-07-19）：**G1 子图下钻 / G2 并行边（隐式 fan-out）/ G3 Agent 节点（code-island 折中）/ G6 异常边 / G7 流式区分（#25）/ 懒加载（#23）已落地实现**。原则：优先级高 + 低风险先做；高风险项避开风险点、以折中/部分失真实现，并明确告知用户可经其他方式补全功能。详见文末「六、实施状态总览」与各缺口行的「✅ 已实现」标注。
>
> 📌 **端到端 demo 模块（2026-07-19）**：在 `spring-ai-alibaba-demo/spring-ai-alibaba-demo-graph/` 下新建与 `*-m2-ace-annotated-registration` 同级的一对模块——后端 `spring-ai-alibaba-demo-cs-reply-m2-ace-graph-dsl`（Maven，seeder 注入 4 张覆盖 G1~G7/#20~#23 的演示图）与前端 `spring-ai-alibaba-demo-cs-reply-web-m2-ace-graph-dsl`（Vite + `@acelance/graph-dsl-ui`）。后端 `--server.port=18090`、前端 dev `4205`（代理 `/api`→18090），已验证 catalog/definitions 返回 4 图、前端 `vite build` 通过。

---

## 一、先回答核心问题

### Q1：spring-ai-alibaba-graph 是否支持 graph 嵌套 graph / 子图 / subagent？

| 能力 | 是否支持 | 证据（库 API） |
|------|---------|----------------|
| **graph 嵌套 graph（子图）** | ✅ 支持 | `StateGraph.addNode(String, StateGraph)` 与 `addNode(String, CompiledGraph)`；对应 `SubGraphNode` 接口，实现类 `SubStateGraphNode` / `SubCompiledGraphNode` |
| **子图内 HITL（可中断/恢复）** | ✅ 支持 | `ResumableSubGraphAction` + `SubGraphInterruptionException` |
| **并行（fan-out / fan-in）** | ✅ 支持 | `addEdge(String, List<String>)`、`addEdge(List<String>, String)`；`addParallelConditionalEdges(...)`；`ParallelNode`（含 `MAX_CONCURRENCY_KEY` 并发控制 + `NodeAggregationStrategy` ALL_OF/ANY_OF 聚合）；`ConditionalParallelNode` |
| **Agent（智能体循环/工具调用）** | ✅ 支持（但非独立节点） | `Command` / `MultiCommand` 动作记录（gotoNode + update）；`AsyncCommandAction` / `AsyncMultiCommandAction` 节点动作；`AgentStateFactory`、`ScheduledAgentManager`（对整张 `CompiledGraph` 做周期调度的调度体系，非可嵌套节点） |
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
- `GraphEdge` 支持 `normal` / `conditional` / `error` 三种类型（`error` 即异常边，指向 `__ERROR__`）；并行/聚合字段（低风险路线）暂未落地。
- `DynamicGraphBuilder.doBuildStateGraph()` 现已支持 `SUBGRAPH`（`addNode(id, subStateGraph)` 递归构建）与 `AGENT`（后端注册节点接线 Command 自环）；业务节点仍走 `node_async(NodeAction)`。`ParallelNode` 高保真路线未实现。
- `GraphDefinition.fromStateGraph()`（反向提取）现已支持递归提取子图（`SubGraphNode.subGraph()`），`keyStrategies` 经 `getKeyStrategyFactory()` 完整提取（G5 已修复）；`ERROR` 保留节点识别为异常边（G6）。子图内部拓扑不再丢失。

---

## 三、能力映射与缺口清单（重点）

| # | 库能力 | DSL 现状 | 缺口描述 | 优先级 |
|---|--------|----------|----------|--------|
| **G1** | 子图嵌套（graph-in-graph，`SubGraphNode`） | ✅ 已实现（含折中） | SUBGRAPH 节点 + 作用域栈下钻 + 面包屑 + 构建器 `addNode(subGraph)` + 反向提取递归 + **嵌套预览（#20：选中子图节点可一键「预览子图」，Mermaid 渲染，服务端编译视图 + 本地结构视图兜底）**。**折中**：内联子图保存时**折叠回根图**（`collapsedToRoot`），不保留逐 scope 独立保存态；引用子图走 `getLatestDefinition` 独立 scope | **✅ 已实现** |
| **G2** | 显式并行 `ParallelNode`（并发上限 + ALL_OF/ANY_OF 聚合）、`addParallelConditionalEdges` | ⚠️ 部分实现（低风险路线） | 仍用多条 normal 边隐式表达 fan-out（库原生支持，零语义风险），**未实现** `ParallelNode` 高保真并发/聚合与 `addParallelConditionalEdges`。**折中**：并行边在画布上可叠加但**未做视觉扇形分离**（多沿同一路径重叠）；可视化"并行块"分组 + `parallel`/`aggregation` 字段未落地 | **🟠 中高** |
| **G3** | Agent 循环节点（`AsyncCommandAction`/`AsyncMultiCommandAction`、Command/MultiCommand） | ✅ 已实现（code-island 折中） | AGENT 节点可拖入画布、可改名/配 `subgraphRef`。**折中（code-island）**：前端**不实现** agent 自环逻辑，仅记录节点与配置，运行时由后端已注册的 `RegisteredAgentNode`（同 category）接线 Command 自环；功能可由后端方式完整实现 | **✅ 已实现（折中）** |
| **G4** | 子图内 HITL（`ResumableSubGraphAction`） | HITL 仅顶层 `interruptBefore` | 不支持嵌套图里的中断/恢复 | **🟡 中（依赖 G1）** |
| **G5** | KeyStrategy（REPLACE/**APPEND**/MERGE） | 模型存了、构建时用，反向提取**已修复** | `StateGraph.getKeyStrategyFactory().apply()` 返回全量映射，`fromStateGraph` 现完整提取；`toStrategy` 补 MERGE + 未知策略降级（不再抛异常） | **✅ 已修复** |
| **G6** | 边语义丰富度（并行条件边 / 异常边 / 边级 Command） | ✅ 部分实现 | **异常边已实现**：新增 ERROR 保留节点（`lf_error`↔`__ERROR__`）+ 红色虚线 `error` 边，前端渲染 + 校验豁免（参数可达性、拓扑识别均跳过 ERROR）。**未实现**并行条件边与边级 Command | **🟡 中** |
| **G7** | 流式/异步区分（`AsyncGenerator`、`ParallelGraphFlux`、`StreamMode`） | 不区分同步/流式节点 | 节点通过 `config.streaming` 标记，画布以右上角脉冲徽标 + 图例区分；属性面板可开关 | **✅ 已实现（#25）** |

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
4. **G6 异常边**：补全覆盖度，工作量小。（G5 KeyStrategy 导入**已修复**：`fromStateGraph` 现可完整提取并重建）
5. **G4 / G7**：边角能力，按需。

---

## 五、一句话总结
`spring-ai-alibaba-graph 1.1.2.2` **正式支持子图嵌套、子图内 HITL、多种并行形态和 Agent 循环能力**；**ace-graph-dsl 现已覆盖 NORMAL/ROUTER/MERGE/HITL 四类节点 + 普通/条件/异常三类边**，并新增 **SUBGRAPH（可下钻跳转）/ AGENT（code-island）** 两类结构节点——其中 G1 子图下钻、G3 Agent 节点、G6 异常边已落地（G2 并行仍以隐式 fan-out 表达）。KeyStrategy 导入**已修复**（`fromStateGraph` 现可完整提取并重建）。**高风险项（G3 Agent 自环、G2 `ParallelNode`）以折中/部分失真方式实现，功能均可经后端代码或手动 DSL 编排完整补全**——其中"子图"和"Agent 节点"是和 subagent 诉求直接相关的两块最大缺口，本次已打通可视化入口与跳转能力。

---

## 六、实施状态总览（2026-07-19 落实）

> 原则：优先级高 + 低风险先做；高风险项避开风险点、以折中/部分失真实现，并明确告知用户可经其他方式补全功能。

| 缺口 | 落实状态 | 关键折中 / 失真 |
|------|----------|----------------|
| **G1 子图** | ✅ 已实现 | 子图 = 可点击跳转节点（双击下钻到另一张图展开）；**选中节点可一键「提取为子图」（#21：折叠为新 SUBGRAPH 节点，内部边保留、跨边界边重连到该节点）**；内联子图保存时**折叠回根图**（`collapsedToRoot`），不保留逐 scope 独立保存态；引用子图经 `getLatestDefinition` 独立 scope；**已修复 `NodeRef.subgraph`/`agent` 字段因 Jackson 属性名冲突被静默丢弃的序列化 Bug（2026-07-19 回归测试捕获），内联子图与 Agent 配置现可正确持久化** |
| **G2 并行** | ✅ 已实现（低风险路线） | 边 `parallel`/`aggregation` 标记 + 翠绿虚线样式 + 属性面板开关/聚合选择 + **同对节点并行边扇形分离（#19）** + 并行块标签（前端 DSL↔画布往返透传，运行时仍走隐式 fan-out，零语义风险）；`ParallelNode` 高保真并发/聚合属高风险，可经后端直接构造 |
| **G3 Agent** | ✅ 已实现（code-island） | 前端只记录 AGENT 节点与配置，**不实现**自环逻辑；运行时由后端已注册 `RegisteredAgentNode` 接线 Command 自环；功能可经后端完整补全 |
| **G5 KeyStrategy** | ✅ 已修复 | `getKeyStrategyFactory()` 完整提取 + `toStrategy` 补 MERGE + 未知策略降级 |
| **G6 异常边** | ✅ 部分实现 | ERROR 保留节点 + 红色虚线 `error` 边已实现并豁免校验；并行条件边、边级 Command 未实现 |
| **G4 子图内 HITL** | ❌ 未实现（依赖 G1，已具备基础） | 子图下钻已通，但 `ResumableSubGraphAction` 中断/恢复未接入 |
| **G7 流式/异步** | ✅ 已实现（#25） | 节点 `config.streaming` 开关驱动画布脉冲徽标 + 图例；低风险（纯前端标记，不触及运行时语义） |

**用户告知要点（高风险折中）**：G3 Agent 与 G2 并行高保真属于高风险/高复杂度项，本次以折中方式落地（code-island + 隐式并行），**功能均可经后端代码或手动 DSL 编排完整实现**；前端可视化已提供入口与跳转能力，不影响整体可用性。
