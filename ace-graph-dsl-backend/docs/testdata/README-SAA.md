# SAA 高阶节点样例索引（M1～M4）

本目录下 SAA 相关可导入样例。宿主需引入 `ace-graph-dsl-saa-agent`；AgentScope 样例额外引入 `ace-graph-dsl-agentscope-agent`。

| 目录 | pattern / impl | 说明 |
|------|----------------|------|
| [saa-sequential-sql-quality](./saa-sequential-sql-quality/) | SEQUENTIAL / GENERIC_AGENT | M1 基线：SQL 生成 → 评分 |
| [saa-parallel-dual-view](./saa-parallel-dual-view/) | PARALLEL | M2：双视角并行 |
| [saa-routing-sql-or-chat](./saa-routing-sql-or-chat/) | ROUTING | M2：LLM 选路（非图条件边） |
| [saa-loop-score-until](./saa-loop-score-until/) | LOOP | M2：打分直至达标 |
| [saa-agentscope-sequential-sql](./saa-agentscope-sequential-sql/) | SEQUENTIAL / AGENTSCOPE | M3：同拓扑切换 impl |

## 设计器短说明

1. 节点面板拖入「高阶多智能体」→ 配置 pattern 与子 Agent 表。  
2. 子 Agent `ref` 指向已入库通用 Agent（`generic:` / `agentscope:`）。  
3. **试运行**后，若节点 state 含 `ace.graph.dsl.saa.subSteps`，轨迹面板会展示子步骤树。  
4. 图边 vs 高阶节点：见仓库 `docs/ACE-Graph-DSL-何时用图边何时用高阶节点.md`。

## Q6（SSE）

中期 **不扩展** SSE 子步骤事件；子步骤靠日志 + 试运行 state 摘要。详见开发设计附录 A。
