# ace-graph-dsl-langfuse

Ace Graph DSL 的**可选** Langfuse 观测模块。为图执行提供「节点级链路追踪 + 节点内 LLM 调用细节」，
并且与「请求级动态模型覆盖」天然联动——当某个节点在本次请求里被动态指定了模型时，Langfuse 里看到的是
**运行时真实模型**，而不是图定义里的静态值。

> 零外部依赖：仅用 JDK 原生 `HttpClient` 直连 Langfuse ingestion API，不引入任何第三方 SDK。
> 默认关闭；宿主应用引入本模块依赖且显式 `enabled=true` 时才真正收发 trace，否则对图执行零侵入。

---

## 1. 接入方式

在宿主应用（通常是 `ace-graph-dsl-spring-boot-starter` 所在工程）的 `pom.xml` 增加依赖：

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-langfuse</artifactId>
    <version>${ace-graph-dsl.version}</version>
</dependency>
```

Spring Boot 自动扫描 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
加载 `LangfuseAutoConfiguration`，按条件注册全部 bean。

开启（默认 `enabled=false`）：

```yaml
ace:
  graph:
    dsl:
      langfuse:
        enabled: true
        base-url: http://localhost:3000   # 自托管；云版填 https://cloud.langfuse.com
        public-key: pk-lf-...
        secret-key: sk-lf-...
        trace-name: ace-graph-dsl-run
        flush-interval-ms: 2000
        max-batch-size: 50
```

---

## 2. 配置项（前缀 `ace.graph.dsl.langfuse`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。关闭时所有 bean 不注册，空操作。 |
| `base-url` | `https://cloud.langfuse.com` | Langfuse 服务地址（自托管填 `http://host:3000`）。 |
| `public-key` | — | Basic Auth 用户名（`pk-...`）。 |
| `secret-key` | — | Basic Auth 密码（`sk-...`）。 |
| `trace-name` | `ace-graph-dsl-run` | 每个 run 的 trace 名称。 |
| `flush-interval-ms` | `2000` | 批量上报间隔（毫秒）。 |
| `max-batch-size` | `50` | 单批最大事件数（超出分多批）。 |
| `connect-timeout-ms` | `5000` | HTTP 连接超时。 |
| `write-timeout-ms` | `10000` | HTTP 写超时。 |

---

## 3. 事件模型（Langfuse ingestion）

每个 run 一条 `trace`，每个节点一条 `span`，节点内的每次 LLM 调用一条挂在 span 之下的 `generation`：

| Langfuse 事件 | 触发时机 | 关键字段 |
| --- | --- | --- |
| `trace.create` | run 开始（`onStart`） | `id`, `name=traceName`, `metadata.runId` |
| `span.create` | 节点开始（`before`） | `id`, `traceId`, `name=nodeId` |
| `span.update` | 节点结束 / 异常（`after` / `onError`） | `endTime`；异常时含 `level=ERROR`, `statusMessage` |
| `trace.update` | run 结束（`onComplete`） | `endTime` |
| `generation.create` | 节点内 LLM 调用边界（finally） | `model`, `input(prompt)`, `output(response)`, `parentObservationId=spanId`, 错误时 `level=ERROR` |

> runId 优先级：state 保留键 `ace.graph.dsl.runId` > `RunnableConfig.threadId`。
> 采用 state 保留键是为了让**并行扇出分支**（其 `RunnableConfig` 不带 `threadId`）也能归并到同一 trace，
> 并与 `GenericAgentNode` 内 `TraceRecorder` 推送的 `generation` 对齐到同一 `runId`。

---

## 4. 与「请求级动态模型覆盖」的关系

这正是本模块最关键的诉求：**每个节点都可能在单次请求里被动态指定使用的模型**。

请求方在 `POST /execution/{graphId}/invoke` 的 body 里带上：

```json
{
  "inputs": { "x": "你好" },
  "threadId": "run-2026-...",
  "modelOverrides": {
    "global":  { "modelId": "gpt-4o", "modelBaseUrl": null, "modelApiKey": null },
    "nodeOverrides": {
      "summarize": { "modelId": "claude-3-5-sonnet", "modelBaseUrl": null, "modelApiKey": null }
    }
  }
}
```

- `global` 覆盖所有 `GENERIC_AGENT` 节点；`nodeOverrides[<nodeId>]` 精确覆盖单个节点，**优先级高于 global**。
- 控制器把 `modelOverrides` 与 `threadId`（作为 runId）以**保留键**注入初始 state；
  `GenericAgentNode` 从 state 读取并应用（`spec.withOverride(...)`），再调用 LLM。
- 节点 `finally` 中通过 `TraceRecorder.recordLLM(...)` 把**实际生效的模型**记进 Langfuse `generation`，
  于是 trace 里看到的是 `claude-3-5-sonnet`（summarize 节点）与 `gpt-4o`（其余节点），而不是图定义里的静态值。
- 返回结果前控制器执行 `stripReserved`，剔除 `ace.graph.dsl.` 前缀的保留键，不泄漏到最终输出。

> 请求级覆盖 API 见 `io.acelance.graph.dsl.runtime.ModelOverrideSpec` / `ModelOverride`，
> 及 `ace-graph-dsl-spring-boot-starter` 的 `GraphExecutionController.ExecutionRequest`。

---

## 5. 扩展点

- **换观测后端**：本模块只是 `TraceRecorder` / `GraphExecutionListener` 的一个实现。
  要接 OpenTelemetry / Jaeger，另写一个 `TraceRecorder` + `GraphExecutionListener` 的 `@Component` 即可，core 不耦合 Langfuse。
- **`LangfuseHttpSender`**：发送抽象，便于单测用内存桩替换真实 HTTP（见模块内 `*Test`）。

---

## 6. 容错与性能

- 所有发送在 `flush()` 中执行；失败仅 `log.warn` 并丢弃本批，**绝不影响图执行**。
- 批量 + 单线程定时机 flush，避免每次 LLM 调用都打网络。
- 进程退出（`LangfuseClient.close()`）时执行最后一次 flush，尽量不丢事件。
- `TraceRecorder.recordLLM` 与 `GraphExecutionListener` 各回调均自防御吞异常（观测异常不得中断业务）。
