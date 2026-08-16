# Agent 流式输出与可定制格式

本文档说明 ace-graph-dsl 的流式输出能力，以及业务项目如何**接入真实 LLM 流式**与**覆写流式输出格式**（例如与前端约定私有 JSON 协议、携带 `thinking` / `isEnd` 等业务字段）。

---

## 1. 它能做什么

- **默认即流式**：`GENERIC_AGENT` 节点在 LLM 调用时**逐 token** 推送片段，前端通过 SSE 实时消费。
- **默认即原生格式**：业务不定制时，按原生 SSE 结构下发（见 §3），框架零改动即可工作。
- **格式可定制**：业务只需提供一个 `@Bean StreamingChunkFormatter` 实现，即可把每个片段转成自己的前后端协议（JSON chunk、`thinking` / `isEnd` 等任意业务字段、或不输出 `type:message` 类片段）。
- **向后兼容**：已自定义旧版 `GraphExecutionEventAdapter` 的项目继续生效（见 §6）。

---

## 2. 启用与接入

流式端点由可选 REST Controller 提供，需开启 Web 层：

```yaml
# application.yml
ace:
  graph:
    dsl:
      web:
        enabled: true                      # 启用 Web 层（REST/SSE）
        execution:
          enabled: true                    # 启用通用图执行端点
```

端点（基础路径默认 `/api/graph`，由 `ace.graph.dsl.web.base-path` 控制）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/execution/{graphId}/stream` | 流式执行（SSE），逐 token 下发 |
| `POST` | `/execution/{graphId}/invoke` | 同步执行，返回最终状态 |
| `POST` | `/execution/{graphId}/resume` | HITL 恢复（SSE） |

请求体：

```json
{
  "inputs": { "question": "你好" },
  "threadId": "可选，不传则自动生成",
  "modelOverrides": {
    "global": { "modelId": "gpt-4o" },
    "nodeOverrides": { "branchA": { "modelId": "qwen-max" } }
  }
}
```

其中 `modelOverrides` 为可选，用于**请求级动态指定某节点 / 全局使用的模型**（详见 CHANGELOG）。

---

## 3. 默认原生 SSE 格式

业务未定制时，每个片段下发如下 JSON（一条 SSE `data:` 一个片段）：

**LLM 生成中的 token 片段**

```json
{ "type": "chunk", "node": "agentNodeA", "chunk": "你好", "graphId": "demo" }
```

**本段流结束片段**（节点收尾时自动追加）

```json
{ "type": "chunk", "node": "agentNodeA", "chunk": "", "isEnd": true }
```

**普通节点输出**（非 LLM 流式节点）

```json
{ "type": "node", "node": "evalNode", "data": { "score": 90 } }
```

**HITL 中断**

```json
{ "type": "interrupt", "node": "humanNode", "interrupted": true, "data": { ... } }
```

> 注意：默认格式下每个 token 片段都带一个 `type:chunk` 的包裹。`StreamingChunkFormatter` 的存在意义，正是让业务**去掉这个包裹**、换成自己的协议。

---

## 4. 接入真实 LLM 流式（逐 token 的来源）

框架本身不绑定具体 LLM 实现，core 内置 `StubChatClientFactory` 仅用于端到端验证（其 `stream()` 退化为单次 `call` 的单个片段）。

业务引入真实 LLM 适配模块（如 `ace-graph-dsl-agent`）后，需让 `AgentChatClient` 的 **`stream(...)` 返回真正的逐 token `Flux`**：

```java
// 真实 LLM 适配模块中实现 AgentChatClient
public class OpenAiAgentChatClient implements AgentChatClient {

    private final OpenAiChatModel chatModel;

    @Override
    public String call(String prompt, Map<String, Object> vars, GenericAgentSpec spec) {
        return chatModel.call(render(prompt, vars)).getContent();
    }

