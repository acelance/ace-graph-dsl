# SAA Sequential · AGENTSCOPE（路径 B 样例）

与 `saa-sequential-sql-quality` **同图拓扑**，仅子 Agent `impl/ref` 切换为 AgentScope。

```text
__START__ → sql_quality(SEQUENTIAL) → __END__
                 ├─ sql_generator  impl=AGENTSCOPE  ref=agentscope:agent:sql-gen → sql
                 └─ sql_rater      impl=AGENTSCOPE  ref=agentscope:agent:sql-rater → score
```

## 前置

1. 宿主同时依赖：
   - `ace-graph-dsl-saa-agent`（高阶节点 Factory）
   - `ace-graph-dsl-agentscope-agent`（路径 B：自研 Adapter + `agentscope-core:2.0.4`）
2. 入库 `agent-nodes.json`（ACE 注册目录不变；仅执行引擎切换）
3. `/capabilities/saa` 应返回 `agentscopeEnabled=true`，`agentscopeBridge=ace-graph-dsl-agentscope-agent@io.agentscope:agentscope-core:2.0.4`

## 自动化

```bash
mvn -pl ace-graph-dsl-agentscope-agent -am "-Dtest=AgentScopeSequentialFactoryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 口径（路径 B / Q5）

- 桥接：自研 `AceAgentscopeReActBaseAgentAdapter` → `ReActAgent`（`agentscope-core:2.0.4`）
- **不是**用 AgentScope 替换图主编排
- 子记忆：`NONE`（不写 remote READ_WRITE）
