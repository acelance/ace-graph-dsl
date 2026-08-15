# ace-graph-dsl 子图/SubAgent 可视化 · 可行性开发方案与优化建议

> 配套调研：`spring-ai-alibaba-graph-dsl-gap-analysis.md`（缺口清单 G1–G7）
> 库版本：`spring-ai-alibaba-graph-core:1.1.2.2`
> 结论先行：**方案可行**。库已原生支持子图嵌套（`StateGraph.addNode(id, StateGraph/CompiledGraph)`）、子图内 HITL、显式并行与 Agent 循环；当前 ace-graph-dsl 缺的是"把这些能力映射到 DSL 数据模型 + 构建器 + 前端下钻"的工程实现，而非底层能力缺失。
> 📌 实施状态（2026-07-19）：本方案已**部分落地**——P0 数据模型 + P1 子图下钻 + G6 异常边 + G3 Agent（code-island 折中）均已实现；P2 并行仅以隐式 fan-out 低风险路线实现（未做 `ParallelNode` 高保真）；P3 Agent 自环、P4 预览嵌套等以折中/部分失真方式落地。详见文末「十、实施状态与折中说明（2026-07-19 落实）」。

---

## 一、可行性结论与约束

| 维度 | 结论 | 依据 |
|------|------|------|
| 子图嵌套（graph-in-graph） | ✅ 可直接实现 | `StateGraph.addNode(String, StateGraph)` 公开 API；`SubGraphNode.subGraph()` 也是公开的，便于反向递归提取 |
| 子图内 HITL | ✅ 可实现 | `ResumableSubGraphAction` + `SubGraphInterruptionException` |
| Agent 循环（subagent 内核） | ✅ 可实现但风险较高 | 需手动构造 `AsyncCommandAction`，用 `Command(gotoNode)` 自环实现 agent loop |
| 显式并行块 | ✅ 低风险实现 | 当前已用 fan-out 边隐式表达；可升级为可视化"并行块" + 聚合策略 |
| KeyStrategy 导入 | ✅ 已修复 | `StateGraph.getKeyStrategyFactory()` 为 public、`apply()` 返回全量映射；`fromStateGraph` 现完整提取，`toStrategy` 补 MERGE + 未知策略降级（不再抛异常） |

**核心约束**：DSL 持久化模型是递归友好的（`GraphDefinition` 本身是 record，JSON 天然可嵌套），所以最自然的做法是**让子图成为一等公民的节点引用**，而非当前纯视觉的 GROUP 容器。

---

## 二、数据模型扩展（奠基，Phase 0）

当前 `NodeRef` 只承载 `nodeId/config/x/y`，category 来自注册中心。子图/agent 节点无注册描述符，必须在 `NodeRef` 自描述。

```java
// 建议新增字段（保持向后兼容：老字段留空即叶子节点）
public record NodeRef(
    String nodeId,
    Map<String, Object> config,
    Double x, Double y,
    // —— 新增 ——
    String category,        // NORMAL/ROUTER/MERGE/HITL/START/END/SUBGRAPH/AGENT
    String subgraphRef,     // "graphId@version"，SUBGRAPH 引用目标
    GraphDefinition subgraph, // 内嵌快照，供离线下钻/渲染（可选）
    String agentConfig      // AGENT 节点的循环配置 JSON（见 Phase 3）
) {}
```

`GraphEdge` 增加可选并行元数据（不改变现有 normal/conditional 语义，向后兼容）：

```java
public record GraphEdge(
    String from, String to, String type,
    String dispatcher, Map<String,String> mapping,
    String condition, String conditionEngine,
    // —— 新增（可选）——
    Boolean parallel,             // 是否为并行分支边
    String aggregation            // MERGE 目标节点的聚合策略 ALL_OF/ANY_OF
) {}
```

> `GraphDefinition` 无需改结构——子图就是 `NodeRef.subgraph` 里嵌一个 `GraphDefinition`，天然递归。

---

## 三、后端构建器改造（`DynamicGraphBuilder`）

