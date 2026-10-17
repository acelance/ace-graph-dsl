# 何时用图边、何时用高阶节点（一页培训）

> 配套：[FAQ 与统一口径](./ACE-Graph-DSL-多智能体FAQ与统一口径.md) · [高阶模式集成目标说明](./ACE-Graph-DSL-高阶模式集成目标说明.md)

## 一句话

| 场景 | 用什么 |
|------|--------|
| 业务阶段串行、意图分流、扇出汇总、HITL | **图边**（已有 ACE 编排） |
| 同一节点内多个子 Agent 协作（串行/并行/路由/循环） | **`SAA_WORKFLOW` 高阶节点** |

## 图边（图管阶段）

```text
__START__ → 意图识别 ─条件边─► HR 节点 / IT 节点 → 汇总 → __END__
                 FanOut ──► 多个业务节点 ──► FanIn
```

- 分流的是 **画布上的阶段节点**
- 可 checkpoint、可 HITL、可对接现有 SSE
- **不要**用条件边「假装」节点内 Routing

## 高阶节点（节点管内）

```text
SAA_WORKFLOW pattern=SEQUENTIAL|PARALLEL|ROUTING|LOOP
    └─ subAgents[]  → generic:{id} 或 agentscope:{id}
```

- 协作的是 **同一节点内的子 Agent**
- 挂载方式 A：`NodeAction`；不改图主编排模型
- 子 Agent 记忆默认 **NONE**（Q5）；流式仍走父节点（Q6）

## 选型口诀

1. 「下一阶段换一条业务链」→ 图边  
2. 「在一个黑盒里先 A 再 B / 并行 / 选一个 / 多轮」→ 高阶节点  
3. 两者可同图：图边管阶段，高阶节点管局部协作  

## 试运行看子步骤

试运行 / 调试 state 中若出现：

- `ace.graph.dsl.saa.subSteps`：子 Agent 摘要树  
- `ace.graph.dsl.saa.subStepsMeta`：pattern / costMs  

**不**依赖新增 SSE 事件（Q6 结论：中期不扩展 SSE）。

## 样例入口

见 [后端 testdata 索引](../ace-graph-dsl-backend/docs/testdata/README-SAA.md)。
