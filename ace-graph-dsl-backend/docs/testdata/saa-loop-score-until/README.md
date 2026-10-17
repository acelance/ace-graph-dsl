# SAA Loop：打分直至达标（M2 样例）

```text
__START__ → score_loop(SAA_WORKFLOW / LOOP) → __END__
                 └─ scorer → score
                      退出：score GT 0.8 或 maxIterations=5
                      父 outputKey=final_score
```

前置：`ace-graph-dsl-saa-agent`；入库 `sql-rater`。

自动化：`MultiPatternSaaWorkflowFactoryIntegrationTest#loopRunsUntilExitConditionMet`

策略：有 `exitConditionKey` → `StateKeyExitLoopStrategy`；无 key → `CountLoopStrategy`。