现状：`doBuildStateGraph` 只调用 `stateGraph.addNode(id, node_async(node.toAction(ctx)))`，无法生成子图/agent/并行节点。

### 3.1 子图节点（Phase 1，关键）
```java
for (NodeRef ref : def.nodes()) {
    if ("SUBGRAPH".equals(ref.category())) {
        GraphDefinition sub = resolveSubgraph(ref);          // 优先 ref 解析，否则用内嵌 snapshot
        StateGraph subSg = doBuildStateGraph(sub);            // 递归构建
        stateGraph.addNode(ref.nodeId(), subSg);             // ← 库原生子图挂载
        continue;
    }
    // ... 现有 NORMAL/ROUTER/MERGE/HITL 逻辑
}
```

### 3.2 Agent 节点（Phase 3）
为 `AGENT` 节点生成 `AsyncCommandAction`：
```java
// Command(gotoNode) 自环直到 agent 判定完成
AsyncCommandAction loop = (state, cfg) -> {
    AgentStep step = runAgentStep(state, agentConfig);
    return step.done
        ? CompletableFuture.completedFuture(new Command(nextNode))
        : CompletableFuture.completedFuture(new Command(selfNodeId)); // 自环
};
stateGraph.addNode(ref.nodeId(), AsyncNodeAction.of(loop), Map.of());
```
> 库已提供 `Command`/`MultiCommand` 原语与 `AgentStateFactory`（`AgentInstructionMessage` 经 jar 核验**不存在**，内核实为 `CommandAction` 自环模式），无需自造机制，只需正确接线。

### 3.3 并行（Phase 2，两种路线）
- **低风险的"并行块"**：维持现有 fan-out 边，但给边打 `parallel=true` 并在 MERGE 目标存 `aggregation`，前端渲染为并行视觉块。构建时等价于现在的隐式并行，零语义风险。
- **高保真 `ParallelNode`**：构造 `ParallelNode(id, id, List<AsyncNodeActionWithConfig>, targets, strategies, compileConfig)`。能力更强（真正并发 + 聚合），但需把成员动作收集齐，复杂度高，建议作为可选增强。

---

## 四、反向提取改造（`GraphDefinition.fromStateGraph`）

现状：只遍历顶层 nodes/edges，且显式丢弃 keyStrategies；子图被压成一个无类别普通节点。

改造要点：
1. **递归**：遇到 `SubGraphNode` 实现类时，调用其公开的 `subGraph()` 递归提取嵌套 `GraphDefinition`，写入 `NodeRef.subgraph` + `category=SUBGRAPH` + `subgraphRef`。
2. **并行识别**：同一 `from` 的多条 `EdgeValue`（targets 全部 value==null）识别为 `parallel=true` 扇出；`ParallelNode` 内部目标回写为并行边。
3. **KeyStrategy**（G5，已修复）：`StateGraph.getKeyStrategyFactory().apply()` 返回全量 `Map<String, KeyStrategy>`，`fromStateGraph` 已改为完整提取（内置 REPLACE/APPEND/MERGE 经 `instanceof` 映射为字符串；自定义策略留空走兜底）。`toStrategy` 补 MERGE 分支并对未知策略降级（日志告警 + 默认 REPLACE）而非抛异常。无需 `@GraphMeta` 注解或 UI 补录兜底。
4. **ERROR 保留节点**（G6）：补识别 `StateGraph.ERROR`，生成异常/兜底边。

---

## 五、前端可视化方案（Phase 1 重点）

现状：`Canvas.vue` 的 `renderFromDefinition` 单层渲染；GROUP 是无锚点纯视觉容器；`graphEditor` store 的 `nodes/edges` 是扁平顶层。

### 5.1 作用域栈 + 下钻
- `graphEditor` 增加 `scopeStack: [{graphId, version, nodes, edges}]` 与 `currentScope`。
- 双击 `SUBGRAPH` 节点 → 压栈，加载 `ref.subgraph`（内嵌）或 `getLatestDefinition(subgraphRef.graphId)`，渲染该子图。
- 顶部面包屑支持逐级弹出返回；`autoLayout`/`MiniMap`/校验均在当前 scope 内工作。
- **懒加载**：仅在 drill-in 时拉取子图，避免一次性递归全部嵌套（性能优化，见第七节）。