    // 覆写 stream：返回逐 token Flux —— 节点侧会自动订阅并经桥接通道透传给 SSE
    @Override
    public Flux<String> stream(String prompt, Map<String, Object> vars, GenericAgentSpec spec) {
        return chatModel.stream(render(prompt, vars))
                        .map(resp -> resp.getResult().getOutput().getContent());
    }
}
```

只要 `AgentChatClient.stream()` 返回多元素 `Flux`，`GenericAgentNode` 就会**逐 token**推送，无需改任何图定义。

> 若未覆写 `stream()`，节点退化为单次片段（一个 chunk 包含完整回复），流式机制本身依然工作，只是没有"逐字"效果。

---

## 5. 覆写流式格式（核心：StreamingChunkFormatter）

这是业务最常用、也是本能力的关键入口。

### 5.1 原理

每个片段下发前，控制器都会调用一个 `StreamingStreamingChunkFormatter.format(StreamingContext)` 把它转成可序列化的负载对象（通常是 `Map` 或业务 DTO）。**业务只要提供一个 `StreamingChunkFormatter` Bean，就接管了所有片段的最终形状。**

优先级（控制器 `resolveFormatter()`）：

1. **自定义 `StreamingChunkFormatter` Bean** → 用它（业务定制格式）。
2. 否则若自定义了旧版 `GraphExecutionEventAdapter` → 委派给它（向后兼容）。
3. 否则 → 原生默认格式 `DefaultStreamingChunkFormatter`。

### 5.2 上下文对象 `StreamingContext`

`format()` 收到的上下文包含格式化所需的一切：

| 方法 | 说明 |
| --- | --- |
| `getTokenChunk()` | 桥接 token 片段（`TokenChunk`，来自 LLM 逐 token 流）；非 token 时为 `null` |
| `getOutput()` | 来自 `graph.stream()` 的 `NodeOutput`（框架内置流式节点会带 `StreamingOutput`）；来自 token 时为 `null` |
| `getGraphId()` | 所属图 ID |
| `getNodeId()` | 节点 ID |
| `isStreaming()` | 是否来自 LLM 流式输出 |
| `getOutputType()` | 流式输出类型（`AGENT_MODEL_STREAMING` / `AGENT_MODEL_FINISHED` 等）；非流式为 `null` |
| `isLast()` | 是否为本段流的最后一个片段（`TokenChunk.last()` 或 `OutputType` 以 `_FINISHED` 结尾） |

`TokenChunk` 字段：`nodeId` / `token`（文本片段）/ `outputType` / `last`。

### 5.3 示例：业务私有 JSON 协议（thinking / isEnd）

假设你的前端协议要求：

- 思考过程：`{ "event": "delta", "thinking": true, "content": "..." }`
- 正文生成：`{ "event": "delta", "content": "..." }`
- 结束：`{ "event": "done", "isEnd": true }`
- **不要** `type:message` 这种包裹；**不要**输出空 chunk 之外的噪音。

```java
@Configuration
public class MyStreamingConfig {

    @Bean
    public StreamingChunkFormatter myStreamingFormatter() {
        return ctx -> {
            // 来自图（非 LLM 流式）的节点输出：直接透传 data，或按需忽略
            if (ctx.getOutput() != null && !ctx.isStreaming()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("event", "node");
                m.put("node", ctx.getNodeId());
                if (ctx.getOutput().state() != null) {
                    m.put("data", ctx.getOutput().state().data());
                }
                return m;
            }

            // HITL 中断
            if (ctx.getOutput() instanceof InterruptionMetadata meta) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("event", "interrupt");
                m.put("node", meta.node());
                return m;
            }

            // LLM 流式 token（桥接或框架内置）
            TokenChunk tc = ctx.getTokenChunk();
            String content = tc != null ? tc.token() : "";
            OutputType ot = ctx.getOutputType();
            boolean thinking = ot != null && ot.name().contains("THINKING");

            Map<String, Object> m = new LinkedHashMap<>();
            if (ctx.isLast()) {
                m.put("event", "done");
                m.put("isEnd", true);
            } else {
                m.put("event", "delta");
                m.put("thinking", thinking);
                m.put("content", content);
            }
            return m;
        };
    }
}
```

前端将收到（示例，思考阶段）：

```json
{ "event": "delta", "thinking": true, "content": "让我先分析一下用户意图…" }
{ "event": "delta", "thinking": false, "content": "你好，我是助手。" }
{ "event": "done", "isEnd": true }
```

> 业务字段（`thinking` / `isEnd` / `event` 等）完全由你定义，框架不感知、不强加。`isEnd` 由 `ctx.isLast()` 驱动，`thinking` 由 `OutputType` 驱动（真实 LLM 模块若支持 reasoning 片段，会经 `AGENT_MODEL_THINKING` 之类类型送达；若只区分「生成中/结束」，用 `isLast()` 即可）。

### 5.4 示例：完全自定义 payload（DTO）

格式化器返回的对象会经 `ObjectMapper` 序列化，因此也可以直接返回业务 DTO：

```java
record DeltaEvent(String event, String node, String content, boolean thinking, boolean isEnd) {}

