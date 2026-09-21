# ACE Graph DSL 多智能体初步技术方案

> 文档类型：初步技术方案（非实施详设）  
> 状态：草案  
> 日期：2026-09-18  
> 对应规划：[ACE-Graph-DSL-多智能体中期规划.md](./ACE-Graph-DSL-多智能体中期规划.md)  
> 选型依据：[ACE-Graph-DSL-多智能体内核选型.md](./ACE-Graph-DSL-多智能体内核选型.md)

---

## 1. 方案目标

在不改变 ACE 主编排模型的前提下，增加一种图节点：

- **节点类型**表达 SAA Agent Framework 的高阶模式（Sequential / Parallel / Routing / Loop）。
- **子 Agent** 由统一接口解析，默认实现是现有 `GENERIC_AGENT`，可选实现是 AgentScope `ReActAgent`。
- `ace-graph-dsl-core` **不**直接依赖 agent-framework 与 agentscope。

---

## 2. 总体架构

```text
设计器 JSON（GraphDefinition / NodeRef）
        │
        ▼
DynamicGraphBuilder
        │  category = SAA_WORKFLOW
        ▼
SaaWorkflowNodeFactory          （core 仅接口，实现在可选模块）
        │
        ├─ pattern → SAA FlowAgent
        │     SequentialAgent / ParallelAgent / LoopAgent / LlmRoutingAgent
        │
        └─ subAgents[] → SubAgentResolver
              ├─ impl=GENERIC_AGENT  → 包装现有 GenericAgentNode
              └─ impl=AGENTSCOPE     → AgentScope ReActAgent 适配器
        │
        ▼
NodeAction 或 CompiledGraph
        │
        ▼
StateGraph.addNode(...)  →  CompiledGraph
```

与现有编译链的关系：

| 现有路径 | 类 | 本方案 |
|----------|----|--------|
| 普通 / 脚本节点 | `DynamicGraphBuilder.buildSingleNodeAction` | 不改 |
| GenericAgent | `resolveGenericAgent` → `GenericAgentNode.toAction` | 不改；仅被子 Agent 解析器复用 |
| 子图 | `addNode(id, CompiledGraph)` | 若 FlowAgent 可导出图，高阶节点走同一挂载方式 |
| 图级并行 | `FanOutNodeAction` | 保留；与节点内 `ParallelAgent` 不是同一层 |

---

## 3. 模块划分

```text
ace-graph-dsl-core
  definition/SaaWorkflowSpec.java          规格，纯数据
  agent/SaaWorkflowNodeFactory.java        接口
  agent/SubAgentResolver.java              接口
  agent/SubAgentBinding.java               解析结果：名字、outputKey、可执行句柄

ace-graph-dsl-saa-agent                    新模块，可选
  SaaWorkflowNodeFactoryImpl               组装 FlowAgent
  GenericAgentSubAgentResolver             引用 GENERIC_AGENT
  依赖：spring-ai-alibaba-agent-framework
         ace-graph-dsl-ai（复用 GenericAgentNodeFactory）

ace-graph-dsl-agentscope-agent             新模块，可选，M3
  AgentScopeSubAgentResolver
  依赖：spring-ai-alibaba-starter-agentscope 或 agentscope-core + 自适配
         ace-graph-dsl-saa-agent（只依赖子 Agent SPI，不反向依赖）

ace-graph-dsl-ui
  节点面板 + pattern 表单
```

装配方式对齐现有 `ObjectProvider<GenericAgentNodeFactory>`：

- 未引入 `ace-graph-dsl-saa-agent` 时，图中出现 `SAA_WORKFLOW` 则编译期报错：「未启用多智能体高阶节点模块」。
- 未引入 AgentScope 模块时，仅当某个子 Agent `impl=AGENTSCOPE` 才报错；`impl=GENERIC_AGENT` 不受影响。

`ace-graph-dsl-spring-boot-starter` 不强制传递这两个模块。宿主（如 platform-agent-server）按需依赖。

---

## 4. DSL 规格

### 4.1 节点

在 `GraphNodeDescriptor` 增加常量：

```text
CATEGORY_SAA_WORKFLOW = "SAA_WORKFLOW"
```

`NodeRef` 增加可选字段 `saaSpec`，与现有 `agentSpec` 互斥：`SAA_WORKFLOW` 只读 `saaSpec`。

