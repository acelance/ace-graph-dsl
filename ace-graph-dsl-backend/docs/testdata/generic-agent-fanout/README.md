# 通用 Agent 节点 + 异步扇出（端到端样例）

> 本目录是「异步扇出」能力的**子文档 / 样例**。完整设计与实现见仓库根目录
> `ASYNC_FANOUT.md`（技术文档）与 `ASYNC_FANOUT_GUIDE.md`（使用文档）；
> 变更记录见 `CHANGELOG.md`。

本目录下的 `graph-definition.json` 是一张**可直接端到端跑通**的示例图，用于验证两件事：

1. `GENERIC_AGENT` 通用 Agent 节点（元数据驱动，无需真实 LLM key 即可运行）。
2. 节点的**异步扇出（parallel fan-out）**——同一源节点通过 `parallel=true` 的边并发执行多个分支。

## 图结构

```
__START__ ─► agentSource(GENERIC_AGENT)
                 │
                 ├─[parallel=true]─► branchA(GENERIC_AGENT)
                 ├─[parallel=true]─► branchB(GENERIC_AGENT)
                 │
        branchA ─┤
        branchB ─┴─► aggregator(GENERIC_AGENT) ─► __END__
```

- `agentSource`：调度器，写 `agent_source_result`。
- `branchA` / `branchB`：读取 `agent_source_result`，分别写 `branchA_result` / `branchB_result`。
- `aggregator`：汇总两个分支结果，写 `agent_result`。

所有字段（`modelBaseUrl` / `modelApiKey` / `modelId` / `prompt` / `inputKeys` / `outputKey`）均内联在节点的 `agentSpec` 中。

## 如何运行

### 1. 后端 JUnit（推荐，最快验证并发）

`GenericAgentFanOutIntegrationTest` 会用带人工延迟的 `DelayChatModelFactory` 覆盖默认 `StubChatModelFactory`，并断言：

- `maxActiveBranches >= 2` —— **确定性**证明两条分支**真并发**执行（不依赖绝对耗时，避免计时抖动误判）；
- `agent_source_result` / `branchA_result` / `branchB_result` / `agent_result` 四个输出 key 均写回。

```bash
cd ace-graph-dsl/ace-graph-dsl-backend
mvn.cmd -pl ace-graph-dsl-ai test -Dtest=GenericAgentFanOutIntegrationTest
```

> 说明：`DynamicGraphBuilder` 在构建时会把 `agentSource` 的两条 `parallel=true` 出边重写为一个内部扇出节点（`FanOutNodeAction`），
> 用独立线程池并发执行 `branchA` / `branchB` 子图，再把结果合并写回；自动补「fan-in 出边」到 `aggregator`。
> `spring-ai-alibaba-graph 1.1.0.0-M4` 的 `StateGraph` 没有原生并行边 API，这是绕过该限制的实现方式。
> 详见 `ASYNC_FANOUT.md`。

### 2. 设计器 / 运行时（需内置 Stub 或真实适配器）

- 此 JSON 可直接作为图定义导入/保存（`modelApiKey` 落库时会被 `AgentSecretMasking` 自动掩码，仅留后 4 位）。
- 未提供业务 `ChatModelFactory` 时，后端默认用 `StubChatModelFactory` 返回固定 JSON 结构，整图可端到端跑通。
- 业务注册真实 `ChatModelFactory`（返回 spring-ai `ChatModel`）后自动切换为真实模型调用。

> **前端试运行注意**：早期版本存在 `subgraphRef` 空字符串误判（前端序列化每个节点都带 `subgraphRef: ""`，
> 被误判为子图节点，试运行报「子图未定义」）。该问题已在 `NodeRef.hasSubgraph()` 加 `!isBlank()` 防护修复
> （见 `CHANGELOG.md` 同日修复条目）。**修复后请重启后端服务**让新 `NodeRef.class` 生效。

## 关键约束

- 并行扇出的分支节点**不支持** `AGENT` 循环节点（会显式报错）。
- `keyStrategies` 中对应 key 需声明为 `REPLACE`，分支结果才能正确落回主图 state。
- 单条 `parallel=true` 边会退化为普通顺序边（无扇出语义），需同源 ≥ 2 条才触发并发。

## 参考文档

- 技术文档：`ASYNC_FANOUT.md`（架构 / `FanOutNodeAction` / 边契约 / 并发正确性）
- 使用文档：`ASYNC_FANOUT_GUIDE.md`（编写 / 运行 / 参数速查 / 排错）
- 变更记录：`CHANGELOG.md`