### 5.2 新节点类型渲染（`DspNode.js`）
- `DspSubgraphNode`：带"下钻箭头"的容器样式（区别于 GROUP 的纯虚线框，增加可点击提示 + 子图名/版本）。
- `DspAgentNode`：`AGENT` 专用图标（区别于普通 NORMAL），可显示 loop/tool 标记。
- `DspParallelBlock`：把并行边包成可视化分组（淡色描边 + "PARALLEL ×N" 标签）。

### 5.3 节点面板 & 引用选择
- `NodePanel` 新增"子图引用"入口，打开 catalog 选择器（复用现有 `listDefinitions` / `getLatestDefinition`），选 `graphId@version` 即落一个 `SUBGRAPH` 节点。
- 高价值低成本动作：**"选中节点 → 右键 → 提取为子图"**，自动创建 `SUBGRAPH` 节点 + 内嵌 `GraphDefinition`，降低手动搭建成本。

---

## 六、分阶段路线图（估算）

| 阶段 | 内容 | 工作量 | 优先级 | 落实状态（2026-07-19） |
|------|------|--------|--------|----------------------|
| **P0 奠基** | `NodeRef`/`GraphEdge` 字段扩展 + 序列化兼容 + 校验豁免（子图节点不进扁平 nodes） | 1–2 d | 🔴 | ✅ 已完成 |
| **P1 子图可视化+下钻** | SUBGRAPH 节点渲染、scope 栈、面包屑、catalog 选择器、构建器 addNode(subSg)、递归反向提取、环检测、"提取为子图" | 3–5 d | 🔴 头号 | ✅ 已完成（"提取为子图" 快捷操作已于 #21 落地：选中节点 → 一键折叠为新 SUBGRAPH 节点，内部边保留、跨边界边重连；catalog 选择器复用 `listGraphIds`/`getLatestDefinition`） |
| **P2 并行显式化** | 边 parallel 标记 + 聚合策略配置 + 并行边样式 + 扇形分离 | 2–3 d | 🟠 | ✅ 已完成（低风险路线：边 parallel/aggregation 往返透传 + 翠绿虚线样式 + 属性面板开关/聚合选择 + 同对节点并行边扇形分离与并行块标签，#19 收尾；`ParallelNode` 高保真并发上限属高风险，可后端直接构造） |
| **P3 Agent/Command 节点** | AGENT 节点 + Command 自环构建 + 调度 + 反向提取 | 4–6 d | 🟠（若 agent 在范围） | ✅ 已完成（code-island 折中：前端仅记录节点与配置，自环由后端注册 `RegisteredAgentNode` 接线） |
| **P4 补齐与优化** | G5 keyStrategy 导入标注、G6 ERROR 异常边、预览嵌套渲染、懒加载性能、回归测试、G7 流式区分 | 2–3 d | 🟡 | ✅ 已完成（G5/G6/#18 回归测试/#20 嵌套预览 已完成；#23 懒加载已实现：引用型子图仅在 drill-in 时按需拉取 + 会话内定义缓存 + 加载遮罩；#25 G7 流式区分已实现：节点 `config.streaming` 开关 → 画布脉冲徽标 + 图例） |

**合计约 12–19 人日**。建议从 **P0 + P1** 起步——它直接回答你"子图/嵌套/subagent"的核心诉求，且子图 + agent 循环组合即可表达 subagent。

---

## 七、优化建议（工程提质）

1. **性能**：drill-in 懒加载子图拓扑；超大图考虑 LogicFlow 渲染分块/虚拟化；复用现有 `autoLayout`（O(V+E)，成本可接受）。
2. **预览协同**：库 `getGraph(MERMAID/PLANTUML)` 本就能渲染嵌套；一旦 P1 构建器支持子图，`previewMermaid/previewPlantUml` 的嵌套图将自动正确——无需另写渲染器。
3. **校验增强**：跨作用域的输入/输出 key 可达性（父↔子边界）；子图引用版本漂移检测（`subgraphRef` 的 version 与 catalog 实际版本比对，给出升级提示）；传递闭包环检测。
4. **DX**：面包屑 + 每 scope 缩略图；"提取为子图"/"内联子图"双向操作；子图引用 `@version` 钉选 + 一键升级。
5. **测试**：构建→提取→再构建的 round-trip 测试，保证 DSL ↔ StateGraph 嵌套保真；为重点子图/agent 组合加集成测试。