### 4.2 SaaWorkflowSpec

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
        "impl": "AGENTSCOPE",
        "ref": "agentscope:sql-rater",
        "instruction": "SQL:\n{sql}\n请求:\n{user_query}",
        "outputKey": "score"
      }
    ]
  }
}
```

字段约定：

| 字段 | 适用 pattern | 说明 |
|------|----------------|------|
| `pattern` | 全部 | `SEQUENTIAL` / `PARALLEL` / `ROUTING` / `LOOP`；其它值校验失败 |
| `inputKeys` / `outputKey` | 全部 | 与 GenericAgent 相同，读写 `OverAllState` |
| `subAgents` | 全部 | 至少 1 个；Sequential 按数组顺序 |
| `subAgents[].impl` | 全部 | 默认 `GENERIC_AGENT` |
| `subAgents[].ref` | 全部 | `generic:{id}` 或 `agentscope:{id}`，须与 impl 一致 |
| `subAgents[].instruction` | 全部 | 支持 `{stateKey}` 占位，执行前从 state 替换 |
| `subAgents[].outputKey` | 全部 | 子结果写入 state，供后续子 Agent 或父 `outputKey` 读取 |
| `modelConfigKey` | ROUTING | 路由器模型；子 Agent 自身模型仍走其注册规格 |
| `maxIterations` | LOOP | 默认 3，上限 10（防止失控） |
| `exitCondition*` | LOOP | 读子 Agent 写入的 state 键；缺失则校验失败 |
| `streamResponseKind` | 全部 | 继承现有 BIZ / OUTPUT 语义，由父节点统一声明 |

M1 只实现 `SEQUENTIAL` + `impl=GENERIC_AGENT`。未知 pattern 在所有阶段都拒绝，避免静默降级。

### 4.3 与图边的键策略

父节点 `outputKey` 使用图上已声明的 `keyStrategies`（默认 REPLACE）。  
子 Agent 的 `outputKey` 若未在图级 `keyStrategies` 声明，编译时自动补 `REPLACE`，并在校验报告中提示。

禁止子 Agent `outputKey` 与父节点 `outputKey` 同名，除非该 pattern 明确「最后一个子 Agent 的输出即父输出」（Sequential 允许最后一个相同，其余不允许）。

---

## 5. 编译流程

在 `DynamicGraphBuilder.buildSingleNodeAction` 增加分支，位置在 GenericAgent 判断之后、通用 `nodeRegistry.get` 之前：

```text
if category == SAA_WORKFLOW:
    factory = SaaWorkflowNodeFactory（ObjectProvider）
    if factory 不存在: 抛出「模块未启用」
    node = factory.create(graphId, nodeId, saaSpec, resolverContext)
    return node_async(node.toAction(nodeCtx))
```

`SaaWorkflowNodeFactoryImpl.create` 步骤：

1. 校验 `saaSpec`（见第 7 节）。
2. 对每个 `subAgents[]` 调用 `SubAgentResolver.resolve`，得到有序 `SubAgentBinding`。
3. 按 `pattern` 构建 FlowAgent：
   - `SEQUENTIAL` → `SequentialAgent.builder().subAgents(...)`
   - `PARALLEL` → `ParallelAgent`
   - `ROUTING` → `LlmRoutingAgent`（或当前 BOM 中的 Routing 实现类）
   - `LOOP` → `LoopAgent`，body 为内嵌 Sequential 或单个子 Agent，加上退出条件与 `maxIterations`
4. 将 FlowAgent 包成 `NodeAction`：
   - 进入时：按 `inputKeys` 从 `OverAllState` 取值，填入 Framework 初始 state。
   - 退出时：读取约定结果，写入父 `outputKey`。
5. 若 M1 验证发现 FlowAgent 可 `asNode()` / 导出 `CompiledGraph`，则改为 `stateGraph.addNode(id, compiledGraph)`，与子图路径一致。该优化不改变 DSL。

**状态边界：** 业务图只看见父节点的输入键与 `outputKey`。子 Agent 中间键可以写入同一 `OverAllState`（便于轨迹与 `{placeholder}`），但是图的下游边只应依赖父 `outputKey`。校验器对「下游边读取子中间键」给出警告，不在 M1 硬失败。

---

## 6. 子 Agent 适配

### 6.1 SPI

```text
interface SubAgentResolver {
    boolean supports(String impl);          // GENERIC_AGENT / AGENTSCOPE
    SubAgentBinding resolve(ResolveRequest request);
}