@Bean
public StreamingChunkFormatter dtoFormatter() {
    return ctx -> {
        TokenChunk tc = ctx.getTokenChunk();
        return new DeltaEvent(
            ctx.isLast() ? "done" : "delta",
            ctx.getNodeId(),
            tc != null ? tc.token() : "",
            false,
            ctx.isLast()
        );
    };
}
```

### 5.5 契约提醒

- `format()` **绝不能抛异常**——异常由控制器兜底（`emitter.completeWithError`），不要在其中做可能失败的逻辑。
- 返回对象必须可序列化（Jackson 友好）。

---

## 6. 向后兼容：旧版 GraphExecutionEventAdapter

若项目已自定义 `GraphExecutionEventAdapter`（旧 SPI），且未提供 `StreamingChunkFormatter`，控制器会自动把每个片段委派给该 adapter 处理（仅当它是非默认实现时）。新的 `StreamingChunkFormatter` 优先级更高，二者无需同时实现。

```java
// 旧 SPI 仍可使用（无 StreamingChunkFormatter Bean 时）
@Bean
public GraphExecutionEventAdapter myEventAdapter() {
    return new DefaultGraphExecutionEventAdapter() {
        @Override
        public Object onChunk(String node, String chunk) { /* 自定义 */ }
    };
}
```

---

## 7. 设计要点（为什么这么做）

- **带外桥接通道 `GraphStreamBridge`**：`StateGraph.addNode` 只接受返回 `Map` 的 `AsyncNodeAction`，自定义节点**无法**把逐 token 片段直接注入 `graph.stream()` 的框架 flux（框架只在内置流式节点里发片段）。因此引入按 `runId(threadId)` 汇聚的桥接器：节点 push 片段 → 控制器合并「图 flux + 桥接 flux」→ 统一 SSE 下发。
- **`TokenChunk` 而非复用框架 `StreamingOutput`**：框架 `StreamingOutput` 构造器对 `chunk` 字段的赋值规则随版本不稳定（多数构造器不赋值、且无法同时指定 `OutputType`），直接 `new` 极易错位。故用最小信息的框架无关 `TokenChunk`，格式交给 `StreamingChunkFormatter` 决定。
- **流结束自动 `complete`**：控制器在 flux 完成 / 异常时调用 `GraphStreamBridge.complete(runId)`，关闭通道、避免 runId 泄漏；`ReactorGraphStreamBridge` 仅在 Web 层启用时注册为 Bean，无控制器消费时节点回落 NOOP。

---

## 8. API 速查

| 类型 | 位置 | 作用 |
| --- | --- | --- |
| `AgentChatClient.stream(...)` | `io.acelance.graph.dsl.agent` | 逐 token 模型调用 SPI；默认退化为单片段 |
| `GraphStreamBridge` / `ReactorGraphStreamBridge` / `NOOP` | `io.acelance.graph.dsl.streaming` | 按 runId 汇聚 token 的桥接通道 |
| `TokenChunk` | `io.acelance.graph.dsl.streaming` | 一个 LLM token 片段（`nodeId`/`token`/`outputType`/`last`） |
| `StreamingChunkFormatter` | `io.acelance.graph.dsl.execution` | **格式定制 SPI**，优先级最高 |
| `StreamingContext` | `io.acelance.graph.dsl.execution` | `format()` 的入参，携带片段全部可定制信息 |
| `DefaultStreamingChunkFormatter` | `io.acelance.graph.dsl.execution` | 原生默认格式（无定制时生效） |
| `AdapterDelegatingStreamingChunkFormatter` | `io.acelance.graph.dsl.execution` | 向后兼容委派旧版 adapter |
| `GraphExecutionController` | `io.acelance.graph.dsl.web` | `/stream`、`/resume` 端点，合并并下发 SSE |
