# ace-graph-dsl 子图/SubAgent 可视化 · 可行性开发方案与优化建议

> 配套调研：`spring-ai-alibaba-graph-dsl-gap-analysis.md`（缺口清单 G1–G7）
> 库版本：`spring-ai-alibaba-graph-core:1.1.2.2`
> 结论先行：**方案可行**。库已原生支持子图嵌套（`StateGraph.addNode(id, StateGraph/CompiledGraph)`）、子图内 HITL、显式并行与 Agent 循环；当前 ace-graph-dsl 缺的是"把这些能力映射到 DSL 数据模型 + 构建器 + 前端下钻"的工程实现，而非底层能力缺失。

---

## 一、可行性结论与约束

| 维度 | 结论 | 依据 |
|------|------|------|
| 子图嵌套（graph-in-graph） | ✅ 可直接实现 | `StateGraph.addNode(String, StateGraph)` 公开 API；`SubGraphNode.subGraph()` 也是公开的，便于反向递归提取 |
| 子图内 HITL | ✅ 可实现 | `ResumableSubGraphAction` + `SubGraphInterruptionException` |
| Agent 循环（subagent 内核） | ✅ 可实现但风险较高 | 需手动构造 `AsyncCommandAction`，用 `Command(gotoNode)` 自环实现 agent loop |
| 显式并行块 | ✅ 低风险实现 | 当前已用 fan-out 边隐式表达；可升级为可视化"并行块" + 聚合策略 |
| KeyStrategy 导入 | ⚠️ 部分限制 | 反射拿不到 `KeyStrategyFactory`；导入态接受默认 REPLACE，提供 UI 补录 |

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
> 库已提供 `Command`/`MultiCommand` 原语与 `AgentInstructionMessage`/`AgentStateFactory`，无需自造机制，只需正确接线。

### 3.3 并行（Phase 2，两种路线）
- **低风险的"并行块"**：维持现有 fan-out 边，但给边打 `parallel=true` 并在 MERGE 目标存 `aggregation`，前端渲染为并行视觉块。构建时等价于现在的隐式并行，零语义风险。
- **高保真 `ParallelNode`**：构造 `ParallelNode(id, id, List<AsyncNodeActionWithConfig>, targets, strategies, compileConfig)`。能力更强（真正并发 + 聚合），但需把成员动作收集齐，复杂度高，建议作为可选增强。

---

## 四、反向提取改造（`GraphDefinition.fromStateGraph`）

现状：只遍历顶层 nodes/edges，且显式丢弃 keyStrategies；子图被压成一个无类别普通节点。

改造要点：
1. **递归**：遇到 `SubGraphNode` 实现类时，调用其公开的 `subGraph()` 递归提取嵌套 `GraphDefinition`，写入 `NodeRef.subgraph` + `category=SUBGRAPH` + `subgraphRef`。
2. **并行识别**：同一 `from` 的多条 `EdgeValue`（targets 全部 value==null）识别为 `parallel=true` 扇出；`ParallelNode` 内部目标回写为并行边。
3. **KeyStrategy**（G5）：反射拿不到 `KeyStrategyFactory`，采用"导入态默认 REPLACE + UI 补录 + 可选 `@GraphMeta(keyStrategies=...)` 注解"三选一缓解。
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

| 阶段 | 内容 | 工作量 | 优先级 |
|------|------|--------|--------|
| **P0 奠基** | `NodeRef`/`GraphEdge` 字段扩展 + 序列化兼容 + 校验豁免（子图节点不进扁平 nodes） | 1–2 d | 🔴 |
| **P1 子图可视化+下钻** | SUBGRAPH 节点渲染、scope 栈、面包屑、catalog 选择器、构建器 addNode(subSg)、递归反向提取、环检测、"提取为子图" | 3–5 d | 🔴 头号 |
| **P2 并行显式化** | 并行块视觉分组 + 边 parallel 标记 + 聚合策略配置（低风险路线） | 2–3 d | 🟠 |
| **P3 Agent/Command 节点** | AGENT 节点 + Command 自环构建 + AgentInstructionMessage + 调度 + 反向提取 | 4–6 d | 🟠（若 agent 在范围） |
| **P4 补齐与优化** | G5 keyStrategy 导入标注、G6 ERROR 异常边、预览嵌套渲染、懒加载性能、回归测试 | 2–3 d | 🟡 |

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
| R5 KeyStrategy 反射不可得 | 导入态默认 REPLACE + UI 补录 + 可选 `@GraphMeta` 注解 |

---

## 九、一句话总结
库能力齐备，**落地只需把"子图/agent/并行"映射进 DSL 的 NodeRef/GraphEdge 模型，并在构建器与前端分别补上"挂载子图"和"下钻渲染"两端**。最高 ROI 路径是 **P0 数据模型 + P1 子图可视化下钻**，它本身就是 subagent 表达能力的基石；Agent 节点（P3）在其之上叠加 agent 循环即构成完整 subagent。