record ResolveRequest(
    String graphId,
    String name,
    String impl,
    String ref,
    String instruction,
    String outputKey,
    SaaWorkflowSpec parent
) {}
```

多个 Resolver 注入为 `List<SubAgentResolver>`。`impl` 无匹配器时编译失败。

### 6.2 GENERIC_AGENT（默认）

`ref = generic:{registeredNodeId}`。

解析步骤：

1. 用现有 `GenericAgentNodeFactory` / `GenericAgentNodeService` 取出 `GraphBoundAgentNode`。
2. `withGraphId(graphId)`，保持 MCP secret、工具命名空间与单节点一致。
3. 包一层薄适配器，实现 Framework 所需的子 Agent 类型（`ReactAgent` 或 `Agent` 基类，以 M1 spike 选定的 API 为准）：
   - `instruction` 中的 `{key}` 从当前 state 渲染为本次输入。
   - 调用 `GenericAgentNode.toAction`。
   - 将其 `outputKey` 映射到 `subAgents[].outputKey`。

不复制 GenericAgent 的 prompt / mcpKeys。注册节点改配置后，高阶节点下次编译即生效。

内联 `agentSpec`（不走注册中心的写法）中期不支持作为子 Agent。子 Agent 必须是引用，避免规格双份漂移。

### 6.3 AgentScope ReActAgent（可选，M3）

`ref = agentscope:{id}`。`id` 仍指向 ACE 注册的 Agent 定义（建议复用 GenericAgent 注册记录的 prompt / model / mcpKeys），只是**执行引擎**换成 AgentScope。

```text
GenericAgentSpec（目录中的规格，只读）
        │ 映射
        ▼
ReActAgent.builder()
    .name / .sysPrompt / .model / .toolkit(MCP·Skill 适配)
        │
        ▼
AgentScopeAgent（优先用 SAA starter 的包装，若 BOM 提供）
        │
        ▼
作为 FlowAgent 的 subAgent
```

映射规则：

| ACE | AgentScope / SAA 包装 |
|-----|------------------------|
| `prompt` + `promptKeys` 渲染结果 | `sysPrompt` |
| `modelConfigKey` 解析出的 endpoint / modelId | AgentScope `Model` 或 SAA `ChatModel` 桥 |
| `mcpKeys` / `skillKeys` | Toolkit；不在 AgentScope 侧另建 MCP 连接池，复用现有 `LessoAceGraphMcpSessionRegistry` 一类会话 |
| `memoryMode` | M3 只支持 `NONE` 与「沿用父图 thread 的只读记忆」；`READ_WRITE` 另立专项，避免双记忆源 |
| `instruction` | `AgentScopeAgent.instruction`，占位符与 GenericAgent 路径相同 |

Msg 不得泄漏到 `OverAllState`。适配器只回写 `outputKey` 对应的字符串或结构化 Map。

---

## 7. 校验

在现有图校验上增加 `SaaWorkflowValidator`，发布前与试运行前都执行。

| 规则 | 级别 |
|------|------|
| `pattern` 属于已开放集合 | 错误 |
| `subAgents` 为空 | 错误 |
| `ref` 前缀与 `impl` 不一致 | 错误 |
| `generic:{id}` 在注册中心不存在或未启用 | 错误 |
| LOOP 缺少 `maxIterations` 或退出条件 | 错误 |
| `maxIterations` > 10 | 错误 |
| 子 `outputKey` 重名 | 错误 |
| ROUTING 子 Agent 少于 2 | 错误 |
| 下游节点读取子中间键 | 警告 |
| `impl=AGENTSCOPE` 但运行时无 Resolver | 错误（编译期） |

设计器保存草稿可只做前端轻校验；发布必须走后端完整校验。

---

## 8. 设计器

不改画布引擎，只扩展节点目录与属性面板。

| UI | 行为 |
|----|------|
| 节点面板分组「高阶模式」 | 一项「多智能体模式」，拖入后 `category=SAA_WORKFLOW` |
| pattern 下拉 | Sequential / Parallel / Routing / Loop |
| 子 Agent 表格 | name、impl、ref（下拉已注册 GenericAgent）、instruction、outputKey |
| impl 下拉 | 默认 GenericAgent；仅当后端能力探测到 AgentScope 时显示 AgentScope |
| Loop 附加项 | maxIterations、退出键、比较符、阈值 |
| 画布徽章 | 节点角标显示 pattern，避免与普通 GenericAgent 混淆 |
| 帮助文案 | 「图上的并行/条件边管阶段；本节点管阶段内部的固定协作」 |

中期不做：在父节点里再展开子画布。子 Agent 仍去「通用 Agent」面板编辑。点击 ref 可跳转。

---

## 9. 运行时与可观测

### 9.1 执行

`AceGraphExecutionController` 不改协议。高阶节点对调用方仍是普通图节点。  
`streamResponseKind` 以父节点为准，避免每个子 Agent 各推一条 OUTPUT。

子 Agent 若内部流式，M1 聚合为父节点的 BIZ 增量；M4 再考虑把子步骤边界打进现有轨迹结构（`agent:{nodeId}` 之下增加 `sub:{name}`）。

### 9.2 轨迹（M4 必达，M1 最低限度）

最低限度（M1）：日志包含 `graphId`、`nodeId`、`pattern`、子 Agent `name`、耗时、是否成功。  
M4：试运行面板展示有序列表：

```text
SAA_WORKFLOW sql_quality [SEQUENTIAL]
  ├─ sql_generator  ok  outputKey=sql
  └─ sql_rater      ok  outputKey=score