---

## 八、风险与缓解

| 风险 | 缓解 |
|------|------|
| R1 反向提取依赖内部类 | `SubGraphNode.subGraph()` 是公开方法，可递归；构建走公开 `addNode(id, StateGraph)`，风险低 |
| R2 Agent 自环语义错误 | P3 单独验证 Command/gotoNode 收口条件，配集成测试；可先交付 P1/P2 不阻塞 |
| R3 跨作用域环/校验复杂度 | P1 即引入引用图环检测 + 版本漂移检查 |
| R4 画布下钻 UX（状态保存/恢复/缩放） | 作用域栈 + 面包屑，复用现有 MiniMap；逐 scope 重置 |
| R5 KeyStrategy（已修复） | 经 `getKeyStrategyFactory()` 完整提取；`toStrategy` 补 MERGE + 未知策略降级，不再抛异常 |

---

## 九、一句话总结
库能力齐备，**落地只需把"子图/agent/并行"映射进 DSL 的 NodeRef/GraphEdge 模型，并在构建器与前端分别补上"挂载子图"和"下钻渲染"两端**。最高 ROI 路径是 **P0 数据模型 + P1 子图可视化下钻**，它本身就是 subagent 表达能力的基石；Agent 节点（P3）在其之上叠加 agent 循环即构成完整 subagent。

---

## 十、实施状态与折中说明（2026-07-19 落实）

> 落实原则（用户约定）：**优先级高的先做、低风险的先做；高风险项避开风险点，以折中/部分失真方式实现，并明确告知用户可经其他方式补全功能。**

### 10.1 已落地（对照缺口 G1/G3/G6 + 奠基 P0）

| 项 | 落地方式 | 备注 |
|----|----------|------|
| **P0 数据模型** | `NodeRef` 新增 `category`/`subgraphRef`/`subgraph`；`GraphEdge` 新增 `type=error`；`@JsonIgnore` 自描述方法 | 后端编译通过 |
| **G1 子图可视化 + 下钻** | SUBGRAPH 自定义节点（`DspSubgraphNode`）+ 作用域栈（`scopeStack`）+ 面包屑；双击下钻到子图、保存折叠回根图；构建器 `addNode(id, subStateGraph)` 递归；反向提取递归 `SubGraphNode.subGraph()` | 满足"在同一图内不展开子图、点击跳转到另一张图展开"的诉求 |
| **G3 Agent 节点（code-island）** | AGENT 自定义节点（`DspAgentNode`）；可拖入、改名、配 `subgraphRef`；属性面板标注"code-island"提示 | 前端不实现自环，仅记录与配置 |
| **G6 异常边** | ERROR 保留节点（`lf_error`↔`__ERROR__`）+ 红色虚线 `error` 边；`edgeParamValidation`/`topologyValidation` 均跳过 ERROR | 与后端校验豁免一致 |
| **G5 KeyStrategy** | `fromStateGraph` 完整提取 + `toStrategy` MERGE/降级 | 已在先前修订修复 |

### 10.2 折中与部分失真（高风险项，需告知用户）

1. **G3 Agent 自环 = code-island（部分失真）**
   - 前端只负责"放置 AGENT 节点 + 记录配置"，**不实现** `AsyncCommandAction` 自环编排。
   - 运行时由后端已注册的 `RegisteredAgentNode`（同 `category`）接线 `Command(gotoNode)` 自环，功能可经后端完整补全。
   - **用户告知**：Agent 循环逻辑需在后端注册节点实现，前端提供可视化入口与配置载体，不影响整体可用性。

