# ace-graph-dsl-saa-agent

可选模块：SAA Agent Framework 高阶模式节点（挂载方式 **A = NodeAction**）。

## 能力（M1）

| 组件 | 说明 |
|------|------|
| `SaaWorkflowNodeFactoryImpl` | 按 `saaSpec` 组装 `SequentialAgent` → `NodeAction` |
| `GenericAgentSubAgentResolver` | `impl=GENERIC_AGENT`，`ref=generic:{id}` → 官方 `BaseAgent` 包装 |
| `GenericAgentBaseAgentAdapter` | Q2：官方 Agent 形态，内部委托 `GraphBoundAgentNode` |
| `AceGraphDslSaaAgentAutoConfiguration` | 注册 Factory / Resolver |

## 测试

```text
# M0 Spike
mvn -pl ace-graph-dsl-saa-agent -am "-Dtest=SequentialAgentNodeActionSpikeTest" test

# M1 Factory 集成
mvn -pl ace-graph-dsl-saa-agent -am "-Dtest=SequentialSaaWorkflowFactoryIntegrationTest" test
```

## 说明

- **不**由 `ace-graph-dsl-spring-boot-starter` 强制传递；宿主按需依赖。
- M1 仅开放 `pattern=SEQUENTIAL`；PARALLEL / ROUTING / LOOP 见 M2。
- 不修改 SSE 协议（Q6）；子步骤可见性靠服务端日志。