→ state.sql_score = ...
```

### 9.3 MCP 会话

子 Agent 与父图共用同一次执行的 MCP session / threadId。禁止每个子 Agent 新建孤立会话，否则垂直场景的登录态与工具连接会断。

---

## 10. 兼容与版本

| 项 | 策略 |
|----|------|
| 旧 JSON | 无 `saaSpec` 的图行为不变 |
| BOM | `spring-ai-alibaba-bom` 统一 graph-core、agent-framework、starter-agentscope |
| 可选依赖 | core 只用接口；实现模块 `provided` 或由宿主显式引入 |
| 序列化 | `SaaWorkflowSpec` 使用 `@JsonIgnoreProperties(ignoreUnknown = true)`，便于后续加字段 |
| 试点 | 新图 ID 试点；不修改 `ztc-service-agent` 的边结构 |

---

## 11. M1 技术切片（建议先做的 spike）

在写完整 Factory 之前，用一个不进设计器的单元测试回答两个问题：

1. 当前 BOM 的 `SequentialAgent` 能否在 ACE 的 `StateGraph` 里作为 `NodeAction` 或子 `CompiledGraph` 运行，并写回 `OverAllState`。
2. 一个现有 `GenericAgentNode.toAction` 能否包成该 `SequentialAgent` 的 subAgent，而 MCP key 仍由 ACE 目录注入。

结论写入本方案附录，再决定第 5 节步骤 5 走适配器还是子图挂载。  
在 spike 完成前，不开始设计器表单。

Spike 通过标准：

- 两个 stub 子 Agent 顺序执行，第二个能读到第一个写入的 key。
- 异常信息包含子 Agent 名。
- 不引入 AgentScope 坐标。

---

## 12. 待决问题

| 编号 | 问题 | 决定时机 |
|------|------|----------|
| Q1 | FlowAgent 在 1.1.2.x 是否暴露 `asNode()` / `CompiledGraph` | M1 spike |
| Q2 | GenericAgent 包进 FlowAgent 时，用官方 `Agent` 接口还是反射适配 | M1 spike |
| Q3 | Routing 用 `LlmRoutingAgent` 还是图内条件边再包一层 | M2 开始前；默认用 Framework 类，避免再造路由 |
| Q4 | AgentScope 桥用 `starter-agentscope` 还是直接 `agentscope-core` | M3 开始前；优先 starter |
| Q5 | 子 Agent `READ_WRITE` 记忆与父图 thread 如何合并 | M3 不做；默认 NONE |
| Q6 | 轨迹事件是否扩展现有 SSE 协议 | M4；M1 只打日志 |

---

## 13. 明确不做的技术路径

- 不在 `DynamicGraphBuilder` 里直接 new `ReActAgent` 替换 `GenericAgentNode`。
- 不把 AgentScope `SequentialPipeline` / `FanoutPipeline` 实现成另一种 `pattern`。
- 不让 `saaSpec.subAgents` 内嵌完整 `GenericAgentSpec`。
- 不把高阶节点实现成「编译期展开成多条普通边」。展开会让版本 diff、设计器坐标和运行时节点 ID 分裂；模式必须保持为单个图节点。