2. **G2 并行 = 显式标记（低风险路线已全部落地）**
   - 后端 `GraphEdge` 已含 `parallel`(Boolean) + `aggregation`(ALL_OF/ANY_OF) 字段；前端已实现：边 parallel/aggregation 在 DSL↔画布间往返透传、`DspEdge` 翠绿虚线样式区分、属性面板「并行」开关与聚合策略下拉（作用于同源 fan-out 全组）。
   - **并行边扇形分离（#19 已落地）**：同 (source,target) 的多条边在渲染时按垂直方向散开（间距 28px），并行组首条边标注 `并行 · ALL_OF/ANY_OF`；手动新拉边仍由 `adjustEdge` 支持拖拽微调。
   - 运行时仍走隐式 fan-out（库原生并发），零语义风险。
   - **已告知/可补全**：高保真 `ParallelNode`（并发上限/聚合）属高风险，可经后端直接构造。

3. **内联子图保存折叠回根图（`collapsedToRoot`）**
   - 在子图 scope 内保存时，所有内联子图内容回写父节点 `subgraph` 字段后**折叠回根图**持久化，不保留逐 scope 独立保存态。
   - 引用子图（`subgraphRef` 已设）则始终作为独立 scope 经 `getLatestDefinition` 加载，与父图分离。
   - **⚠️ 已修复关键序列化 Bug（2026-07-19 回归测试发现）**：`NodeRef` 的 `isSubgraph()` / `isAgent()` 布尔方法与 record 组件访问器 `subgraph()` / `agent()` 在 Jackson 中冲突为同一 JSON 属性名，导致 `@JsonIgnore` 把真正的 `subgraph` / `agent` 字段一并丢弃——**内联子图与 Agent 配置在保存时会静默丢失**。已将两方法更名为 `hasSubgraph()` / `hasAgent()` 消除冲突，并新增 `GraphDefinitionRoundTripTest` 固化此保真（G1/G3 持久化现已正确）。

4. **预览嵌套 / 懒加载 / 回归测试（P4）**
   - **✅ 嵌套预览已落地（#20，2026-07-19）**：选中 SUBGRAPH 节点后「预览子图」按钮，弹窗调用后端 `previewMermaid`（编译视图，权威）→ 前端 `MermaidPreview` 组件客户端渲染（Mermaid CDN 动态加载 + 离线回退源码）；后端不可用时回退本地 `graphDefinitionToMermaid` 结构视图。零后端改动（复用既有 `preview/mermaid` 端点）。
   - **✅ 懒加载已落地（#23，2026-07-19）**：引用型子图仅在 drill-in 时按需拉取（不再整体预取）；新增会话内定义缓存 `defCache`，反复下钻同一图不重复请求网络；异步拉取期间画布显示加载遮罩（`subgraphLoading`）。**未做**：超大图的 LogicFlow 渲染分块/虚拟化（属深度性能优化，非当前痛点）。
   - **✅ G7 流式/异步区分已落地（#25，2026-07-19）**：节点 `config.streaming` 布尔开关（属性面板，普通/脚本/结构节点均可开）→ 画布节点右上角脉冲徽标（≋ 波纹 + 青色描边 + `drop-shadow` 辉光 + `1.8s` 脉冲动画）+ 存在此类节点时左下角图例说明；路由节点（ROUTER）同步支持徽标。纯前端标记，不触及运行时语义，零风险。
   - **round-trip 回归测试已补（`GraphDefinitionRoundTripTest`，覆盖 G1/G2/G3/G5/G6 提取与序列化保真，并捕获上述序列化 Bug）**。

### 10.3 未实现项（仅余 G4；G7 / 懒加载 已完成）
- **G4 子图内 HITL**：子图下钻已通，但 `ResumableSubGraphAction` 中断/恢复未接入（高风险、必要性中，往后安排，见路线图 Wave C）。

> 结论：本次以"低风险优先 + 高风险折中"策略完成了核心诉求（子图作为可跳转节点 + 下钻展开、Agent 入口、异常边），高风险的高保真并行与 Agent 自环以可补全方式落地，用户知情权与功能完整性均得到保障。
