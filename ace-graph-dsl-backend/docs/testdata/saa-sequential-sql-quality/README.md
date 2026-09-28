# SAA Sequential：SQL 生成与评分（M1 样例）

本目录是 **SAA_WORKFLOW + SEQUENTIAL** 的可导入样例，对应开发计划 M1「一条 Sequential 样例图」。

## 图结构

```text
__START__ → sql_quality(SAA_WORKFLOW / SEQUENTIAL) → __END__
                 │
                 ├─ sql_generator  ref=generic:sql-gen   → outputKey=sql
                 └─ sql_rater      ref=generic:sql-rater → outputKey=score
                                                             父 outputKey=sql_score（取最后子键）
```

## 文件

| 文件 | 说明 |
|------|------|
| `graph-definition.json` | 图定义（含 `saaSpec`） |
| `agent-nodes.json` | 需先入库的两个 GENERIC_AGENT 定义（`sql-gen` / `sql-rater`） |

## 前置

1. 宿主依赖 **`ace-graph-dsl-saa-agent`**（否则校验/编译报「模块未启用」）。
2. 将 `agent-nodes.json` 中的节点经「节点面板 → 通用 Agent」创建入库（或调用 `/api/graph/agents`）。
3. 导入/保存 `graph-definition.json`。

## 自动化验证

```bash
cd ace-graph-dsl/ace-graph-dsl-backend
mvn -pl ace-graph-dsl-saa-agent -am "-Dtest=SequentialSaaWorkflowFactoryIntegrationTest" test
```

该 JUnit 用 stub GenericAgent 模拟 `generic:sql-gen` / `generic:sql-rater`，验证 Spec→Factory→方式 A 写回 `sql` / `score` / `sql_score`。

## 口径

- 挂载：**方式 A（NodeAction）**
- 子 Agent：**官方 BaseAgent 包装**，内部委托 GenericAgent
- **不改 SSE**（Q6）；子步骤见服务端日志
- 与图级 FanOut / 条件边无关（节点管内协作）

详见：`ace-graph-dsl/docs/ACE-Graph-DSL-SAA高阶模式节点-开发设计与计划.md`
