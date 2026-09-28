# ace-graph-dsl-saa-agent

可选模块：SAA Agent Framework 高阶模式节点（挂载方式 **A = NodeAction**）。

## M0 Spike（已通过）

- 用例：`src/test/java/.../spike/SequentialAgentNodeActionSpikeTest.java`
- 适配器：`SequentialFlowAgentNodeAction`
- 结论：`SequentialAgent` + 官方 `ReactAgent` subAgent 可经 NodeAction 写入 ACE `OverAllState`；第二步可读第一步 `outputKey`
- 详见：`docs/ACE-Graph-DSL-SAA高阶模式节点-开发设计与计划.md` 附录 A.2

## 运行 Spike

在 `ace-graph-dsl-backend` 下：

```text
mvn -pl ace-graph-dsl-saa-agent -am -Dtest=SequentialAgentNodeActionSpikeTest test
```

## 说明

- **不**由 `ace-graph-dsl-spring-boot-starter` 强制传递；宿主按需依赖。
- M1 起补齐 `SaaWorkflowSpec` / Factory / Builder 分支 / 设计器。
