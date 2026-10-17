# SAA Sequential · AGENTSCOPE（M3 样例）

与 `saa-sequential-sql-quality` **同图拓扑**，仅子 Agent `impl/ref` 切换为 AgentScope。

```text
__START__ → sql_quality(SEQUENTIAL) → __END__
                 ├─ sql_generator  impl=AGENTSCOPE  ref=agentscope:agent:sql-gen → sql
                 └─ sql_rater      impl=AGENTSCOPE  ref=agentscope:agent:sql-rater → score
```

## 前置

1. 宿主同时依赖：
   - `ace-graph-dsl-saa-agent`（高阶节点 Factory）
   - `ace-graph-dsl-agentscope-agent`（`SubAgentResolver` + `starter-agentscope`）
2. 入库 `agent-nodes.json`（ACE 注册目录不变；仅执行引擎切换）
3. `/capabilities/saa` 应返回 `agentscopeEnabled=true`

## 自动化

```bash
mvn -pl ace-graph-dsl-agentscope-agent -am "-Dtest=AgentScopeSequentialFactoryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 口径（Q4 / Q5）

- 桥接：`spring-ai-alibaba-starter-agentscope`（BOM 1.1.2.2）→ `AgentScopeAgent`
- **不是**用 AgentScope 替换图主编排
- 子记忆：`NONE`（InMemoryMemory，不写 remote READ_WRITE）
