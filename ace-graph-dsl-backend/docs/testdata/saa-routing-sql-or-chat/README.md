# SAA Routing：SQL 或闲聊（M2 样例）

```text
__START__ → router_node(SAA_WORKFLOW / ROUTING) → __END__
                 ├─ sql_generator (generic:sql-gen) → sql_out
                 └─ chat_helper   (generic:chat-helper) → chat_out
                      路由器 ChatModel ← modelConfigKey=models:default
```

前置：`ace-graph-dsl-saa-agent`；入库子 Agent；宿主可解析 `models:default`（或依赖 AutoConfig fallback）。

自动化：`MultiPatternSaaWorkflowFactoryIntegrationTest#routingSelectsNamedSubAgentViaStubChatModel`

说明：Routing 用 Framework `LlmRoutingAgent`，**不是**图条件边。
