# SAA Parallel：双视角并行（M2 样例）

```text
__START__ → dual_view(SAA_WORKFLOW / PARALLEL) → __END__
                 ├─ view_alpha → out_a
                 └─ view_beta  → out_b
                      父 outputKey=merged（ParallelAgent.mergeOutputKey）
```

前置：引入 `ace-graph-dsl-saa-agent`；入库 `agent-nodes.json`；导入本图。

自动化：`MultiPatternSaaWorkflowFactoryIntegrationTest#parallelMergesTwoSubAgentOutputs`
