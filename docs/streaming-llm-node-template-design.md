# 流式 LLM 节点模板服务设计方案

> 文档状态：设计稿  
> 适用范围：`ace-graph-dsl-backend` + `ace-graph-dsl-ui`（GENERIC_AGENT / Agent 节点）  
> 关联现状：`GenericAgentNode`、`GenericAgentSpec`、`GraphStreamBridge`、`StreamingChunkFormatter`、`TokenChunk`

### 写给读者：怎么读、怎么写这份方案

这份方案既要给人读，也要给**以后再 review 的人（或模型）**读。写糊了会出现两种成本：读者看不懂；过几轮对话后连作者自己也要靠翻代码、翻聊天记录才能「推断当初定了什么」——那不叫方案，叫谜语。

#### 三条硬规矩

1. **先讲人话，再给类名。** 每一节定案先回答：谁在用、发生什么事、允许怎样、禁止怎样、配在哪里。类名 / 接口名放在「对应代码」里，不能用行话顶替说明。  
2. **禁止「看起来很短、其实要猜」。** 例如不要只写「与图编辑权限对齐」——必须写清：哪些 HTTP 接口、要检查哪把权限钥匙、谁来配置、没配时会怎样。  
3. **缩写首次出现带一句解释**（例如：SPI = 业务可替换的扩展点；宿主 = 接入本框架的业务 Spring Boot 工程）。读者不应被迫翻源码才能懂方案在说什么。

#### 每条「定案」必须自带的五句话（缺一则未写完）

写完一节后，不看聊天记录、不看源码，只读本节，应能直接回答：

| # | 必须写清 | 反面例子（禁止） |
|---|---|---|
| 1 | **一句话结论**（允许 / 禁止 / 默认谁） | 「建议对齐现有能力」 |
| 2 | **作用对象**（哪个角色、哪个界面、哪条接口） | 「相关接口」 |
| 3 | **怎么做**（步骤或表：若 A 则 B） | 「按优先级处理」却不写优先级 |
| 4 | **明确不做什么** | 省略「不做什么」，后人以为范围很大 |
| 5 | **以后怎么验收**（日志长什么样、403、UI 提示等可观察结果） | 「实现时注意」 |

缺口表（§13）里标「已定案」的项，必须能链到满足上表的一节；链过去仍要靠推断的，视为**未闭环**，应改文案而不是改状态。

#### 给以后 review 的提醒

- 优先相信**本节白纸黑字**；聊天摘要、代码现状只能用来找「文案与实现是否一致」，不能用来补「方案没写清的结论」。  
- 若发现某节读完仍要猜：先改方案文案，再继续讨论或编码。

---

## 1. 背景与目标

### 1.1 问题

业务节点（如 Demo 中的 `TranslateJaNode`）往往把「翻译/问答等真实业务」委托给 Service（如 `TranslationService.translate`），而大模型调用本身存在大量共性：

- 提示词从哪里来（Nacos AI / K8s Config / Langfuse / 本地文件等）因项目而异
- 模型配置（base-url、api-key、model-id）常为请求级挂载
- 本地 Tools、MCP 远程工具需要按配置挂载，且需去重
- Skill 只需先暴露元数据，真正用到时再加载全文
- 多模态在节点间传递时，`UserMessage.media` 在序列化边界易丢失
- 不同节点流式 SSE 协议可能不同，需要可插拔的响应类型

### 1.2 目标

在 `ace-graph-dsl-backend` 提供 **流式 LLM 节点模板服务**（建议名 `StreamingLlmTemplate`）：

1. 用原生 Spring AI（`ChatClient` / `ChatClient.Builder` / `stream`）实现骨架
2. 用 `java.util.function` / SPI Function 把「取配置、取资源」交给业务实现
3. 仅面向 **Agent / GENERIC_AGENT** 节点；脚本等其他节点保持原样
4. UI 可勾选节点需要加载的资源类型与 key
5. 支持节点流式响应类型（默认 `BIZ` / `OUTPUT`，可扩展）
6. UI 定义 Agent 节点时必须能选择流式响应方式；后端提供「已实现流式响应类型 KEY」加载接口，供下拉配置（含业务扩展类型）

### 1.3 非目标

- Backend 不绑定具体注册中心（Nacos / K8s / Langfuse 等）实现
- 不改造脚本节点、纯 Java `NodeAction` 的既有语义（可自愿调用 Template）
- 不保证上游 Graph checkpoint 对 `UserMessage.media` 原样恢复（见第 8 节）
- **不定义 SSE 流式输出协议**：框架不规定 `thinking` / `isEnd` / `phase` 等字段，只提供挂载入口让业务已有协议接入（见 §9.0）
- **本期不做 AgentCard**：不设计绑定字段消费、不设计 `AgentCardResolver`、Template 不解析 Card（skill/工具/安全等信息若需要，先走独立 Skill/Tools/MCP 勾选）

---

## 2. 架构简述

| 层 | 职责 | 不负责 |
|---|---|---|
| `StreamingLlmTemplate` | 请求级装配、工具去重、流式桥接、结果写 State | 读 Nacos/K8s/Langfuse |
| Function / SPI Resolver | 按 key 取 Prompt / Model / Tools / MCP / Skill | 节点编排语义 |
| `StreamingChunkFormatter`（业务实现） | **决定 SSE 每帧形状** | — |
| 框架流式层 | 把 `streamResponseKind` 标签无损透传给业务 Formatter | **不定义任何 SSE 字段协议** |
| Agent 节点 | 读 UI 的 ResourceBinding + streamKind，调用 Template | 脚本节点逻辑 |
| UI | 勾选资源类型、填/选 key；经 kinds 接口选择流式响应方式 | 运行时拉真实内容 |
| 流式类型 Catalog API | 暴露可选 kind KEY 列表（配置驱动） | 业务协议内部细节 |

演进关系：现有 `GenericAgentNode` + SPI Bean → 升级为「请求级 Function 注入 + 资源勾选矩阵 + 流式类型标签透传」。

> **协议归属**：SSE 格式由业务项目掌控，框架只提供挂载入口（`StreamingChunkFormatter`，现网已有）。详见 §9.0。

```text
UI(ResourceBinding + streamKind)
        │
        ▼
GenericAgentNode / Agent
        │
        ▼
StreamingLlmTemplate  ── Function 填充 ──► Prompt / Model / Tools / MCP / Skill
        │
        ├── ChatClient.stream()（原生 Spring AI）
        ├── GraphStreamBridge.emit(TokenChunk + StreamResponseKind 标签)
        └── 业务 StreamingChunkFormatter（读 kind，自定协议）──► SSE
```

---

## 3. 总体数据流

### 3.1 资源装配与执行

```mermaid
flowchart TB
  subgraph UI["ace-graph-dsl-ui"]
    A[勾选资源类型] --> B[选/填 resource keys]
    B --> C[保存 agentSpec.resourceBindings + streamResponseKind]
    L[调用资源列表 API] -.-> B
    K[调用流式响应类型 KEY 列表 API] -.-> C
  end

  subgraph Runtime["图执行 /stream"]
    D[OverAllState + runId] --> E[GenericAgentNode]
    E --> F[StreamingLlmTemplate.execute]
    F --> G1[PromptResolver 按有序 keys]
    F --> G2[ModelMountResolver]
    F --> G3[LocalToolResolver]
    F --> G4[McpToolResolver]
    F --> G5[SkillCatalogResolver 仅元数据]
    G1 & G2 & G3 & G4 & G5 --> H[ToolDeduper + ChatClient.Builder]
    H --> I[原生 Spring AI stream]
    I --> J[GraphStreamBridge 带 kind 标签 → 业务 Formatter]
    I --> W[写回 outputKey]
  end

  C --> E
```

### 3.2 请求级挂载时序

```mermaid
sequenceDiagram
  participant Ctrl as GraphExecutionController
  participant Node as GenericAgentNode
  participant Tpl as StreamingLlmTemplate
  participant Sup as Function SPI
  participant AI as Spring AI ChatClient
  participant Fmt as 业务 StreamingChunkFormatter

  Ctrl->>Node: apply(state) / runId
  Node->>Tpl: execute(ctx, bindings, functions, streamKind)
  Tpl->>Sup: model / prompts / tools / mcp / skills
  Sup-->>Tpl: 配置与回调
  Tpl->>Tpl: 工具去重 + 命名空间
  Tpl->>AI: builder.defaultTools(...).build().stream()
  loop tokens
    AI-->>Tpl: token
    Tpl-->>Ctrl: emit(TokenChunk + kind 标签)
    Ctrl->>Fmt: format(StreamingContext{kind})
    Fmt-->>Ctrl: 业务协议 payload
  end
  Tpl-->>Node: Map outputKey -> full text
```

---

## 4. Function / SPI：把业务选型踢出骨架

### 4.1 设计原则（对应需求 1–4、11）

- 提示词、模型、本地 Tools、MCP：一律通过函数接口 / Resolver 形参获取
- Backend 只提供标准接口；业务用 Nacos、K8s Config、Langfuse 等自行实现
- 模型为 **请求级挂载**：每次请求再解析当前节点需要的模型信息
- Tools / MCP 支持热刷新或每次请求加载（由业务 Resolver 决定缓存策略）
- 模板内部优先使用原生 Spring AI SDK

### 4.2 请求上下文

```java
public record LlmRequestContext(
    String agentCode,           // 智能体产品/入口编码，整次请求不变（见下）
    String graphId,
    String nodeId,
    String runId,
    OverAllState state,
    ResourceBinding binding
) {}
```

#### `agentCode`：为什么要有、怎么用（定案）

**为什么要加进上下文（动机，先读这个）**

业务很常见的做法是：先用 **`agentCode` 圈定「这个智能体」能用的资源大盘**（提示词、MCP、Skill、模型配置等），再在节点上用勾选的 key 细到具体条目。

- 这一层**还没细到节点**，只是「客服助手」和「订单助手」各自能看见的资源范围不同  
- **怎么按 `agentCode` 过滤，完全是业务开发的事**（查自家注册中心、DB、配置……）  
- **ace-graph-dsl 产品只负责一件事**：在请求上下文里**提供可设置、可获取的 `agentCode`**，并保证从入口到每个 Resolver 调用都能读到**同一个值**——不替业务写过滤逻辑，也不规定过滤规则长什么样

没有这个字段，业务只能自己往 `OverAllState` 塞私货或靠猜 `graphId`/`nodeId`，框架与业务约定不清晰，也容易在扇出、多节点时丢。

**一句话结论**：`LlmRequestContext` **必须带 `agentCode`**；在 **Controller 入口写死（或按路径解析一次）**，写入本次执行的 state 保留键，**整次请求生命周期不变**；业务在 Prompt/MCP/Skill 等 Resolver 里用 `ctx.agentCode()` 做**资源范围初筛**（可选，但是框架保证你拿得到）。

**它不是什么**（避免和现有字段混）：

| 字段 | 管什么 | 例子 |
|---|---|---|
| `agentCode` | **哪个智能体产品/业务入口**；业务可据此做资源大盘初筛 | `cs-assistant`、`order-helper` |
| `graphId` | 跑的是哪张编排图 | `graph-refund-v3` |
| `nodeId` | 图里当前是哪个节点（细粒度勾选在 Binding 上） | `agent_translate` |
| `runId` | 这一次执行的追踪号 | UUID |

关系可以记成：

```text
agentCode  →  这个智能体能碰哪些资源（业务初筛，可选）
节点 Binding → 本节点实际勾了哪些 key（框架按勾选加载）
```

**产品边界（谁做什么）**：

| 谁 | 做什么 | 不做什么 |
|---|---|---|
| **ace-graph-dsl** | 入口可写入；state 保留键贯穿；`LlmRequestContext.agentCode()` 可读；关键日志建议带上该字段 | **不**内置「按 agentCode 过滤 prompt/mcp/skill」的实现 |
| **业务开发** | 决定要不要按 `agentCode` 过滤、过滤规则、数据从哪来 | 不必改框架；在自己的 Resolver / Catalog 实现里读 `ctx.agentCode()` 即可 |

业务 Resolver 示意（过滤怎么写由业务定，这里只说明「能读到」）：

```java
@Bean
PromptContentResolver prompts(MyPromptClient client) {
    return (ctx, keys) -> {
        // 可选：先按智能体产品缩小可见范围，再按节点勾选的 keys 取正文
        return client.load(ctx.agentCode(), keys);
    };
}
```

**谁在入口写死**：

业务自己的执行入口（或框架执行 Controller 由业务包一层）在进图之前定好，例如：

```java
@PostMapping("/agents/cs-assistant/stream")
public SseEmitter streamCs(@RequestBody ExecutionRequest req) {
    // 本入口写死：智能体 + 绑哪张图（完整选图见 §4.2.1）
    String agentCode = "cs-assistant";
    String graphId = "graph-refund-v3";
    log.info("智能体请求入口: agentCode={}, graphId={}", agentCode, graphId);
    // 与 runId 一样写入 state 保留键，后面所有节点 / Resolver 共用
    inputs.put(LlmRequestContext.ACE_AGENT_CODE_KEY, agentCode);
    inputs.put(ModelOverrideSpec.ACE_RUN_ID_KEY, runId);
    // ... runtime.get(graphId) / executionFacade.stream(graphId, inputs, runId)
}
```

保留键常量（与 `ace.graph.dsl.runId` 同前缀，避免泄漏到最终业务结果）：

```java
/** state 保留键：本次请求的智能体入口编码，整次 run 不变 */
public static final String ACE_AGENT_CODE_KEY = "ace.graph.dsl.agentCode";
```

**怎么贯穿生命周期**：

```text
Controller 入口写死 agentCode
    → 写入初始 OverAllState（保留键）
    → 图内每个 GenericAgentNode 从 state 读出
    → 填进 LlmRequestContext.agentCode
    → 设计期 Catalog / 运行期 Resolver 都能读到（用途不同，见 §4.3）
    → 同一 run 内扇出/多节点：值不变
```

节点组装上下文示意：

```java
String agentCode = StateValues.getString(state, LlmRequestContext.ACE_AGENT_CODE_KEY, "");
if (agentCode.isBlank()) {
    log.error("节点 {} 缺少 agentCode（state 保留键 {} 为空）。"
            + "请在执行入口写入该键，禁止在节点内猜默认值",
            nodeId, LlmRequestContext.ACE_AGENT_CODE_KEY);
}
LlmRequestContext ctx = new LlmRequestContext(
        agentCode, graphId, nodeId, runId, state, ResourceBindings.fromSpec(spec));
```

**明确不做什么**：

- 框架**不实现**按 `agentCode` 的资源过滤（那是业务的工作）  
- 不在节点里根据 `nodeId` / `graphId` **猜** `agentCode`  
- 框架**不提供**全局默认 `agentCode`  
- Catalog 的 `agentDefId`（注册式节点定义 id）**不等于** `agentCode`；按产品过滤资源列表时用查询参数 / `ctx` 上的 **`agentCode`**（§7.2）

**怎么验收**：

1. 入口写入后，同一 run 内每个 Agent 节点 / Resolver 读到的 `agentCode` 相同  
2. 设计期 Catalog 能按 `agentCode` 返回列表（无节点 keys）；运行期 Resolver 能同时拿到 `ctx.agentCode()` 与已勾选 keys  
3. 故意不写保留键时，节点侧出现上述 error 日志（不静默当空字符串用完）

### 4.2.1 图定义好了，Controller 怎么调到正确的图？（定案）

**一句话结论**：跑哪张图靠 **`graphId`**；框架现网执行 API 已在路径上带它：`POST /execution/{graphId}/stream`。业务要么直接打这个 URL，要么自写 Controller 里**写死 / 映射出** `graphId`，再调 `GraphRuntime.get(graphId)`。

**对象**：图已在设计器保存并发布（或草稿试跑）；业务 HTTP 入口要把一次用户请求接到那张图。

**怎么做（两种常见写法）**：

| 方式 | 谁提供 graphId | 适用 |
|---|---|---|
| A. 直调框架执行端点 | 调用方 URL 路径：`/execution/{graphId}/stream` | 调试、简单宿主、一个入口对应多图（前端传不同 graphId） |
| B. 业务自己的产品入口 | **业务代码写死或配置映射**（如 `cs-assistant` → `graph-cs-v3`），再调 runtime / 转发到 A | 线上产品口：用户只认「客服助手」，不认图 id |

```text
设计器保存图 graphId=graph-cs-v3
        │
        ▼
发布 / 加载进 GraphRuntime（按 graphId 索引 CompiledGraph）
        │
        ▼
HTTP 请求携带或映射出 graphId
        │
        ▼
runtime.get(graphId).stream(inputs, config)
```

**关键样例 A：直调现网框架端点（图 id 在路径）**

```java
// 现网：GraphExecutionController（ace.graph.dsl.web.execution.enabled=true）
// POST /execution/{graphId}/stream
@PostMapping(value = "/{graphId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@PathVariable String graphId,
                         @RequestBody(required = false) ExecutionRequest req) {
    String threadId = resolveThreadId(req);
    CompiledGraph graph = runtime.get(graphId);   // 按路径上的 graphId 取已编译图
    log.info("执行流式图: graphId={}, threadId={}", graphId, threadId);
    return toSse(graph.stream(inputs(req, threadId), buildConfig(threadId)),
            graphId, threadId, resolveFormatter());
}
```

前端 / 调试台：

```http
POST /execution/graph-cs-v3/stream
Content-Type: application/json

{
  "inputs": {
    "ace.graph.dsl.agentCode": "cs-assistant",
    "ace.graph.dsl.runId": "…",
    "user_text": "帮我查订单"
  }
}
```

**关键样例 B：业务产品入口写死 graphId（推荐线上）**

```java
@RestController
@RequestMapping("/agents/cs-assistant")
public class CsAssistantController {

    /** 本产品入口固定绑定的编排图（发布后的 graphId） */
    private static final String GRAPH_ID = "graph-cs-v3";
    private static final String AGENT_CODE = "cs-assistant";

    private final GraphRuntime runtime;
    // … 构造注入

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest req) {
        String runId = UUID.randomUUID().toString();
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (req.inputs() != null) {
            inputs.putAll(req.inputs());
        }
        inputs.put(LlmRequestContext.ACE_AGENT_CODE_KEY, AGENT_CODE);
        inputs.put(ModelOverrideSpec.ACE_RUN_ID_KEY, runId);
        // forceSkills 等业务字段按需写入……

        log.info("智能体入口: agentCode={}, graphId={}, runId={}", AGENT_CODE, GRAPH_ID, runId);
        CompiledGraph graph = runtime.get(GRAPH_ID);
        if (graph == null) {
            log.error("图未加载或不存在: graphId={}（请确认已发布且 GraphRuntime 已索引）", GRAPH_ID);
            throw new IllegalStateException("图不存在: " + GRAPH_ID);
        }
        // 也可内部转发 HTTP 到 /execution/{GRAPH_ID}/stream，效果等价
        return toSse(graph.stream(inputs, buildConfig(runId)), GRAPH_ID, runId, resolveFormatter());
    }
}
```

**agentCode 与 graphId 不是一回事**：

| 字段 | 回答的问题 | 例子 |
|---|---|---|
| `agentCode` | 哪个智能体产品入口（资源大盘、日志归类） | `cs-assistant` |
| `graphId` | 这次跑哪张编排图 | `graph-cs-v3` |

一个 `agentCode` 可绑一张图，也可按版本/场景映射多张图（业务自己做映射表）；框架**不会**根据 `agentCode` 自动猜 `graphId`。

**明确不做什么**：

- 框架**不**在执行时用「最新一张图」「唯一一张图」之类默认值代替缺失的 `graphId`  
- 框架**不**规定业务必须用路径参数还是常量写死——两种都行，但**必须有明确来源**  
- 不把 `nodeId` 当成选图依据  

**怎么验收**：

1. 发布 `graph-cs-v3` 后，`POST /execution/graph-cs-v3/stream` 能跑通；换一个不存在的 id → 明确失败（图未找到）  
2. 业务入口写死 `GRAPH_ID` 时，日志同时打出 `agentCode` + `graphId`，且实际执行的是该图  
3. 故意漏传 / 映射错 graphId → 不会静默跑到另一张图  

### 4.3 Resolver 接口（运行期：此时 key 已经有了）

先分清两个时刻，避免把「按 agentCode 圈大盘」和「按节点勾选的 key 取正文」混在一个接口里：

| 时刻 | 谁在用 | 有没有节点勾选的 key | 走哪类接口 |
|---|---|---|---|
| **设计期（UI 勾选前/勾选时）** | ace-graph-dsl-ui 属性面板要展示「可选列表」 | **还没有**（用户正在选，或尚未保存） | **Catalog 列表**（§7.2）：入参主要是 `agentCode`（+ 可选 graphId 等），**不传** `promptKeys` / `mcpKeys` … |
| **运行期（图已保存、节点在执行）** | Template 按 Binding 加载 | **已经有了**（落库在节点 `ResourceBinding` 里） | **本节 Resolver**：`resolve(ctx, keys)`，**既有** `ctx.agentCode()`，**也有** keys |

所以你的判断对了一半：

- **「初筛 / 列出这个智能体能用的资源大盘」** → 确实**不能、也不该**往函数里塞节点 key；那时 key 还不存在。应走 Catalog（或业务自己的 list），只凭 `agentCode` 等圈范围。  
- **「运行期按勾选 key 取内容」** → key **可以、也必须**入参；它们来自 UI 已保存的配置，不是现场猜的。业务若还想再校验「这个 key 是否属于该 agentCode」，可以在 Resolver 里同时读 `ctx.agentCode()` + keys，那是**二次校验**，不是初筛。

```text
设计期：agentCode ──► Catalog.list ──► UI 勾选 ──► 保存 promptKeys/mcpKeys/…
运行期：agentCode + 已保存的 keys ──► Resolver.resolve(ctx, keys) ──► 模型调用
```

```java
/** 按有序 keys 合并提示词（顺序有意义，例如两套 prompt 拼接） */
@FunctionalInterface
public interface PromptContentResolver {
    String resolve(LlmRequestContext ctx, List<String> promptKeys);
}

/** 请求级模型挂载 */
@FunctionalInterface
public interface ModelMountResolver {
    ModelEndpoint resolve(LlmRequestContext ctx, String modelConfigKey);
}

public record ModelEndpoint(
    String baseUrl,
    String apiKey,
    String modelId,
    Map<String, Object> extras
) {}

@FunctionalInterface
public interface LocalToolResolver {
    List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> toolKeys);
}

@FunctionalInterface
public interface McpToolResolver {
    List<NamedToolCallback> resolve(LlmRequestContext ctx, List<String> mcpKeys);
}

/** Skill：仅目录元数据（运行期；keys = 本节点白名单） */
@FunctionalInterface
public interface SkillCatalogResolver {
    List<SkillDescriptor> resolve(LlmRequestContext ctx, List<String> skillKeys);
}
```

> 命名提醒：`SkillCatalogResolver` 名字带 Catalog，但职责是**运行期按节点 skillKeys 取 L1 元数据**，不是 UI 的资源列表。UI 列 Skill 候选走 §7.2 的 `AgentResourceCatalog`（`ResourceType.SKILL`）。

业务运行期示意（keys 来自节点配置；agentCode 用于可选的范围校验）：

```java
@Bean
PromptContentResolver prompts(MyPromptClient client) {
    return (ctx, keys) -> {
        // keys：UI 已勾选并落库；agentCode：入口写入，用于确认这些 key 仍在本智能体范围内
        return client.load(ctx.agentCode(), keys);
    };
}
```

```java
public record SkillDescriptor(
    String key,
    String name,
    String shortDescription,
    String triggerHint
) {}

/** 真正用到 skill 时再加载 SKILL.md 等正文 */
@FunctionalInterface
public interface SkillContentLoader {
    Optional<String> loadBody(LlmRequestContext ctx, String skillKey);
}
```

业务侧示例（Nacos，示意）：

```java
@Bean
PromptContentResolver nacosPrompts(ConfigService cs) {
    return (ctx, keys) -> keys.stream()
        .map(k -> cs.getConfig(k, "DEFAULT_GROUP", 3000))
        .filter(Objects::nonNull)
        .collect(Collectors.joining("\n\n"));
}
```

未实现时：默认空串 / 空列表 / stub 模型，保证开箱可跑。

### 4.4 与现有 SPI 的关系

现有 `PromptRepository`、`SkillRegistry`、`McpServerRegistry`、`McpToolProvider`、`ChatClientFactory`、`SecretResolver` **接口保留**（不改方法签名语义），Template 内可适配为 Function；新代码优先走 Resolver。

但**模块归属与工具类型有调整**（§12.1 定案）：

| 组件 | 变化 |
|---|---|
| `McpServerRegistry` / `McpToolProvider` / `ChatClientFactory` / `AgentChatClient` | 从 core **迁入 `ace-graph-dsl-ai`**（涉及模型层） |
| `AgentTool` | **删除**。原 `name()` / `call(Map)` 语义由 spring-ai `ToolCallback` + `ToolDefinition` 承担；`McpToolProvider` 返回类型随之改为 `ToolCallback` |
| `PromptRepository` / `SkillRegistry` / `SecretResolver` | 留在 core（返回 String / 纯数据） |

请求级 `ModelOverrideSpec` 与 binding.modelKey **并存**：Override 优先于 binding。

### 4.4.1 模型来源优先级（A5 定案）

#### 优先级链

```text
请求级 ModelOverrideSpec  >  modelConfigKey（需 enableModel=true）  >  节点内联 modelBaseUrl/apiKey/modelId
```

`ModelOverrideSpec` 内部已有自己的两级（现网 `effectiveFor`）：**nodeId 精确覆盖 > global 覆盖**，本方案不改。

**这个顺序的意图**：让注册中心成为模型配置的**权威来源**——改一处 key 指向即可全局切换模型，无需逐个节点改图。节点内联字段退化为**兜底与本地调试**用途（没接注册中心、或临时试某个私有端点时仍可用）。

#### `enableModel` 是什么

它是 `ResourceBinding` 的字段（§7.1），对应 UI 上「`[x] Model`」这个类型总开关，与 `modelConfigKey` 成对出现——与 Prompt / Skill / MCP 是同一套勾选机制：

| 值 | 含义 |
|---|---|
| `true` | 启用「按 key 取模型配置」这一路（第二优先级） |
| `false` | **跳过该路**，不调用 `ModelMountResolver`，连带省掉一次注册中心远程调用；此时静态来源只剩内联字段 |

**它不是「禁用节点」**。`enableModel=false` 时节点照常执行。

#### 静态层整路择一：不完整就报错（修订定案）

先前写过「三路对 baseUrl/apiKey/modelId **逐字段**取第一个非空」。这会让人很懵，例如：

> 没传请求级 Override，勾了 `modelConfigKey`，但注册中心返回缺 `modelId`；日志里却发现用了节点上残留的内联 `modelId`。

开发和用户都会问：我明明选的是注册中心这一路，为什么偷偷拼了内联？

**改成下面这套（更符合直觉）：**

| 步骤 | 规则 |
|---|---|
| 1. 选**静态底座**（两选一，整路拿走） | 若 `enableModel=true` 且 `modelConfigKey` 非空 → **只用**注册中心返回的那一套；否则 → **只用**节点内联那一套 |
| 2. 底座必须齐全 | 选中的那一路若缺 `baseUrl` 或 `modelId`（api-key 按业务要求）→ **直接报错**，**禁止**再去另一路捡字段补齐 |
| 3. 请求级 Override（若有） | 才允许**逐字段补丁**盖在底座上（例如只改 `modelId` 做 A/B） |

大白话：

```text
先认准「我这次听谁的」——注册中心 或 内联，二选一，不拼盘。
听谁的，谁就要给齐；给不齐就报错，别偷用另一路的字段。
只有「请求里临时覆盖」可以改其中一两个字段。
```

**`modelConfigKey` 解析失败 / 不完整：**

| 情况 | 行为 |
|---|---|
| Resolver **抛异常**（key 不存在、注册中心挂了、鉴权失败） | **报错**，不回落内联 |
| Resolver 返回了，但缺 `baseUrl` / `modelId` 等必要项 | **报错**，不回落内联 |
| 未勾选 Model / 未填 key | 整路改用内联；内联也不齐 → 报错 |

**为什么 Override 仍允许逐字段？**

请求级覆盖最常见的是「只换模型名做对比」，底座的地址和密钥还用静态配置。若 Override 也强制「整包替换」，只传 `modelId` 会把 `baseUrl`/`apiKey` 弄丢。这和「注册中心与内联拼盘」不是一类需求：前者是**用户显式传入的临时补丁**，后者是**两套静态配置偷偷混用**。

#### 分层职责

| 组件 | 职责 |
|---|---|
| `ModelMountResolver`（业务实现） | **只负责 key 这一路**：按 `modelConfigKey` 从注册中心取配置 |
| `ModelEndpointResolver`（框架内部） | 先整路选定底座，校验齐全，再套 Override 补丁 |

```java
/**
 * 模型端点解析（框架内部）。
 *
 * <p>静态层：modelConfigKey 与内联二选一（整路），不齐则报错，禁止拼盘。
 * 请求级 Override：仅在此底座上逐字段补丁。</p>
 */
public final class ModelEndpointResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelEndpointResolver.class);

    private final ModelMountResolver mountResolver;
    private final SecretResolver secretResolver;

    private enum BaseSource { CONFIG_KEY, INLINE }

    public ModelEndpoint resolve(LlmRequestContext ctx, InlineModel inline, ModelOverride override) {
        ResourceBinding b = ctx.binding();

        // 1) 整路选定静态底座
        ModelEndpoint base;
        BaseSource baseSource;
        if (b.enableModel() && isNotBlank(b.modelConfigKey())) {
            base = requireComplete(ctx, resolveByKeyOrFail(ctx, b.modelConfigKey()),
                    "modelConfigKey=" + b.modelConfigKey());
            baseSource = BaseSource.CONFIG_KEY;
            // 内联即使有值也不参与补缺；并存时编译期已 warn
        } else {
            base = requireComplete(ctx, fromInline(inline), "节点内联模型字段");
            baseSource = BaseSource.INLINE;
        }

        // 2) 请求级 Override：仅逐字段盖在底座上（可只改 modelId）
        String baseUrl = firstNonBlank(override == null ? null : override.modelBaseUrl(), base.baseUrl());
        String modelId = firstNonBlank(override == null ? null : override.modelId(), base.modelId());
        String apiKey;
        String apiKeySource;
        if (override != null && isNotBlank(override.modelApiKey())) {
            apiKey = override.modelApiKey();          // 请求传入，视为明文
            apiKeySource = "OVERRIDE";
        } else if (baseSource == BaseSource.INLINE && inline != null && inline.apiKeyMasked()) {
            apiKey = secretResolver.resolve(ctx.graphId(), ctx.nodeId(), base.apiKey());
            apiKeySource = "INLINE(masked→resolved)";
        } else {
            apiKey = base.apiKey();
            apiKeySource = baseSource.name();
        }

        log.info("节点 {} 模型解析完成: 静态底座={}, modelId={}, baseUrl={}, apiKey来源={}, 是否有Override补丁={}",
                ctx.nodeId(), baseSource, modelId, baseUrl, apiKeySource, override != null && !override.isEmpty());
        return new ModelEndpoint(baseUrl, apiKey, modelId, base.extras());
    }

    /** 选中的那一路必须给齐必要字段，禁止再向下一路捡漏 */
    private static ModelEndpoint requireComplete(LlmRequestContext ctx, ModelEndpoint ep, String which) {
        if (ep != null && isNotBlank(ep.baseUrl()) && isNotBlank(ep.modelId())) {
            return ep;
        }
        log.error("节点 {} 的 {} 模型配置不完整: baseUrl={}, modelId={}；不会用其它来源字段拼盘",
                ctx.nodeId(), which,
                ep == null ? null : ep.baseUrl(),
                ep == null ? null : ep.modelId());
        throw new IllegalStateException(String.format(
                "节点 %s 的 %s 不完整（需要 baseUrl + modelId）。"
                        + "请补全该来源配置；若改用另一来源：勾选/取消 Model，或填写内联字段。"
                        + "不会自动用另一路字段补齐。",
                ctx.nodeId(), which));
    }

    private ModelEndpoint resolveByKeyOrFail(LlmRequestContext ctx, String modelConfigKey) {
        try {
            return mountResolver.resolve(ctx, modelConfigKey);
        } catch (RuntimeException e) {
            log.error("节点 {} 按 modelConfigKey={} 解析失败，不回落内联",
                    ctx.nodeId(), modelConfigKey, e);
            throw new IllegalStateException(String.format(
                    "节点 %s 的 modelConfigKey=%s 解析失败：%s。"
                            + "请检查 key 与注册中心；临时改用内联请取消勾选 Model",
                    ctx.nodeId(), modelConfigKey, e.getMessage()), e);
        }
    }
}
```

`InlineModel` 为内联三字段的载体，由节点从 `GenericAgentSpec` 提取，避免 Template 依赖 Spec 类型：

```java
/** 节点内联模型字段（apiKeyMasked 决定是否需要 SecretResolver 还原） */
public record InlineModel(String baseUrl, String apiKey, boolean apiKeyMasked, String modelId) {}
```

#### 该优先级的已知副作用

勾选 Model 并填了 key 后，节点上的内联字段**整路不用**（也不会拿来补缺）。编译期仍要 warn，避免界面上两套配置都在、运行却只听 key：

```java
log.warn("节点 {} 同时存在 modelConfigKey={} 与内联模型字段：静态底座只用 key，内联整路忽略；"
        + "内联不会用来补齐 key 缺的字段。若要用内联，请取消勾选 Model",
        nodeId, modelConfigKey);
```

### 4.4.2 Prompt 变量渲染（A4 定案）

#### 先澄清现网实情

`GenericAgentSpec.prompt` 的 javadoc 写着「支持 `{{state.key}}` 占位」，但**该渲染从未实现**：

- `GenericAgentNode#toAction` 把 `spec.inputKeySet()` 对应的 state 值收集成 `variables` Map；
- 连同未渲染的 `promptTemplate` 一起交给 `AgentChatClient.call(promptTemplate, variables, spec)`；
- core 内**没有任何字符串替换**，`StubChatClientFactory` 只是把两者原样回显。

即渲染责任被隐式推给了各 `AgentChatClient` 实现方，且无人实现。因此本节不是「补齐规则」，而是**首次定义**，同时把渲染收归框架层。

#### 五条定案

**① 渲染在框架层完成，交给 Spring AI 的一律是已渲染纯文本**

不使用 Spring AI 的 `PromptTemplate` / `.param()`。原因是 Spring AI 模板用**单花括号** `{key}`，而 prompt 里极常见 JSON 示例（`{"role":"user"}`）与 JSON Schema 片段，交给它会直接解析报错。我们用双花括号 `{{}}` 自渲染，从根上规避。

**② 语法只有一个命名空间，`state.` 前缀可选**

```text
{{state.order_no}}   等价于   {{order_no}}
```

保留 `state.` 是为了兼容已写下的 javadoc 约定；允许省略是因为现网 `variables` Map 的键本就是裸 key。不引入其他命名空间（`env.` / `ctx.` 等），需要时再加。

**③ 单遍扫描，替换值不再参与渲染（安全红线）**

若递归渲染，用户输入 `{{state.internal_api_key}}` 存进 state 后，下一轮就会被展开——**模板注入**。因此严格单遍：一次 `Matcher` 遍历，替换进去的内容不再被扫描。

实现上必须用 `Matcher.quoteReplacement` 包裹替换值，否则值里的 `$` 和 `\` 会被 `appendReplacement` 当作组引用，轻则乱码重则抛异常。

**④ system 与 user 共用同一份变量快照**

「谁先渲染」的实质不是先后，而是**是否看到同一份 state**。定案：进入 Template 时取一次快照，system / user 都用它。二者互不引用，故顺序无语义差别。

多个 `promptKeys` 采用**先按序合并、后统一渲染一次**（而非各自渲染再拼接）。二者结果等价，前者只扫一遍，更省。

**⑤ skill L1 目录与工具目录在渲染之后拼接**

这两段是框架生成的，不该被当成用户模板。若先拼后渲染，skill 描述里恰好出现 `{{...}}` 就会被误替换。

#### 「已渲染纯文本」会不会没法动态取值？（常见疑问）

**不会有问题——但要把「动态」说清楚指哪一层。**

| 你说的「动态」 | 本方案支不支持 | 怎么做的 |
|---|---|---|
| **每次跑图 / 每个节点执行时**，订单号、上游输出等来自当前 `OverAllState` | **支持，这就是主路径** | 进 Template 时对 state **拍一次快照**，把 `{{order_no}}` 等换成**这一次**的真实值，再交给 Spring AI |
| **同一节点里**，模型调了 Tool，Tool 结果要进下一轮对话 | **支持，但不靠再渲染 `{{}}`** | Spring AI tool-calling 把 Tool 返回值做成 **tool 消息** 追加进 messages；system/user 首轮文本保持不变（与 Skill L2「Tool 结果 append」一致） |
| **同一节点里**，Tool 跑完后希望 **整段 system 按新 state 再渲染一遍** | **首期不做** | 需要的话应拆成下一节点，或以后另开「多轮前可重渲染」开关；默认不重渲染，避免和单遍快照、防注入规则打架 |
| 把未替换的模板交给 Spring AI，让它用 `{key}` / `.param()` 再渲染 | **明确不做** | Spring AI 单花括号会和 prompt 里的 JSON 示例冲突；动态值已由框架在调用前注入 |

所以：

1. **「交给 Spring AI 的是纯文本」** = 不再让 Spring AI 做第二遍模板引擎；**不是**说变量永远写死在配置文件里。  
2. **动态值从哪来**：节点执行那一刻的 state（上游节点刚写入的、请求入口塞进的）。每个节点、每次 run 都可以不同。  
3. **Tool 带来的新信息**：走消息列表追加，不走「再扫一遍 `{{...}}`」。若业务把 Tool 结果又写回了 state、且还想再进 prompt——用**下一个 Agent 节点**读 state 再渲染，而不是在同一节点内偷偷重渲染。

```text
请求进来 → state 里已有/上游写入动态值
    → 本节点：快照 → {{}} 换成纯文本 → ChatClient
    →（如有 Tool）Tool 结果进 messages，不重渲 system
    → 写回 outputKey → 下一节点再快照、再渲染
```

#### 完整装配顺序

```text
1. 取变量快照（依据 inputKeySet，一次读完）
2. 按 promptKeys 顺序合并成 systemTemplate（分隔符 "\n\n"）
3. 单遍渲染 systemTemplate  ← 用户模板到此为止
4. 追加 skill L1 目录 + 工具目录（不渲染）
5. 单遍渲染 userMessage（同一快照）
```

#### 变量取值与类型转换

| state 值类型 | 转换方式 |
|---|---|
| `String` | 原样 |
| 数字 / 布尔 | `toString()` |
| `Map` / `List` / POJO | **JSON 序列化** |
| `null` 或 key 不存在 | 空串 + warn |

`Map`/`List` 必须走 JSON：Java 默认 `toString()` 产出 `{a=1, b=2}`，既非合法 JSON 也不利于模型解析。

#### 缺失变量策略

**默认空串 + warn，可配置切严格模式**（`ace.graph.dsl.prompt.strict-variables=true` 时抛异常）。

不选「保留原样」：把 `{{state.foo}}` 原封不动送进模型是最糟的——模型会把它当字面量学走，且难排查。不默认严格：图执行早期某些可选输入确实可能为空，直接炸太脆弱。

#### 编译期校验（顺带解决 `inputKeys` 手填痛点）

编译期扫描模板中全部占位符，与 `spec.inputKeySet()` 比对：

```java
// 模板里用了但 inputKeys 未声明 → 可达性校验覆盖不到，运行期必空
log.warn("节点 {} 的 prompt 引用了未声明的变量 {}，运行期将渲染为空串；"
        + "请将其补入 inputKeys 以纳入边可达性校验", nodeId, undeclared);
// inputKeys 声明了但模板没用 → 多余的可达性约束，可能挡住本可通过的图
log.warn("节点 {} 的 inputKeys 声明了 {} 但 prompt 未引用，建议移除", nodeId, unused);
```

同一份扫描结果可回吐给 UI 做 `inputKeys` 自动补全，免去手填（P1）。

#### 长度护栏

上游节点输出动辄数十 KB，直接拼进 prompt 会击穿上下文窗口且费用失控。约定**单变量截断上限**与**渲染后总长上限**（默认值走配置），超限截断并 warn，日志给出 `nodeId` 与变量名。

> **截断只作用于「喂给模型的字符串」**，**不会**改写 state 里的上游原文。  
> 节点间大结果、文件 URL 怎么放 state：见 **§8.3**（默认完整透传；文件只传 URL；按需另写 summary key）。

```java
/**
 * Prompt 变量渲染器（框架内部）。
 *
 * <p>严格单遍替换：替换入的内容不再参与后续扫描，避免 state 值携带
 * {@code {{...}}} 造成模板注入。</p>
 */
public final class PromptRenderer {

    private static final Logger log = LoggerFactory.getLogger(PromptRenderer.class);

    /** {{ state.key }} / {{ key }}，允许内部空白 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*(?:state\\.)?([A-Za-z0-9_.\\-]+)\\s*}}");

    private final ObjectMapper objectMapper;
    private final PromptRenderProperties props;   // strictVariables / maxValueLength / maxTotalLength

    /**
     * 单遍渲染。
     *
     * @param template  已合并的模板（多 promptKeys 按序拼接后传入）
     * @param snapshot  变量快照，system/user 共用同一份
     */
    public String render(String template, Map<String, Object> snapshot, String nodeId) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (m.find()) {
            String name = m.group(1);
            String value = stringify(snapshot, name, nodeId);
            // quoteReplacement 必须加：值中的 $ / \ 否则会被当作组引用
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return truncateTotal(sb.toString(), nodeId);
    }

    /** 类型归一：集合类走 JSON，避免 Java toString 产出非法 JSON */
    private String stringify(Map<String, Object> snapshot, String name, String nodeId) {
        Object v = snapshot.get(name);
        if (v == null) {
            if (props.strictVariables()) {
                throw new IllegalStateException(String.format(
                        "节点 %s 的 prompt 变量 %s 无值（严格模式）。请检查上游是否写入该 state key，"
                                + "或关闭 ace.graph.dsl.prompt.strict-variables", nodeId, name));
            }
            log.warn("节点 {} 的 prompt 变量 {} 无值，渲染为空串", nodeId, name);
            return "";
        }
        String text = (v instanceof String s) ? s
                : (v instanceof Number || v instanceof Boolean) ? String.valueOf(v)
                : toJson(v, nodeId, name);
        return truncateValue(text, nodeId, name);
    }
}
```

### 4.5 `LlmResolvers` / `LlmCallRequest` / `ChatModelFactory` 完整定义（A6 定案）

#### 4.5.1 先纠正一处概念错位

早期骨架把 `modelResolver()`、`localTools()`、`skillCatalog()` 等**全部当作请求级字段**放进 `LlmCallRequest`，导致该 record 需要 18 个构造参数。

但这些 Resolver 是**单例依赖**（Spring Bean，请求间复用），不是请求数据。二者混在一个 record 里既臃肿又误导——每次调用都要重新传一遍不会变的东西。

**定案：拆成两个对象。**

| 对象 | 生命周期 | 内容 |
|---|---|---|
| `LlmResolvers` | **单例**，构造 Template 时注入一次 | 全部 Resolver / Factory |
| `LlmCallRequest` | **请求级**，每次调用构造 | 本次调用真正变化的参数（8 个字段） |

#### 4.5.2 `LlmResolvers`（单例依赖集合）

```java
/**
 * Resolver 依赖集合（单例）。
 *
 * <p>由 Spring 装配一次后随 {@link StreamingLlmTemplate} 复用；业务未提供某项时
 * 由自动配置回落到默认实现（空串 / 空列表 / stub），保证开箱可跑。</p>
 */
public record LlmResolvers(
    PromptContentResolver prompts,
    PromptRenderer promptRenderer,          // 框架内部：单遍变量渲染（§4.4.2）
    ModelEndpointResolver modelEndpoints,   // 框架内部：三路合并（§4.4.1），内部持有 ModelMountResolver
    ChatModelFactory chatModels,
    LocalToolResolver localTools,
    McpToolResolver mcpTools,
    SkillCatalogResolver skillCatalog,
    SkillContentLoader skillContent,
    SkillResourceLoader skillResources,
    MediaRefResolver media,
    StreamResponseKindResolver kinds
) {
    /** 全部依赖非空校验：缺失项应由自动配置补默认实现，不允许 null 进入运行期 */
    public LlmResolvers {
        Objects.requireNonNull(prompts, "PromptContentResolver 不能为空");
        Objects.requireNonNull(promptRenderer, "PromptRenderer 不能为空");
        Objects.requireNonNull(modelEndpoints, "ModelEndpointResolver 不能为空");
        Objects.requireNonNull(chatModels, "ChatModelFactory 不能为空");
        Objects.requireNonNull(localTools, "LocalToolResolver 不能为空");
        Objects.requireNonNull(mcpTools, "McpToolResolver 不能为空");
        Objects.requireNonNull(skillCatalog, "SkillCatalogResolver 不能为空");
        Objects.requireNonNull(skillContent, "SkillContentLoader 不能为空");
        Objects.requireNonNull(skillResources, "SkillResourceLoader 不能为空");
        Objects.requireNonNull(media, "MediaRefResolver 不能为空");
        Objects.requireNonNull(kinds, "StreamResponseKindResolver 不能为空");
    }
}
```

#### 4.5.3 `LlmCallRequest`（请求级参数）

```java
/**
 * 一次 LLM 调用的请求级参数。
 *
 * <p>{@code binding} 不单独存放，统一从 {@link LlmRequestContext#binding()} 取，
 * 避免同一份配置在两处出现导致不一致。</p>
 */
public record LlmCallRequest(
    LlmRequestContext context,      // 含 agentCode/graphId/nodeId/runId/state/binding
    String userMessage,             // 本次用户输入（已由节点从 state 取好）
    String outputKey,               // 结果写回 state 的 key
    String streamResponseKind,      // 节点配置的 kind KEY，可空（按 §9.7.4 回落）
    String mediaInputKey,           // 多模态引用所在的 state key，可空表示纯文本
    boolean streaming,              // 是否流式
    ToolConflictPolicy conflictPolicy,
    GraphStreamBridge bridge,       // 非流式时传 GraphStreamBridge.NOOP
    InlineModel inlineModel,        // 节点内联模型字段，可空（§4.4.1 兜底来源）
    ModelOverride modelOverride,    // 本节点生效的请求级覆盖，可空（§4.4.1 最高优先级）
    Set<String> inputKeys           // 变量快照读取范围，来自 spec.inputKeySet()（§4.4.2）
) {

    public LlmCallRequest {
        Objects.requireNonNull(context, "LlmRequestContext 不能为空");
        if (outputKey == null || outputKey.isBlank()) {
            throw new IllegalArgumentException("outputKey 不能为空");
        }
        // userMessage 允许为空串（纯 system + 多模态的场景），但不允许 null
        userMessage = userMessage == null ? "" : userMessage;
        conflictPolicy = conflictPolicy == null ? ToolConflictPolicy.FIRST_WIN : conflictPolicy;
        bridge = bridge == null ? GraphStreamBridge.NOOP : bridge;
    }

    /** 便捷访问：资源勾选配置（唯一来源为 context） */
    public ResourceBinding binding() {
        return context.binding();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 建造者：字段较多且多为可空，避免长构造器误传 */
    public static final class Builder { /* ... */ }
}
```

#### 4.5.4 `ChatModelFactory`

```java
/**
 * 由模型端点信息创建 spring-ai {@code ChatModel}（位于 ace-graph-dsl-ai，§12.1.3）。
 *
 * <p>与现网 {@code ChatClientFactory} 的区别：本接口返回**原生 ChatModel**，
 * 由 Template 自行 {@code ChatClient.builder(model)} 装配工具与提示词；
 * 而 {@code ChatClientFactory} 返回的是自定义隔离层 {@code AgentChatClient}。</p>
 */
@FunctionalInterface
public interface ChatModelFactory {

    /** 按端点创建（或复用）ChatModel */
    ChatModel create(ModelEndpoint endpoint);
}
```

**默认实现必须缓存**：`ChatModel` 内含 HTTP 客户端与连接池，每请求重建会造成连接风暴与显著延迟。

```java
/**
 * 默认实现：按 {@link ModelEndpoint} 缓存 ChatModel（OpenAI 兼容协议，含 DashScope 兼容模式）。
 *
 * <p>缓存必须有界：请求级模型覆盖可能带入动态 api-key，无界缓存会持续增长直至 OOM。
 * 超过上限时按 LRU 淘汰并打印 warn，便于发现「key 每次都变」的误用。</p>
 */
public class CachingChatModelFactory implements ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(CachingChatModelFactory.class);

    /** 缓存上限：正常用法下端点数量有限，超限即说明存在动态 key 误用 */
    private static final int MAX_CACHED_MODELS = 64;

    private final Map<ModelEndpoint, ChatModel> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ModelEndpoint, ChatModel> eldest) {
                    boolean evict = size() > MAX_CACHED_MODELS;
                    if (evict) {
                        log.warn("ChatModel 缓存超过上限 {}，触发 LRU 淘汰；"
                                + "请检查是否存在请求级动态 api-key 导致端点无限增长", MAX_CACHED_MODELS);
                    }
                    return evict;
                }
            });

    @Override
    public ChatModel create(ModelEndpoint endpoint) {
        validate(endpoint);
        return cache.computeIfAbsent(endpoint, ep -> {
            log.info("创建 ChatModel: baseUrl={}, modelId={}（api-key 已省略）",
                    ep.baseUrl(), ep.modelId());
            return buildOpenAiCompatible(ep);
        });
    }

    /** 端点必要字段校验：缺失时给出可操作错误，而非等到调用模型才失败 */
    private static void validate(ModelEndpoint ep) {
        Objects.requireNonNull(ep, "ModelEndpoint 不能为空");
        if (ep.baseUrl() == null || ep.baseUrl().isBlank()) {
            throw new IllegalArgumentException("模型 baseUrl 不能为空");
        }
        if (ep.modelId() == null || ep.modelId().isBlank()) {
            throw new IllegalArgumentException("模型 modelId 不能为空");
        }
    }
}
```

> **安全**：`ModelEndpoint` 含 api-key，作为缓存 key 仅存于内存；**日志一律不打印 api-key**（上例已省略），异常信息中亦不得回显。

#### 4.5.5 与现网 `ChatClientFactory` / `AgentChatClient` 的关系

| 组件 | 处置 |
|---|---|
| `ChatModelFactory`（新） | 新路径唯一模型入口，返回原生 `ChatModel` |
| `ChatClientFactory` / `AgentChatClient`（现网） | **保留并标 `@Deprecated`**，仅供未迁移的旧路径；随 `GenericAgentNode` 改为调用 Template 后一并下线 |
| 是否提供互相桥接 | **不提供**。`AgentChatClient` 返回 `String`（已完成的调用结果），`ChatModelFactory` 返回 `ChatModel`（可被继续装配的模型），语义不可逆，强行桥接会丢失工具与多模态能力 |

业务若已自定义 `ChatClientFactory`，迁移为 `ChatModelFactory` 通常更简单：直接返回构造好的 spring-ai `ChatModel` 即可，无需再实现 `call`/`stream` 两套方法。

#### 4.5.6 Template 签名调整

```java
public final class StreamingLlmTemplate {

    private final LlmResolvers resolvers;   // 单例注入

    public StreamingLlmTemplate(LlmResolvers resolvers) {
        this.resolvers = Objects.requireNonNull(resolvers, "LlmResolvers 不能为空");
    }

    /** 执行一次 LLM 调用并把结果写回 state */
    public Map<String, Object> execute(LlmCallRequest req) { /* 见 §10 */ }
}
```

---

## 5. 工具去重与同名 MCP（需求 5）

### 5.1 统一包装

```java
public record NamedToolCallback(
    String logicalKey,      // 内部配置 key，如 mcp:weather-svc（不下发给模型，可含冒号）
    String originalName,    // MCP / 本地原始 tool name
    String uniqueName,      // 模型可见名，必须合法：^[a-zA-Z0-9_-]{1,64}$（§5.3）
    ToolSource source,       // LOCAL | MCP | BUILTIN（枚举，非字符串）
    ToolCallback callback    // spring-ai 类型；本 record 位于 ace-graph-dsl-ai（§12.1.3）
) {}
```

#### 5.1.1 uniqueName 必须落到 `ToolDefinition.name`（D1 定案）

**关键**：Spring AI 注册给模型的工具名取自 `ToolCallback.getToolDefinition().name()`，**不是** record 里的字段。只在 `NamedToolCallback.uniqueName` 上改名，模型侧看到的仍是原始重名，`ChatClient` 会因重复工具名直接报错。

因此必须包装出一个改名后的 `ToolCallback`：

```java
/**
 * 生成对模型可见的 ToolCallback：强制令 name == uniqueName。
 *
 * <p>inputSchema 原样保留；description 仅在**存在同名冲突**时前置来源标识——
 * 名字唯一只解决路由，不解决模型在同名工具间的选择（§5.3 第二层）。</p>
 *
 * <p>具体 builder API 以实际 spring-ai 版本（当前 1.1.2）为准。</p>
 *
 * @param conflicted 该工具的 originalName 是否落在冲突组内，由 ToolDeduper 分组时产出
 */
public ToolCallback toModelCallback(boolean conflicted) {
    ToolDefinition origin = callback.getToolDefinition();
    if (uniqueName.equals(origin.name()) && !conflicted) {
        return callback;                      // 无需改名也无需消歧，避免多包一层
    }
    ToolDefinition renamed = ToolDefinition.builder()
            .name(uniqueName)
            .description(disambiguate(origin.description(), serverKey(), conflicted))
            .inputSchema(origin.inputSchema())
            .build();
    return new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return renamed;
        }

        @Override
        public String call(String toolInput) {
            return callback.call(toolInput);   // 调用委派给原始回调，语义不变
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return callback.call(toolInput, toolContext);
        }
    };
}
```

挂载时统一走该方法，并在日志中打印映射关系，便于排查模型「叫不到工具」：

```java
// conflicted：originalName 是否落在冲突组，决定是否改写 description（§5.3）
Set<String> conflicted = ToolDeduper.conflictedOriginalNames(unique);
List<ToolCallback> callbacks = unique.stream()
        .map(t -> t.toModelCallback(conflicted.contains(t.originalName())))
        .toList();
builder.defaultTools(callbacks.toArray(new ToolCallback[0]));
log.info("节点 {} 挂载工具 {} 个: {}", ctx.nodeId(), unique.size(),
        unique.stream().map(t -> t.originalName() + "->" + t.uniqueName()).toList());
```

**自检约束**：挂载前对 `callbacks` 的 `getToolDefinition().name()` 断言**两条**——全局唯一、且匹配 `^[a-zA-Z0-9_-]{1,64}$`。前者避免 `ChatClient` 抛出难以定位的底层异常，后者早于端点返回 400（§5.3）。

### 5.2 策略

1. **唯一名**：`{source}__{serverOrLocalKey}__{originalName}`  
   例：`mcp__weather__get_forecast`、`local__crm__get_order`（**分隔符为双下划线，不可用冒号**，原因见 §5.3）
2. **同 uniqueName**：建议 **先到保留 + warn**（避免热刷新抖动）；也可配置为后覆盖
3. **给模型的可见名**：**一律用 uniqueName，不设短名**（D2 定案，见 §5.3）
4. **本地 vs MCP**：冲突策略枚举 `ToolConflictPolicy`（LOCAL_FIRST / MCP_FIRST / FAIL）

不同 MCP 同名 tool 必须靠 `serverKey` 进命名空间，禁止直接用原名挂载到同一 `ChatClient`。

### 5.3 模型可见名与 description 消歧（D2 定案）

先回答关键问题：**uniqueName 能否保证模型调用到正确的工具？** 拆成两层，答案不同。

#### 第一层：技术路由——能保证，但有硬前提

链路是闭环的：`ToolDefinition.name()` → 请求体 `tools[].function.name` → 模型返回 `tool_calls[].function.name` → Spring AI `ToolCallingManager` 按同一字符串回查 callback → 执行。发出去和收回来是同一个 name，只要 `toModelCallback()` 改名后 `call()` 仍委派原实现，路由**不会错**。

**但现有格式会让请求直接失败**。OpenAI 及兼容端点对 function name 的约束是：

```text
^[a-zA-Z0-9_-]{1,64}$
```

`mcp:weather:get_forecast` 含**冒号**，违反字符集；`ace:skill:load_skill`（§6.4.1）同样违规。这类请求会被端点以 400 拒绝——不是「模型选错工具」，是**整个请求都发不出去**。故分隔符统一改为 `__`，并强制 sanitize：

```java
/** OpenAI 兼容端点的 function name 约束 */
private static final Pattern LEGAL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
private static final int MAX_NAME_LENGTH = 64;
private static final String NS_SEPARATOR = "__";

/**
 * 生成模型可见名：命名空间拼接 + 非法字符归一 + 超长哈希压缩。
 *
 * <p>超长时保留 originalName 尾部而非头部：工具语义主要落在原始名上，
 * 前缀（source/serverKey）对模型无价值，压成哈希即可，且哈希基于完整
 * uniqueName 计算，保证压缩后仍全局唯一。</p>
 */
public static String toModelName(String source, String serverKey, String originalName) {
    String joined = sanitize(source) + NS_SEPARATOR + sanitize(serverKey)
            + NS_SEPARATOR + sanitize(originalName);
    if (joined.length() <= MAX_NAME_LENGTH) {
        return joined;
    }
    String hash = sha256Hex(joined).substring(0, 8);
    int keep = MAX_NAME_LENGTH - hash.length() - NS_SEPARATOR.length();
    String tail = sanitize(originalName);
    if (tail.length() > keep) {
        tail = tail.substring(tail.length() - keep);
    }
    String compressed = tail + NS_SEPARATOR + hash;
    log.warn("工具名超长已压缩: {} -> {}（原名 {} 字符，上限 {}）",
            joined, compressed, joined.length(), MAX_NAME_LENGTH);
    return compressed;
}

/** 非法字符统一替换为下划线，避免端点 400 */
private static String sanitize(String raw) {
    return raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9_-]", "_");
}
```

挂载前的断言随之升级为**两条**：既校验全局唯一，也校验合法性。

```java
ToolNames.assertUnique(callbacks);   // 重名 fail fast
ToolNames.assertLegal(callbacks);    // 不匹配 LEGAL_NAME 则 fail fast，早于端点 400
```

#### 第二层：语义选择——改名解决不了，必须补 description

这一层才是「模型调不到正确工具」的真正来源。

设想两个 MCP 都有 `query_order`，description 都是「查询订单」。改名后成了 `mcp__crm__query_order` 与 `mcp__erp__query_order`，名字确实唯一了，**但两条 description 一模一样**。模型选工具主要依据 description，面对两个无从区分的候选只能靠猜——**名字唯一了，调用照样是错的**，且错得隐蔽（不报错，只是查了错误的系统）。

因此 §5.1.1 中「description 原样保留」这条**仅适用于无冲突场景**。定案：

| 场景 | description 处理 |
|---|---|
| 无同名冲突 | **原样保留**，不加任何前缀（避免无谓噪声与 token） |
| 存在同名冲突 | **前置来源标识**：`[来源: {serverKey}] ` + 原描述 |

```java
/** 仅冲突组内改写 description：让模型有依据在同名工具间做选择 */
private static String disambiguate(String origin, String serverKey, boolean conflicted) {
    return conflicted ? "[来源: " + serverKey + "] " + origin : origin;
}
```

判定「是否冲突」由 `ToolDeduper` 在分组时一并产出——它本就按 `originalName` 分了组，冲突组信息是现成的，无需二次扫描。

> 仅靠 serverKey 未必够：若两个 MCP 的 key 是 `svc-a` / `svc-b` 这类无语义名，模型仍难判断。故 `McpToolResolver` 的配置项应允许为每个 MCP 填一段**人类可读的来源说明**（如「CRM 客户系统」），有则优先用于消歧文案。这属配置质量问题，框架给出位置即可。

#### 为什么不需要在 system 里写工具目录

D2 原提的「固定一段自动生成的工具目录文案」**不做**。理由：

1. 工具清单是经请求体的 `tools` 参数以结构化 JSON 下发的，**不走 system prompt**。模型本就从 `tools[].function.description` 读取工具信息。
2. 在 system 里重复一遍纯属冗余，且**每一轮对话都要重发**，持续烧 token。
3. 更糟的是存在**不一致风险**：system 里的目录与实际 `tools` 参数一旦不同步（如某工具解析失败被跳过），模型会更困惑。

既然 description 已承担消歧职责，system 侧不需要任何工具相关文案。§10 骨架的 `buildSystem` 相应**只拼 prompt 与 skill L1 目录**。

#### 顺带简化：短名保留规则可以取消

统一加命名空间前缀后，内置 skill 工具为 `ace__skill__load_skill`，业务侧同名工具为 `local__xx__load_skill`，**天然不撞**。§6.4.1 中「短名 `load_skill` 为保留名、业务让位」的规则不再需要（`BUILTIN` 优先级仍保留，用于去重排序）。

#### 为什么不做「无冲突用短名」

短名看着更简洁，但会让工具名**不稳定**：今天只有一个 `query_order` 于是用短名，明天接入第二个 MCP 触发冲突，它就突然变成 `mcp__crm__query_order`。若 prompt 里提到过工具名，或业务侧对工具调用做了埋点统计，都会在毫无征兆的情况下失效。统一前缀换来的是**行为可预测**，代价仅是名字长一些——而模型选工具主要看 description，不看 name。

---

## 6. Skill 渐进披露（需求 6）

> 业界最佳实践：**Progressive Disclosure**。禁止把成百上千份 `SKILL.md` + 脚本一次性挂进 `ChatClient`。

### 6.1 三层加载

| 层 | 加载什么 | 何时 | 说明 |
|---|---|---|---|
| L1 Catalog | `code` / `name` + 短 `description` | 节点执行启动时 | 仅本节点白名单内的 skill |
| L2 Instructions | 完整 `SKILL.md` 正文 | 激活该 skill 时 | 建议单份 &lt; 5k tokens |
| L3 Resources | `scripts/`、`references/`、附件 | 正文引用到时再读 | 激活时只返回资源**索引**，不预读内容 |

### 6.2 UI 勾选 = 节点白名单（L1 可见范围）

定义 Agent 节点时，Skill 为 **两级勾选**：

1. **类型总开关** `[x] Skill` → `enableSkill=true`（启用本节点 Skill 挂载）
2. **条目多选** → 只有勾选中的 key 写入 `skillKeys`，构成白名单

示例：`[x] Skill` + `[[x] skill.refund]` + `[ skill.invoice]`（invoice 未勾选）表示：

- 启用 Skill 挂载  
- 白名单仅 `skill.refund`  
- `skill.invoice` 不在白名单：不进 L1、不可 `load_skill`

其它约定：

- 全库可有成百上千 skill；catalog 可列出候选，**只有条目勾选的进入白名单**
- **条目勾选 ≠ 预加载正文**：不把对应 `SKILL.md`/脚本装进 system 或 ChatClient
- `enableSkill=false`：忽略 `skillKeys`，不注入目录、不注册 skill 相关 Tool
- `enableSkill=true` 但 `skillKeys` 为空：启用了类型却无白名单，等价本节点无可用 skill（可 UI 提示补选）

```text
全库 Skills（上千）
        │
        ▼
UI 勾选 skillKeys ──► 本节点白名单（例如 5～30 个）
        │
        ▼
L1：仅白名单元数据进 system / load_skill 工具描述
        │
   模型或业务激活某一 code
        │
        ▼
L2：SkillContentLoader 读 SKILL.md → 作为 Tool 结果回灌 messages
        │
        ▼
L3：read_skill_resource(code, path) 按需读脚本/附件
```

**上千 skill 时**：优先靠节点白名单控制 L1 体积；若单节点白名单仍过大，再增补 `search_skills(query)`（P2），先检索 Top-K 元数据再 `load_skill`。

### 6.3 触发与正文回灌（闭环）

推荐主路径：**框架内置 Tool**（优于解析 `SKILL:xxx` 文本标记）：

| 工具 | 作用 |
|---|---|
| `load_skill(code)` | 校验 `code ∈ 本节点 skillKeys`；调用 `SkillContentLoader` 返回 L2；附带 L3 资源索引 |
| `read_skill_resource(code, path)` | 按需读 L3；同样校验白名单 |

回灌规则：

1. L2/L3 内容作为 **Tool 结果 append 进 messages**，不修改已固定的 system 前缀  
2. 同一 `code` 重复加载：**去重**（已激活可返回短提示）  
3. **强制激活**：见 §6.3.1（用户/上游怎么指定）  
4. **模型自选**：依赖 L1 description（写清「做什么 + 何时用」；勿在 description 里写完整流程）；模型调用内置工具 `ace__skill__load_skill`

### 6.3.1 用户 / 上游「指定要用哪个 Skill」用什么格式（定案）

先承认缺口：此前只写了「可强制 `load_skill`」，**没写清人怎么指定、state 里什么形状**。这里补齐。

#### 三种激活方式（谁说了算）

| 方式 | 谁发起 | 框架认什么 | 聊天口令谁定 |
|---|---|---|---|
| A. 模型自选 | 模型看 L1 目录后调 `load_skill` | 工具参数里的 `code`（= skill key） | 无 |
| B. 上游 / 业务强制激活 | 前置节点、BFF、业务 Controller | **state 保留键里的结构化列表**（见下） | 无（已是结构化） |
| C. 终端用户在对话里点名 | 用户打字或点 UI | **不直接解析聊天原文**；由业务前端/前置逻辑转成方式 B | **业务定**，框架不强制口令 |

**一句话**：框架只约定 **state 里怎么写「要强制激活的 skill」**；**不约定**用户必须说 `/skill xxx` 还是「用退款技能」——避免又变成框架私定交互协议（和流式协议归属同一原则）。

#### 框架约定的唯一结构化格式（方式 B）

保留键（与 `agentCode` / `runId` 同前缀）：

```java
/** 本次节点执行前要强制激活的 skill key 列表（有序；可空） */
public static final String ACE_FORCE_SKILLS_KEY = "ace.graph.dsl.forceSkills";
```

**state 里的值形态**（落库 / 入口写入时建议统一成 JSON 数组可读的结构）：

```json
["skill.refund", "skill.invoice"]
```

或 Java：

```java
List<String> forceSkills = List.of("skill.refund", "skill.invoice");
state.put(ACE_FORCE_SKILLS_KEY, forceSkills);
```

规则：

| 规则 | 说明 |
|---|---|
| 元素含义 | 与 UI `skillKeys`、L1 的 `code` **同一套 key**（例如 `skill.refund`） |
| 有序 | 按列表顺序依次 `load_skill`，先激活的先回灌 |
| 不在白名单 | **跳过该 code** + **info** 日志（多节点场景很常见，不要打成 error）；**不删** state 里的列表，留给后续节点 |
| 空 / 缺键 | 不强制激活，走模型自选即可 |
| 写入时机 | 建议在 **图执行入口** 写入初始 state（与 `runId` / `agentCode` 一起）；整次 run 内随 `OverAllState` 往后传，**中间节点不要清掉** |
| 多节点传递 | **能传到第 N 个节点**（见 §6.3.2） |

节点执行顺序（有强制列表时）：

```text
读 ACE_FORCE_SKILLS_KEY
  → 对每个 code（∈ 本节点 skillKeys）先 load_skill，正文进 messages
  → 不在本节点白名单的 code：跳过 + 日志（不删 state 里的列表）
  → 再进入正常 ChatClient 轮次（模型仍可再 load 其它白名单 skill）
```

#### 不同项目聊天口令不一样时，怎么激活？（必读）

现实里很常见：

| 项目 | 前端/业务约定的用户输入 | 真正要激活的 skill key |
|---|---|---|
| 项目 A | `/ {{monitor-third-mcp\|企业邮件/CC 聊天记录查询（MCP）}}`  
即 `/ {{skill_code \| skill_name}}` | `monitor-third-mcp` |
| 项目 B | `/monitor-third-mcp` | `monitor-third-mcp` |

**ace-graph-dsl 两种都不解析。** 激活路径一律是：

```text
用户按「该项目自己的口令」输入
    → 该项目的前端 或 BFF 或前置节点：按自己的约定抠出 skill_code
    → 写入 state：ace.graph.dsl.forceSkills = ["monitor-third-mcp"]
    → 进入图 / Agent 节点
    → 框架只读 forceSkills，按 key 做 load_skill（并校验本节点白名单）
```

项目 A 解析示意（业务自己写，框架不内置）：

```text
输入：/ {{monitor-third-mcp|企业邮件/CC 聊天记录查询（MCP）}}
解析：取 | 左侧 → monitor-third-mcp
写入：forceSkills = ["monitor-third-mcp"]
```

项目 B 解析示意：

```text
输入：/monitor-third-mcp
解析：去掉前导 / → monitor-third-mcp
写入：forceSkills = ["monitor-third-mcp"]
```

两边进到框架之后**完全一样**。  
`skill_name`（中文名）只给人看；**框架激活只认 key（code）**，与 UI `skillKeys`、工具参数 `code` 一致。

若前端已经点选技能、不走文本口令：直接 `forceSkills = ["monitor-third-mcp"]`，连解析都不用。

**不要**指望在 Template 里用正则同时兼容 A、B 及以后第三种口令——那会变成框架替所有业务定交互协议，且永远跟不齐。

#### 样例代码（业务侧，框架不内置）

下面都是**接入方自己的代码**示意：解析各自口令 → 写入保留键 → 再调图执行。类名、包名按业务项目改即可。

**1）项目 A：解析 `/ {{code|name}}`，写入 state**

```java
/** 项目 A 约定：/ {{skill_code|skill_name}} → 只取 code */
public final class ProjectASkillMentionParser {

    private static final Logger log = LoggerFactory.getLogger(ProjectASkillMentionParser.class);

    // 匹配：/ {{code|任意说明}} ，允许花括号内外有空格
    private static final Pattern PATTERN = Pattern.compile(
            "/\\s*\\{\\{\\s*([^|{}]+?)\\s*\\|[^}]*}}");

    private ProjectASkillMentionParser() {}

    /**
     * 从用户原文解析要强制激活的 skill key 列表。
     * @param userText 用户输入，例如：/ {{monitor-third-mcp|企业邮件/CC 聊天记录查询（MCP）}}
     * @return 例如 ["monitor-third-mcp"]；解析不到则空列表
     */
    public static List<String> parseForceSkills(String userText) {
        if (userText == null || userText.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        Matcher m = PATTERN.matcher(userText);
        while (m.find()) {
            String code = m.group(1).trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        log.info("项目A技能口令解析: 原文长度={}, forceSkills={}", userText.length(), codes);
        return List.copyOf(codes);
    }
}
```

**2）项目 B：解析 `/code`，写入 state**

```java
/** 项目 B 约定：/monitor-third-mcp → code 即 monitor-third-mcp */
public final class ProjectBSkillSlashParser {

    private static final Logger log = LoggerFactory.getLogger(ProjectBSkillSlashParser.class);

    // 行首或空白后的 /xxx（不含空格与 |）
    private static final Pattern PATTERN = Pattern.compile("(?:^|\\s)/([A-Za-z0-9_.\\-]+)");

    private ProjectBSkillSlashParser() {}

    public static List<String> parseForceSkills(String userText) {
        if (userText == null || userText.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        Matcher m = PATTERN.matcher(userText);
        while (m.find()) {
            codes.add(m.group(1));
        }
        log.info("项目B技能口令解析: 原文长度={}, forceSkills={}", userText.length(), codes);
        return List.copyOf(codes);
    }
}
```

**3）业务执行入口：解析后写入保留键，再跑图**

> `graphId` 从哪来：见 **§4.2.1**（入口写死 / 配置映射，框架不按 agentCode 猜图）。

```java
@PostMapping("/agents/cs-assistant/stream")
public SseEmitter stream(@RequestBody ChatRequest req) {
    String agentCode = "cs-assistant";           // 本入口写死
    String graphId = "graph-refund-v3";          // 本入口绑哪张图（§4.2.1）
    String runId = UUID.randomUUID().toString();

    Map<String, Object> inputs = new LinkedHashMap<>();
    if (req.inputs() != null) {
        inputs.putAll(req.inputs());
    }
    inputs.put(LlmRequestContext.ACE_AGENT_CODE_KEY, agentCode);
    inputs.put(ModelOverrideSpec.ACE_RUN_ID_KEY, runId);

    // 选本项目自己的解析器（A 或 B），不要两套混用同一入口
    List<String> forceSkills = ProjectASkillMentionParser.parseForceSkills(req.userText());
    // List<String> forceSkills = ProjectBSkillSlashParser.parseForceSkills(req.userText());
    inputs.put(LlmRequestContext.ACE_FORCE_SKILLS_KEY, forceSkills);

    log.info("智能体入口: agentCode={}, graphId={}, runId={}, forceSkills={}",
            agentCode, graphId, runId, forceSkills);
    // 框架执行口等价：POST /execution/{graphId}/stream
    return executionFacade.stream(graphId, inputs, runId);
}
```

**4）前端点选技能（不走文本口令）**

```javascript
// ace-graph-dsl-ui 业务定制页：用户勾选技能后直接塞初始 state
const inputs = {
  'ace.graph.dsl.agentCode': 'cs-assistant',
  'ace.graph.dsl.runId': crypto.randomUUID(),
  'ace.graph.dsl.forceSkills': ['monitor-third-mcp'],  // 与节点 skillKeys 同一套 key
  // ... 其它业务字段
}
await api.stream(graphId, { inputs })
```

**5）框架侧读取（实现 Template / Agent 节点时，产品内代码）**

```java
@SuppressWarnings("unchecked")
List<String> forceSkills = Optional.ofNullable(state.value(LlmRequestContext.ACE_FORCE_SKILLS_KEY).orElse(null))
        .map(v -> {
            if (v instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            log.warn("节点 {} 的 forceSkills 类型不是 List: {}", nodeId, v.getClass().getName());
            return List.<String>of();
        })
        .orElse(List.of());

for (String code : forceSkills) {
    if (!binding.skillKeys().contains(code)) {
        // 多节点场景很常见：意图留给后面的节点，本节点白名单故意不含 → info 而非 error
        log.info("节点 {} 的 forceSkills 含 {}，但不在本节点白名单，跳过加载（保留键仍留给后续节点）",
                nodeId, code);
        continue;
    }
    // load_skill(code) → 正文 append 进 messages（实现细节见 §6.3）
    log.info("节点 {} 强制激活 skill={}", nodeId, code);
}
```

#### 终端用户聊天口令：框架不规定，给业务参考即可

下面**不是**框架协议，只是业务可选用的参考；解析后必须写入 `ACE_FORCE_SKILLS_KEY`，框架才认：

| 参考做法 | 示例 | 谁解析 |
|---|---|---|
| 斜杠 + code | `/monitor-third-mcp` | 业务网关 / 前置节点 |
| 斜杠 + `{{code\|name}}` | `/ {{monitor-third-mcp\|企业邮件…}}` | 同上（取 `\|` 左侧为 code） |
| UI 点选 | 用户在前端选技能 | 前端直接塞 state 列表 |
| 自然语言 | 「查一下企业邮件」 | 业务 NLU，或交给模型自选（方式 A） |

**明确不做**：

- 框架在 Template 里用正则抠用户原文里的 `SKILL:xxx` / `/skill`（易误伤正文，且和「协议归业务」冲突）  
- 另搞一套与 `skillKeys` 不同的「展示名 / 别名」协议却不写映射表（若业务要别名，在写入 forceSkills **之前**自己映射成正式 key）

#### `code` 参数长什么样（模型调工具时）

内置工具对模型暴露的名字是 `ace__skill__load_skill`（§5.3），参数：

```json
{ "code": "skill.refund" }
```

`code` 必须等于白名单里的 key；description 里应写明「code 取自可用技能目录中的 key」。

#### 怎么验收

1. 入口写入 `forceSkills=["skill.refund"]` 且节点白名单含该项 → 进模型前日志有「强制激活 skill.refund」，且 messages 中已有 L2 正文  
2. 写入不在白名单的 code → 跳过 + 日志，节点不整体失败；**state 中 forceSkills 仍保留**，可供后续节点使用  
3. 用户只说自然语言、业务未写 forceSkills → 不报错，靠模型自选 `load_skill`  
4. 业务自己做了 `/skill` 解析并写入 forceSkills → 行为与方式 B 相同  
5. **多节点**：入口写入后，仅第 N 个节点勾选该 skill → 前序节点跳过（info）、第 N 个强制激活（§6.3.2）

### 6.4 Resolver 职责划分

```java
/** L1：按白名单 keys 只解析元数据 */
@FunctionalInterface
public interface SkillCatalogResolver {
    List<SkillDescriptor> resolve(LlmRequestContext ctx, List<String> skillKeys);
}

public record SkillDescriptor(
    String key,              // code，与 UI skillKeys 一致
    String name,
    String shortDescription, // L1 展示 / 何时用
    String triggerHint
) {}

/** L2：真正用到时再加载 SKILL.md 正文 */
@FunctionalInterface
public interface SkillContentLoader {
    Optional<String> loadBody(LlmRequestContext ctx, String skillKey);
}

/** L3：按需加载脚本/附件（可与 ContentLoader 合并实现） */
@FunctionalInterface
public interface SkillResourceLoader {
    Optional<String> loadResource(LlmRequestContext ctx, String skillKey, String relativePath);
    List<String> listResourceIndex(LlmRequestContext ctx, String skillKey); // 激活时只返回路径列表
}
```

Template 侧：

1. `enableSkill` 时：用 `skillKeys` 调 `SkillCatalogResolver` → 拼 L1 目录 + 注册 `load_skill` / `read_skill_resource`  
2. 工具调用时：先校验白名单，再调 Loader  
3. Tool 结果写回后继续 ChatClient 多轮（与原生 tool-calling 一致）

### 6.4.1 内置 skill 工具的命名与去重（定案）

`load_skill` / `read_skill_resource` 是**框架内置工具**，与业务的本地 Tools / MCP 工具走同一个 `ChatClient` 工具列表，因此必须纳入 §5 的去重体系。

| 规则 | 说明 |
|---|---|
| 命名空间 | 内置工具 uniqueName 固定为 `ace__skill__load_skill` 与 `ace__skill__read_skill_resource`，`source=BUILTIN`（双下划线分隔，冒号不合法，见 §5.3） |
| ~~保留名~~ | **已取消**：统一命名空间前缀后，内置工具与业务同名工具（`local__xx__load_skill`）天然不撞，无需保留名规则 |
| 冲突优先级 | 在 `ToolConflictPolicy` 之上追加一条：`BUILTIN` 永远保留，不被 LOCAL / MCP 覆盖 |
| 注册条件 | 仅当 `enableSkill=true` 且 `skillKeys` 非空时注册；否则这两个工具完全不出现在工具列表里 |
| 参数校验 | `load_skill(code)` 必须校验 `code ∈ 本节点 skillKeys`；越权返回明确错误文本（不抛异常中断节点），并记录 warn |
| 路径校验 | `read_skill_resource(code, path)` 除白名单校验外，还须做**路径穿越防护**（拒绝 `..`、绝对路径、符号链接逃逸） |

### 6.4.2 多轮与流式的关系

L2 正文经 Tool 结果回灌后需要继续下一轮模型调用，这部分**由 Spring AI 的 tool-calling 机制自动完成**，Template 不自己写循环。需要注意：

- 流式场景下，工具调用轮次不产生对用户可见的 token；只有最终回答轮的 token 经 `GraphStreamBridge` 下发
- 若希望把「正在加载 skill」这类过程可见，可在工具执行前后额外 `emit` 一个 `BIZ` 类型片段（可选，默认不发）
- 单次请求的工具调用轮数应设上限（建议 ≤ 5），防止模型反复 `load_skill` 打转

### 6.5 与现网行为差异

现网 `GenericAgentNode` 对 `skillKey` 会 **直接 load 全文拼进 prompt**。  
新方案改为：勾选只定白名单 → L1 元数据；全文仅在激活后经 Tool 回灌。旧单 `skillKey` 兼容：映射为 `skillKeys=[skillKey]`，**默认仍只注入 L1**，除非显式配置「启动即激活」（可选高级开关，默认关闭）。

### 6.6 不做

- 一次性挂载全部 / 白名单内全部 `SKILL.md` + 脚本到 ChatClient  
- 无白名单时把全库 L1 塞进 system（必须由节点勾选限定；未勾选则本节点无 skill）  

---

## 7. 资源勾选与 Catalog API（需求 8–9）

### 7.1 ResourceBinding

不是每个节点都需要 prompt / skill / tools / MCP 全量加载。  
UI 勾选类型 + 填写/选择 key；未勾选类型 Template **跳过**对应 Resolver。

```java
public record ResourceBinding(
    boolean enablePrompt,
    List<String> promptKeys,      // 有序；可多套合并
    boolean enableModel,
    String modelConfigKey,        // 通常单套
    boolean enableLocalTools,
    List<String> localToolKeys,
    boolean enableMcp,
    List<String> mcpKeys,
    // serverKey → 该 server 内启用的工具名；缺省或空列表 = 该 server 全部工具（§7.1.1 / D3）
    Map<String, List<String>> mcpToolWhitelist,
    boolean enableSkill,
    List<String> skillKeys
) {}
```

#### `ResourceBindings.fromSpec(spec)`：谁实现？（定案）

**一句话结论**：这是 **ace-graph-dsl 产品内**的纯映射工具，**不是**业务 SPI，业务**不必、也不该**自己实现 `fromSpec`。

把它拆开看就不会混：

| 东西 | 谁提供 | 干什么 |
|---|---|---|
| UI 勾选结果落在 `GenericAgentSpec` / 定义库 | 框架存取 + UI 编辑 | 保存 enableXxx + keys |
| `ResourceBinding` 记录类型 | **框架** | 运行期「本节点勾了什么」的只读视图 |
| `ResourceBindings.fromSpec(spec)` | **框架** | 从 Spec **抄出** Binding，字段一一对应，无 IO、无远程 |
| Prompt/MCP/Skill/… **Resolver** | **业务**实现 Bean | **按 key 取真实内容**（Nacos / DB / 文件…） |
| Catalog.list | **业务**实现 Bean | 设计期给 UI 的可选列表 |

```text
UI 勾选 → 落库 GenericAgentSpec.resourceBinding（或等价字段）
                │
                ▼  框架节点执行时（业务薄节点 / GenericAgentNode）
    ResourceBindings.fromSpec(spec)  →  ResourceBinding（只有开关和 keys）
                │
                ▼  框架 Template
    resolvers.prompt().resolve(ctx, binding.promptKeys())  → 业务才真正去拉正文
```

关键样例（**产品内代码**，放在 `ace-graph-dsl-ai` / core，业务项目直接调用）：

```java
/** 框架工具类：Spec → 运行期 Binding；禁止业务再写一份不一致的拷贝逻辑 */
public final class ResourceBindings {

    private ResourceBindings() {}

    /**
     * 从节点 Spec 抽出资源勾选视图。
     * 仅做字段映射；key 是否存在、内容如何加载一律不在这里做。
     */
    public static ResourceBinding fromSpec(GenericAgentSpec spec) {
        Objects.requireNonNull(spec, "GenericAgentSpec 不能为空");
        // 若 Spec 已内嵌 resourceBinding 字段，直接返回副本即可：
        // return Objects.requireNonNullElseGet(spec.resourceBinding(), ResourceBinding::disabledAll);
        // 若仍扁平挂在 Spec 上，则显式组装：
        ResourceBinding b = new ResourceBinding(
                spec.enablePrompt(),
                List.copyOf(nullToEmpty(spec.promptKeys())),
                spec.enableModel(),
                spec.modelConfigKey(),
                spec.enableLocalTools(),
                List.copyOf(nullToEmpty(spec.localToolKeys())),
                spec.enableMcp(),
                List.copyOf(nullToEmpty(spec.mcpKeys())),
                Map.copyOf(nullToEmptyMap(spec.mcpToolWhitelist())),
                spec.enableSkill(),
                List.copyOf(nullToEmpty(spec.skillKeys()))
        );
        log.debug("ResourceBindings.fromSpec: node 侧开关 prompt={}, model={}, mcp={}, skill={}",
                b.enablePrompt(), b.enableModel(), b.enableMcp(), b.enableSkill());
        return b;
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, List<String>> nullToEmptyMap(Map<String, List<String>> map) {
        return map == null ? Map.of() : map;
    }
}
```

节点里那行的真实含义：

```java
// graphId / nodeId：构图时框架已注入本节点
// agentCode / runId：从入口写入的 state 保留键读取
// binding：框架从本节点 Spec 映射 —— 不是业务传进来的「实现」
LlmRequestContext ctx = new LlmRequestContext(
        agentCode, graphId, nodeId, runId, state, ResourceBindings.fromSpec(spec));
```

**明确不做**：

- 不要求业务实现 `ResourceBindings` / `fromSpec`  
- `fromSpec` **不**调 Resolver、**不**校验 key 是否存在（校验见 §7.4）  
- 业务只实现「按 key 取数」的 Resolver；勾选矩阵的读写与映射归框架

**怎么验收**：

1. UI 勾选 `promptKeys=["cs.sys"]` 保存后，节点执行时 `ctx.binding().promptKeys()` 即为该列表  
2. 业务工程中**搜不到**自写的 `fromSpec`；只有框架模块里有一份  
3. 关掉某类型 enable 后，Template 跳过对应 Resolver（与 §7.1 总开关一致）

> **本期不做 AgentCard**：不纳入 `ResourceBinding`、不设计 Resolver、Template 亦不消费。  
> 后续若要从 Card 汇总 skill/工具/安全信息，再单独立项（绑定 + 解析 + 与独立 skill/mcp keys 的优先级）。

### 7.1.1 旧字段清理与 MCP 工具级过滤（D3 定案）

**前提**：确认无存量图，出现也可重建，故**不做任何兼容映射**——原「`promptKey` → `promptKeys=[promptKey]`」之类的自动转换全部取消。

#### 先纠正一处对 `spec.tools` 的误判

D3 缺口原写「旧 `tools[]` → `localToolKeys` 或 mcp 工具过滤名」，前一半是错的。查证现网 `GenericAgentNode#resolveTools` 与 `InMemoryMcpToolProvider#effectiveToolNames`：

```java
// 节点声明的工具名与 server 白名单取交集；节点未声明时用 server 白名单全集
```

即 `spec.tools` **从来不是本地工具 key，而是 MCP 工具级过滤白名单**——先由 `mcp`/`mcpKey` 定位到某个 server，再用 `tools` 从该 server 暴露的工具里挑子集。

这意味着直接删掉它会**丢失一项能力**：新设计的 `mcpKeys` 只能整个 server 全选。这不是兼容问题，是**功能缺口**，且是生产刚需——一个 MCP server 动辄暴露几十个工具，全量挂载会带来三重代价：

| 代价 | 说明 |
|---|---|
| token | 每个工具的 name/description/inputSchema 都要随每轮请求下发 |
| 准确率 | 候选越多模型越容易选错（与 §5.3 第二层同源） |
| 安全 | 把不该暴露给模型的工具（如删除类）一并给了出去 |

#### 因此：删字段，但把能力搬到 `ResourceBinding`

新增 `mcpToolWhitelist`，语义与现网**完全一致**，只是从 `spec` 平移到 `binding`：**两级白名单取交集**——server 侧（`McpServerConfig.tools()`）划定可暴露范围，节点侧再挑子集，节点未声明则取 server 全集。

两级各由谁施加：

| 层级 | 施加者 | 说明 |
|---|---|---|
| server 级 | 业务的 `McpToolResolver` 实现 | 它读自己的 MCP 配置，天然知道该 server 暴露什么 |
| 节点级 | **框架**（`McpToolFilter`） | 见下 |

#### 过滤放在框架侧，不放进 SPI

`McpToolResolver` 签名**保持不变**（仍是 `resolve(ctx, mcpKeys)`），白名单过滤由 Template 侧的 `McpToolFilter` 统一施加。

职责划分是：**业务负责「取」，框架负责「筛」**。理由是白名单同时承担安全边界职责，若下放到 SPI，任一业务实现漏做过滤即形成绕过；而 MCP 的 `list_tools` 本就一次返回全部，框架侧过滤不产生额外远程开销。过滤逻辑亦因此只有一处实现。

```java
/** 按节点白名单过滤 MCP 工具；serverKey 取自 NamedToolCallback.logicalKey 的后半段 */
public static List<NamedToolCallback> apply(List<NamedToolCallback> resolved,
                                            ResourceBinding b, String nodeId) {
    Map<String, List<String>> wl = b.mcpToolWhitelist();
    if (wl == null || wl.isEmpty()) {
        return resolved;                      // 未声明任何白名单 = 全放行
    }
    List<NamedToolCallback> kept = resolved.stream()
            .filter(t -> {
                List<String> allow = wl.getOrDefault(t.serverKey(), List.of());
                return allow.isEmpty() || allow.contains(t.originalName());
            })
            .toList();
    if (kept.size() != resolved.size()) {
        log.info("节点 {} MCP 工具白名单过滤: {} → {} 个", nodeId, resolved.size(), kept.size());
    }
    // 白名单点了但 server 侧没暴露 → 配置错；静默忽略会让人误以为工具已挂载
    Set<String> available = resolved.stream()
            .map(NamedToolCallback::originalName).collect(Collectors.toSet());
    wl.forEach((server, names) -> {
        List<String> missing = names.stream().filter(n -> !available.contains(n)).toList();
        if (!missing.isEmpty()) {
            log.warn("节点 {} 的 MCP {} 白名单含未暴露的工具 {}，已忽略；"
                    + "请确认工具名拼写与该 server 的实际暴露范围", nodeId, server, missing);
        }
    });
    return kept;
}
```

> `NamedToolCallback` 需提供派生访问器 `serverKey()`——从 `logicalKey`（如 `mcp:weather-svc`）截出 `weather-svc`。`logicalKey` 是内部标识，不下发模型，故仍可含冒号（§5.1）。

UI 上 MCP 相应从两级勾选变**三级树**（类型开关 → server → 工具），不勾具体工具即代表该 server 全选：

```text
[x] MCP
  [x] crm-svc                    → mcpKeys=["crm-svc"]
      [x] query_order            → mcpToolWhitelist={"crm-svc":["query_order"]}
      [ ] delete_order
  [ ] erp-svc                    → 未勾选，不加载
```

#### 各旧字段处置

| 旧字段 | 处置 | 理由 |
|---|---|---|
| `tools` | **删**，能力由 `mcpToolWhitelist` 承接 | 见上 |
| `promptKey` / `skillKey` / `mcpKey` | **删** | 被 `promptKeys` / `skillKeys` / `mcpKeys` 取代，且新字段支持多选与排序 |
| `skill` / `mcp`（内联文本） | **删** | 二者都是**跨节点共享资源**，天然该注册后按 key 引用；保留内联等于鼓励复制粘贴 |
| `prompt`（内联模板） | **保留**，但语义改为「节点特化追加」 | 唯一有合理特化需求的字段，详见下 |

保留 `prompt` 的理由与新语义：prompt 常有一句两句的节点专属补充（如「本节点只输出 JSON」），为此注册一个资源过重。但**不再是「与 promptKey 二选一」**——那要定优先级、要处理两者都填的情况。改为**纯追加**：

```text
system = 渲染(promptKeys 按序合并) + "\n\n" + 渲染(prompt 内联)
```

位置固定在末尾，无优先级概念，也不影响 §4.4.2 的单遍渲染（两段合并后一次扫描）。

#### 废弃字段的检测：分层处理

`GenericAgentSpec` 带 `@JsonIgnoreProperties(ignoreUnknown = true)`，删字段后残留 JSON **不会反序列化失败**——但会被**静默吞掉**。若有人照旧文档填了 `tools`，他会以为工具已挂载，实际一个都没有。这种静默失效比报错难查得多。

故分两层：

| 层 | 策略 | 理由 |
|---|---|---|
| 反序列化 | **保留 `ignoreUnknown = true`** | 灰度期两版本后端需能互读图定义，收紧会导致回滚失败 |
| 保存 / 编译入口 | **检出废弃字段即 fail fast** | 明确告知字段已删及替代项，不给静默机会 |

```java
/** 已删除字段 → 替代项，用于给出可操作报错（保存与编译期共用） */
private static final Map<String, String> REMOVED_FIELDS = Map.of(
        "tools",      "已删除，请改用 ResourceBinding.mcpToolWhitelist（MCP 工具级白名单）",
        "promptKey",  "已删除，请改用 ResourceBinding.promptKeys（支持多选与排序）",
        "skillKey",   "已删除，请改用 ResourceBinding.skillKeys",
        "mcpKey",     "已删除，请改用 ResourceBinding.mcpKeys",
        "skill",      "已删除，请注册为 skill 资源后用 skillKeys 引用",
        "mcp",        "已删除，请注册为 MCP 资源后用 mcpKeys 引用");
```

检测点放在 `GenericAgentNodeService`（保存）与 `DynamicGraphBuilder#resolveGenericAgent`（编译）——后者已是内联与注册式的唯一汇聚点（§9.7.3 / §12.1），不新增遍历。

#### 连带删除的类

| 类 | 处理 |
|---|---|
| `McpToolProvider` / `InMemoryMcpToolProvider` | 删除，职责由 `McpToolResolver` 承接（返回 `NamedToolCallback`） |
| `AgentTool` | 删除（A8 已定，统一到 `ToolCallback`） |
| `McpServerConfig` | **保留**，`tools()` 仍是 server 级白名单来源 |

### 7.2 Catalog 列表 API（设计期：只有 agentCode，没有节点 keys）

**干什么**：给 UI 勾选用的「可选资源列表」。此时用户还没勾完（或正在勾），**没有** `promptKeys` / `mcpKeys` 等可传。

**初筛靠什么**：业务在实现里用查询参数里的 **`agentCode`**（必填建议）圈定这个智能体的资源大盘；可选再带 `graphId` / 注册式节点定义 id。  
**不把**节点 Binding 里的 keys 当作列表入参——那些 keys 是列表勾选的**结果**，不是列表的前提。

```text
GET /api/agent-resources/prompts?agentCode=&graphId=&agentDefId=
GET /api/agent-resources/models?agentCode=&...
GET /api/agent-resources/tools?agentCode=&...
GET /api/agent-resources/mcp?agentCode=&...
GET /api/agent-resources/skills?agentCode=&...
```

> 不含 `agent-cards`（本期不做）。  
> 参数名：`agentCode` = 智能体产品入口编码（与 §4.2 同一概念）；`agentDefId` = 注册式 Agent 节点定义 id（可选，旧文案里的 `agentId` 易与 agentCode 混淆，故改名）。

响应示例：

```json
{
  "items": [
    { "key": "cs.reply.ja", "label": "日译提示词", "description": "..." }
  ]
}
```

```java
public interface AgentResourceCatalog {
    ResourceType type();
    /**
     * 列出可供 UI 勾选的资源。
     * @param agentCode 智能体产品编码，业务据此做资源大盘初筛（可空则业务自行决定是否返回空/全量）
     * @param graphId   可选
     * @param agentDefId 可选，注册式节点定义 id
     */
    List<ResourceItem> list(String agentCode, String graphId, String agentDefId);
    // 默认：return List.of();
}

public enum ResourceType {
    PROMPT, MODEL, LOCAL_TOOL, MCP, SKILL
    // AGENT_CARD 本期不做
}

public record ResourceItem(String key, String label, String description) {}
```

开发者实现则返回可选 key；未实现默认空列表。UI 始终支持手动添加 key（手填的 key 仍在运行期走 Resolver）。

**约束（软）**：Catalog 返回的 key **宜**与运行时 Resolver 使用同一 key 空间。框架**不在保存期强制对齐**（见 §7.4）；不一致时以运行期加载结果为准，并通过错误日志与调试 UI 暴露。

**和 Resolver 的分工（再强调一次）**：

| | Catalog（本节） | Resolver（§4.3） |
|---|---|---|
| 时机 | 设计期 UI | 运行期 Template |
| 入参 keys | **无** | **有**（节点已勾选） |
| agentCode | 有（查询参数） | 有（`ctx.agentCode()`） |
| 典型用途 | 圈大盘、填下拉 | 按勾选加载正文/工具 |

### 7.3 前端交互

1. 选中 Agent 节点 → 属性面板「资源装配」+「流式响应方式」
2. 每类资源均为 **两级勾选**（Prompt / Skill / MCP / LocalTools 语义相同）：
   - 左侧类型总开关 → `enableXxx`
   - 右侧候选条目多选 → 只有勾选中的 key 写入对应 `*Keys`（本节点实际应用列表 / 白名单）
   - prompt / tools / mcp / skill：多 key；model：单 key（单选）
3. 打开面板时并行请求：各类资源 catalog + **流式响应类型 KEY 列表**（见第 9.4 节）
4. catalog 候选默认 **条目未勾选**；用户勾选后才写入 Binding
5. **流式响应方式：节点必选 KEY**（见第 9.4.3 节）
   - 打开面板拉取 kinds 列表后，**默认选中列表第一项**（`items[0].key`）
   - 用户可改选其他已实现类型
   - 若用户未操作/忘记选择：保存与运行一律使用前端已写入的默认 KEY（首位），**不得**提交空值
6. 支持拖拽调整 **已勾选** `promptKeys` 的顺序（合并顺序有意义）
7. 保存图 → `agentSpec` 落库；运行只加载「类型已启用且条目已勾选」的资源；所选 kind 随 SSE 片段透传给业务 Formatter
8. 试跑与图内执行共用同一 `execute` 链路

### 7.3.1 两级勾选通例

| UI | 含义 |
|---|---|
| 左侧 `[x] Prompt`（或 Skill / MCP / …） | **启用**该类型挂载（`enableXxx=true`） |
| 右侧 `[[x] some.key]` | 该 key **应用到本节点**（写入对应 keys） |
| 右侧 `[[ ] other.key]` | 仅在候选列表中，**不应用**到本节点 |

**Prompt 跨节点示例**（同一 catalog，各节点应用列表不同）：

```text
NODE-A：[x] Prompt  [[x] cs.sys] [[x] cs.ja] [[ ] cs.output]
         → enablePrompt=true, promptKeys=["cs.sys","cs.ja"]
         （cs.output 未应用到 A）

NODE-B：[x] Prompt  [[ ] cs.sys] [[x] cs.ja] [[x] cs.output]
         → enablePrompt=true, promptKeys=["cs.ja","cs.output"]
         （cs.sys 未应用到 B）

NODE-C：[x] Prompt  [[x] cs.sys] [[ ] cs.ja] [[ ] cs.output]
         → enablePrompt=true, promptKeys=["cs.sys"]
         （仅 cs.sys 应用到 C）
```

Template：只按该节点 `promptKeys` **有序**合并拉取并注入；未写入 keys 的 key 绝不加载。

**Skill** 同理（条目勾选 = 白名单，仅 L1；见 §6.2）：

```text
enableSkill=true, skillKeys=["skill.refund"]  // [[x] skill.refund] [[ ] skill.invoice]
```

交互示意（NODE-A）：

```text
[x] Prompt     ← 启用提示词挂载
               [[x] cs.sys ]     ← 应用到本节点
               [[x] cs.ja ]      ← 应用到本节点（顺序可调）
               [[ ] cs.output ]  ← 未应用
[x] Model      (o) model.qwen-plus
[ ] LocalTools
[x] MCP        [[x] mcp.weather ] [[ ] mcp.crm ]
[x] Skill      ← 启用 Skill 挂载
               [[x] skill.refund ]
               [[ ] skill.invoice ]

多模态入参 key  [ multimodal_refs ]  ← 可留空；填了就从该 state key 读引用（§8.2.1）
流式响应方式 *  [ 下拉：BIZ ← 默认首位 | OUTPUT | TOOL | ... ]
```

> UI 本期不展示 AgentCard 勾选项。

---

### 7.4 资源 key 校验策略（C1 / C2 定案）

#### 定案一句话

**prompt / model / localTool / mcp / skill 等资源 key：默认允许先保存，不在保存期做「key 是否存在」校验；运行时加载失败则打错误日志，调试 UI 尽可能把失败的 key 列出来。仅当业务提供了校验 SPI 时，保存期才可选启用校验。**

流式响应类型 `streamResponseKind` **不在此列**——它仍按 §9.4 / §9.7：必选、目录内、空值归一；那是框架自有配置目录，与外部资源注册中心无关。

#### 为什么默认不做保存期存在性校验

| 原因 | 说明 |
|---|---|
| 资源常在外部系统 | Prompt / MCP / Skill 可能在 Nacos、配置中心、MCP Server；保存瞬间未必可达，硬拦会误伤「先画图后上资源」的工作流 |
| Catalog ≠ Resolver | Catalog 只负责 UI 候选列表；真实能否加载以 Resolver 为准。框架无法在无业务实现时凭空「ping」远端 |
| 与 D3「零兼容 / 先跑起来」一致 | 无存量包袱时更应降低保存摩擦，把正确性验证放到可观测的运行/调试路径 |

#### 三层行为

| 层 | 做什么 | 不做 |
|---|---|---|
| **保存 / 发布（默认）** | 只做结构级与废弃字段检查（`REMOVED_FIELDS`、JSON 形状等）；**不**因 key 在远端不存在而拒绝保存 | 不强制 `enableXxx=true ⇒ keys 非空` 的存在性校验；不强制 Catalog ∩ Resolver |
| **运行期** | Resolver 按 key 加载；**加载不到则 error 日志**（含 `graphId` / `nodeId` / `resourceType` / `key` / 原因），节点按策略失败或降级（见下） | 不静默跳过并假装成功 |
| **调试 UI（`/debug/stream`）** | 汇总本 run 内「无法加载的资源 key」并输出到调试通道，便于属性面板对照 | 不依赖业务 Formatter（与 §9.6.7 一致） |

#### 运行期加载失败策略

按资源类型区分「硬失败」与「可跳过」：

| 资源 | 失败时 | 理由 |
|---|---|---|
| **Model**（最终三路仍无有效端点） | **节点 fail fast** | 没有模型无法调用（§4.4.1 已定） |
| **Prompt**（`enablePrompt` 且某个 `promptKey` 加载失败） | **节点 fail fast** | system 残缺比静默少一段指令更危险 |
| **MCP / LocalTool**（某个 key 加载失败） | **跳过该 key + error 日志**；其余工具继续挂载 | 单工具缺失常可降级；整节点炸掉过重 |
| **Skill**（某个 `skillKey` 元数据加载失败） | **从本节点白名单剔除 + error 日志**；其余 skill 继续 | L1 目录缺一项可降级；`load_skill` 越权规则仍生效 |
| **MCP 工具白名单点名但 server 未暴露** | warn（§7.1.1 已定） | 配置拼写问题，非「资源中心挂了」 |

关键节点日志示例（必须带齐定位字段）：

```java
log.error("节点 {} 资源加载失败: type={}, key={}, graphId={}, cause={}",
        ctx.nodeId(), ResourceType.PROMPT, key, ctx.graphId(), e.toString());
```

#### 调试 UI 如何看到失败的 key

在 `/debug/stream` 的框架标准格式中增加诊断事件（**仅调试端点**，不进入生产 `/stream`）：

```json
{
  "type": "resource_miss",
  "nodeId": "agent_cs",
  "resourceType": "PROMPT",
  "key": "cs.output",
  "message": "PromptContentResolver 未找到该 key",
  "fatal": true,
  "ts": 1757300000456
}
```

| 字段 | 说明 |
|---|---|
| `type=resource_miss` | 与 `chunk` / `node` 并列的调试诊断事件 |
| `resourceType` / `key` | 无法加载的具体资源 |
| `fatal` | `true` 表示将导致节点失败（如 Prompt）；`false` 表示已降级跳过（如单个 MCP） |

`StreamingLlmTemplate` 在 Resolver 失败时把条目写入本 run 的 `ResourceLoadDiagnostics`（线程/run 作用域），由调试端点在流结束前或失败时刷出；生产 `/stream` **不发射**该类事件（避免框架私自定生产协议）。

UI 调试台：在节点卡片或「资源装配」旁展示红色失败列表（key + 类型 + 原因），与属性面板勾选对照。

#### 可选：业务提供保存期校验（C1 收束到此）

框架提供**可选** SPI；**未实现则保存路径完全跳过存在性校验**（默认）：

```java
/**
 * 资源 key 存在性校验（可选 Bean）。
 *
 * <p>业务能同步/廉价判断 key 是否可被对应 Resolver 加载时实现本接口；
 * 未提供时框架不做保存期存在性校验，仅依赖运行期日志 + 调试 UI。</p>
 */
@FunctionalInterface
public interface ResourceKeyValidator {

    /**
     * @return 校验结果；unknown/不可达时应返回 {@code skipped} 而非伪造失败，
     *         以免远端抖动误拦保存
     */
    ValidationResult validate(ResourceType type, String key, String agentCode, String graphId, String agentDefId);
}

public record ValidationResult(
        Status status,   // OK | MISSING | SKIPPED | ERROR
        String message
) {
    public enum Status { OK, MISSING, SKIPPED, ERROR }
}
```

保存入口行为：

```text
若容器中无 ResourceKeyValidator Bean
  → 不做 key 存在性校验，直接保存
若有 Bean
  → 对 Binding 中已启用类型的每个 key 调用 validate
  → MISSING / ERROR：拒绝保存并返回可操作错误（类型 + key + message）
  → SKIPPED / OK：放行（SKIPPED 打 debug，表示业务也无法确认）
```

**不提供**框架内置「对着 Catalog.list 做差集」冒充校验：Catalog 可能不全（UI 仍允许手动填 key）、且与 Resolver 不是同一实现时差集会误报。存在性必须以业务 Validator（或运行期 Resolver）为准。

> C1「Catalog ↔ Resolver 同一 key 空间」因此收束为：**约定 + 可选 Validator + 运行期真相**；不再单独做强制联合试探 API。若业务需要「试跑单个 key」，可自行基于同一 Validator 暴露内部工具，框架不强制。

### 7.5 设计器相关接口要不要登录权限（C4 定案）

#### 用大白话说清问题

画流程图、勾选提示词、点「试跑 / 调试」的人，是**内部编排人员**。  
真正跟机器人聊天的人，是**业务终端用户**。

这两种人权限不一样。  
本方案新加的几个接口（拉提示词列表、拉流式类型下拉、设计器调试流）如果**不设门槛**，任何人知道 URL 就能调——等于把设计器后门敞开。

所以定案是：

> **给「设计器里用的接口」加上和「保存图 / 试运行」同一套菜单权限检查；  
> 「线上给用户跑图」的接口不走这套菜单，由业务自己的登录体系管。**

「与图编辑权限对齐」这句话，翻译过来就是上面这句，不要再单独猜。

#### 权限配在哪里（不用猜）

业务项目（接入 ace-graph-dsl 的那个 Spring Boot 工程）里实现一个 Bean，告诉框架「当前登录用户有哪些菜单权限」。  
实现哪个接口、菜单 key 有哪些、前端怎么藏按钮，**现成文档已经写好了**：

| 想知道什么 | 去哪看 |
|---|---|
| 后端怎么接权限、菜单 key 列表 | `ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md` |
| 和 Spring Security 怎么配合 | `ace-graph-dsl-backend/docs/SECURITY_INTEGRATION.md` |
| 前端按钮显隐 | `ace-graph-dsl-ui/src/stores/permissions.js` |
| 后端拦没登录/没权限的请求 | 各 Controller 里调用 `MenuPermissionGuard.require(...)`（保存图、试运行已经在用） |

**没接权限时：全部放行。** 本地开发不受影响；上线由业务项目接上自己的权限中心。

注意：前端把按钮藏起来**不够**。必须后端也拦一遍，否则别人直接调 HTTP 仍能过。

#### 哪些接口要拦、要哪把钥匙

把「钥匙」理解成菜单权限码（字符串），业务把自家权限映射到这些码即可。

| 谁在用 | 接口（干什么） | 要哪把钥匙 | 现在有没有拦 |
|---|---|---|---|
| 编排人员打开属性面板 | 拉可选提示词 / 模型 / 工具 / MCP / Skill 列表 | 能看图：`graph:view`（若人在 Agent 节点库里配，有 `agent-node:view` 也行） | **还没有，要实现** |
| 编排人员打开属性面板 | 拉「流式响应方式」下拉列表 | 同上 `graph:view` | **还没有，要实现** |
| 编排人员点试运行 | 用草稿图跑一遍（dry-run） | `graph:validate` | **已有** |
| 编排人员在 Agent 库里试跑节点 | Agent 试跑 / 校验接口 | `agent-node:test` | **已有** |
| 编排人员在设计器里看流式调试 | **新接口** `/execution/{图id}/debug/stream` | `graph:validate`（和试运行同级） | **还没有，要实现** |
| 终端用户 / 业务前端正式跑图 | `/execution/{图id}/stream`（以及 resume、invoke） | **不走上面这些菜单钥匙** | 见下一节 |
| 设计器启动时问「我有哪些按钮」 | 查菜单权限列表接口 | 一般不拦（否则连自己有没有权限都查不到） | 现网如此 |

没有权限时：后端返回 **403**，前端提示「没有权限」。

#### 正式跑图为什么不跟「改图权限」绑在一起

| | 设计器调试流 `/debug/stream` | 正式跑图 `/stream` |
|---|---|---|
| 谁用 | 改流程图的人 | 聊天的用户，或业务自己的后端 |
| 问的是 | 「你能不能在设计器里调试这张图？」 | 「你能不能调用已经上线的能力？」 |
| 谁管权限 | 用菜单钥匙 `graph:validate` | 业务自己的登录 / 网关 / Spring Security |
| 框架还管什么 | 开了就要检查菜单 | 另有开关 `ace.graph.dsl.web.execution.enabled`（默认关）；**不会自动套菜单钥匙** |

千万别把「能聊天的普通用户」也授成 `graph:validate`，否则等于给所有用户开了设计器调试后门。  
也别把正式流量打到 `/debug/stream`：那条线返回的是框架调试格式，不是业务线上协议。

#### 「提示词 / MCP 这些资源」还要不要再鉴权一层？

分两件事：

1. **能不能打开设计器、拉列表、点调试** → 上面菜单权限管（本节省定）。  
2. **某个具体的提示词 key、模型配置，这个租户能不能用** → **菜单不管。**  
   由业务在「列资源列表」和「按 key 加载」的实现里自己按登录用户过滤；api-key、MCP 令牌仍按现有密钥方案保管。

不必为每一个 prompt key 再发明一把菜单钥匙。

#### 对应代码（实现时照着挂）

```java
// 新接口里与「保存图 / 试运行」同一写法；没有权限就抛错 → 403
MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VIEW,
        "无权查看 Agent 资源目录");
MenuPermissionGuard.require(menuPermissions, GraphMenuPermissions.GRAPH_VALIDATE,
        "无权使用图调试流");
```

- 不新发明菜单权限码（除非产品明确要求单独拆「只能调试」）。  
- 前端：调试按钮和「试运行」用同一权限判断；列表接口若 403，属性面板直接提示无权限。

---

## 8. 多模态在节点间传递（需求 7）

### 8.1 结论

| 传递方式 | 同一 run、内存、无 interrupt | checkpoint / HITL resume |
|---|---|---|
| State 存 URL / MediaRef 字符串 | 一般不丢 | 一般不丢 |
| State 存完整 `UserMessage`（含 media） | 多数能传到下一节点 | **易丢 media** |
| 收成 `Map(role, content)` | 往往只剩文本 | 无 media |

**不要指望** checkpoint 原样恢复 `UserMessage.media`。  
上游参考：[spring-ai-alibaba#3913](https://github.com/alibaba/spring-ai-alibaba/issues/3913)、[#4562](https://github.com/alibaba/spring-ai-alibaba/issues/4562)。

### 8.2 MediaRef 协议（定案）

节点间只传 **引用**，不传 `Media` 对象/二进制。

```java
/**
 * 多模态引用。
 *
 * @param url   资源地址；引用存在时必须有值（无 url 的条目视为非法，跳过 + warn）
 * @param mime  MIME 类型；**可选**，允许为 null，由 Resolver 补全
 * @param mediaId 可选：外置存储 id（上传场景优先用它，少依赖 url 猜类型）
 * @param type  可选：业务分类（image / file / audio…），仅用于展示与路由
 */
public record MediaRef(String url, String mime, String mediaId, String type) {}
```

#### 8.2.1 Agent 节点入参约定（定案：UI 填 mediaInputKey）

**配置方式**：Agent 节点新增一个字段 `mediaInputKey`，由用户在 UI 填写「本节点从哪个 state key 读多模态引用」。与现网 `inputKeys` 同层，**不做成带 enable 开关的资源类型**。

```java
// GenericAgentSpec 新增（与 inputKeys 同层，不放 ResourceBinding —— 它是 state key，不是资源 key）
String mediaInputKey;   // 例：multimodal_refs；留空表示本节点不处理多模态
```

放在 `GenericAgentSpec` 而非 `ResourceBinding` 的理由：它指向的是 **图内 state key**（数据来源），而 `ResourceBinding` 里的 keys 指向的是 **外部资源中心的资源 key**，两者语义不同。

**取值规则**：

- `mediaInputKey` 留空 → 本节点完全不处理多模态，纯文本调用
- 有值但 state 中该 key 不存在 / 为 null / 为空列表 → 同样纯文本调用，**不报错**  
  （与现网口径一致：`state.value(key).orElse(null)`，不因 null 拦节点）
- 单个引用内：**`url` 必须有**；**`mime` 能提供就提供，提供不了传 null**

| 情况 | Template 行为 |
|---|---|
| `mediaInputKey` 留空 | 纯文本调用，不挂任何 media |
| key 有值但 state 无该 key / null / 空列表 | 纯文本调用，debug 级日志 |
| 引用有 url、有 mime | 直接用该 mime 挂载 |
| 引用有 url、mime 为 null | 走 §8.2.2 回退补全 |
| 引用无 url | 跳过该条 + warn（不中断节点） |

**UI 与校验**：

- 属性面板（内联节点）/ 节点面板编辑器（注册式节点）新增输入框「多模态入参 key」，可留空
- 该 key 应一并计入 `inputKeys`，让连线可达性校验能发现「上游没人产出这个 key」
- 保存时给出**提示级**告警（不阻断）：填了 key 但全图无节点声明产出该 key
- 填错 key 时运行期只会静默走纯文本，因此上述校验提示是必要的

#### 8.2.1.1 State 中的存储形态（重要）

多模态引用在 state 中**必须以 Map / List&lt;Map&gt; 形态存放**，不要直接存 `MediaRef` 对象。

原因：`OverAllState` 经序列化 / checkpoint / 深拷贝后，自定义 record 很可能被还原成 `LinkedHashMap`，下游强转会抛 `ClassCastException`（同类问题见 [#1978](https://github.com/alibaba/spring-ai-alibaba/issues/1978)）。

约定：

```java
// 前置节点写入
state.put("multimodal_refs", List.of(
    Map.of("url", "https://.../a.png", "mime", "image/png"),
    Map.of("url", "https://.../b", "mediaId", "oss-123")   // mime 缺失，交后端补全
));
```

- Template 侧解析必须**容错**：同时接受 `List<Map>` 与 `List<MediaRef>`，也接受单个对象（自动包成单元素列表）和单个 url 字符串
- 该 key 的 `KeyStrategy` 统一注册为 **`REPLACE`**（每个节点整体替换，不做累积追加），避免多节点写入时引用越滚越多
- 若业务确实需要累积多轮附件，由业务节点自己合并后整体 `REPLACE` 写回，框架不提供 `APPEND` 语义

#### 8.2.2 mime 缺失时的补全顺序

前端常只有 url，且 url 可能没有文件后缀。补全由后端 `MediaRefResolver` 负责：

1. 引用里显式携带的 `mime`（有则优先）
2. `mediaId` 对应的存储元数据（上传时已落库的 contentType）
3. URL 路径扩展名映射（`.png → image/png`）
4. HTTP `HEAD`（或 GET 首包）的 `Content-Type`（剥离 `;charset=…`）
5. 下载后读文件头魔数（`FF D8` → jpeg、`%PDF` → pdf …）

**仍无法识别时（定案：保守跳过）**：不挂该 media，仅把 url 以文本形式附在 prompt 里（例如「附件：https://…（类型未识别）」），并打印 warn（含 graphId / nodeId / url / 失败原因）。  
理由：宁可模型看不到附件，也不要因错误 MIME 让视觉模型直接报错、带倒整次调用。  
可选开关：改为按 `application/octet-stream` 强挂，**默认关闭**。

#### 8.2.3 Resolver 接口

```java
/** 把 MediaRef 解析为可直接交给 Spring AI 的 Media（含 mime 补全） */
@FunctionalInterface
public interface MediaRefResolver {
    /**
     * @return 解析成功的 Media 列表；无法识别的条目按策略跳过（不抛异常中断节点）
     */
    List<Media> resolve(LlmRequestContext ctx, List<MediaRef> refs);
}
```

缺省实现：仅做第 1、3 级（显式 mime、URL 扩展名），HEAD / 魔数由业务实现按需接入（涉及网络与超时策略）。

#### 8.2.3.1 安全约束（必须实现，url 为外部输入）

`url` 来自前端，后端对其发起 `HEAD` / 下载相当于开放了一个 **SSRF** 入口，必须限制：

| 约束 | 要求 |
|---|---|
| 协议白名单 | 仅允许 `http` / `https`；拒绝 `file:` `ftp:` `gopher:` 等 |
| 地址黑名单 | 拒绝回环、私网、链路本地与云元数据地址（`127.0.0.0/8`、`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`::1` 等） |
| DNS 解析后复检 | 解析出的真实 IP 再校验一次，防 DNS rebinding |
| 重定向 | 限制跟随次数（建议 ≤ 3），**每跳都要重新做上述校验** |
| 域名白名单 | 可选但推荐：仅允许业务自有 CDN / OSS 域名，开关可配 |

失败一律按 §8.2.2 的保守策略处理（跳过 + warn），并记录被拒原因，便于排查是配置问题还是攻击探测。

#### 8.2.3.2 性能约束

| 约束 | 建议默认 |
|---|---|
| 连接 / 读取超时 | 各 2s，可配 |
| 单文件大小上限 | 10MB，超限跳过 + warn |
| 单次请求 media 条数上限 | 10 条，超出截断 + warn |
| 解析结果缓存 | 按 `url` 或 `mediaId` 缓存已识别的 mime，短 TTL（如 5 分钟），避免同一 url 在多节点重复 HEAD / 下载 |
| 下载时机 | 能只传 URL 给模型的适配器，就不要下载字节；只有必须读魔数时才下载首若干字节 |

#### 8.2.4 Template 接线（骨架内的固定步骤）

多模态在 Template 主流程里是一个**可跳过的前置步骤**，与 §10 骨架合并如下：

```java
// 1. 读 state：mediaInputKey 留空则整段跳过
List<MediaRef> refs = MediaRefs.readFrom(ctx.state(), spec.mediaInputKey());
// readFrom 内部容错：List<Map> / List<MediaRef> / 单对象 / 单 url 字符串 / null

// 2. 解析 + mime 补全（含安全与条数限制）；失败条目已按保守策略剔除
//    mediaResolver 为单例依赖，取自 LlmResolvers（§4.5.2）
List<Media> medias = refs.isEmpty()
        ? List.of()
        : resolvers.media().resolve(ctx, refs);

// 3. 组装 user 消息：medias 为空时行为与纯文本调用完全一致
ChatClient.ChatClientRequestSpec prompt = client.prompt().system(system)
        .user(u -> {
            u.text(req.userMessage());
            for (Media m : medias) {
                u.media(m.getMimeType(), m.getData());
            }
        });

// 4. 后续 stream / call 与无多模态时同一条链路
```

要点：

- **每次调模型都重新组装** media，绝不复用上游传来的 `UserMessage`（其 `media` 可能已在序列化边界丢失，见 §8.1）
- `medias` 为空是正常路径，不是异常，不打 warn（只打 debug）
- 被跳过的条目由 Resolver 返回附带说明，Template 把它们以文本形式追加到 user 文本末尾（「附件：url（类型未识别）」）

`LlmCallRequest` 相应新增两项：

```java
MediaRefResolver mediaResolver;   // 缺省实现只做显式 mime + 扩展名
String mediaInputKey;             // 来自 GenericAgentSpec，可空
```

#### 8.2.5 其它约定

- 上传场景：外置对象存储，state 只存 `mediaId` / `url`，**上传时即记录 mime**，从源头避免后续猜类型
- 对 `messages` 与多模态 refs 注册明确的 `KeyStrategy`（refs 用 `REPLACE`，见 §8.2.1.1），避免 resume 丢 key

### 8.3 节点间大结果与产物 URL 怎么投递？（定案 + 使用建议）

业务节点输出经常是：几 KB～几十 KB 文本，或 Skill 产出的 Excel / Word / PDF / 图片——最终往往只要**可下载 URL**。问题就变成：这些东西怎么交给下游节点？是写进 state「原样透传」，还是先精简再当下游入参？

#### 一句话结论

| 投递什么 | 放哪 | 何时精简 |
|---|---|---|
| **给下游 LLM 看的文字** | 仍走 state：`outputKey` → 下游 `inputKeys` / `{{var}}` | **进 prompt 时**由框架长度护栏截断（§4.4.2）；生产侧也可另写「摘要 key」 |
| **文件本体（xlsx/pdf/图二进制）** | **绝不进 state**；先上传外置存储 | state 只留 URL / mediaId（与 §8.2 MediaRef 同一原则） |
| **给前端下载 / 非 LLM 节点用的 URL** | state 里单独 key（或结构化 Map 的字段）透传 | 一般**不精简** URL；短字符串原样往后传 |

**默认做法：完整结构化结果进 state 透传；精简发生在「消费方拼 prompt」时，而不是生产方一写 state 就砍掉。**  
例外：生产方已经确定「后面永远只要 URL」时，上传后**只写 URL**，不要把几 MB 正文再塞进 state。

#### 原生 Graph：上一个节点怎么把结果写进 state？（机制 + 样例）

**一句话**：节点**不用**自己调 `state.put(...)`。实现 `NodeAction`（或 `AsyncNodeAction`），在 `apply` 里 **`return Map.of(key, value)`**；引擎按图上声明的 **`KeyStrategy`** 把这份 Map **合并**进 `OverAllState`，下一节点再用 `state.value(key)` 读。

```text
NodeAction.apply(state)
    → return { "reply_draft": "……", "report_url": "https://…" }
    → CompiledGraph 按 KeyStrategy（多为 REPLACE）合并进 OverAllState
    → 下一节点 apply 时 state 里已有这些 key
```

**原生最小样例**（与仓库单测 `SubgraphStateIsolationTest` 同口径）：

```java
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

// 构图时：每个会写入的 key 都要有 KeyStrategy（ace-graph-dsl 图定义里的 keyStrategies）
StateGraph graph = new StateGraph(() -> Map.of(
        "reply_draft", KeyStrategy.REPLACE,
        "report_url", KeyStrategy.REPLACE
));

// 节点 A：产出写回 = return 的 Map（不是手写 state.put）
graph.addNode("node_a", node_async((NodeAction) state -> {
    String text = "……几 KB 文本……";
    String url = "https://oss.example.com/a.xlsx";   // 文件只传 URL
    log.info("节点A写回 state: reply_draft.length={}, report_url={}", text.length(), url);
    return Map.of(
            "reply_draft", text,
            "report_url", url
    );
}));

// 节点 B：读上游写进 state 的 key
graph.addNode("node_b", node_async((NodeAction) state -> {
    String draft = state.value("reply_draft").map(Object::toString).orElse("");
    String url = state.value("report_url").map(Object::toString).orElse("");
    log.info("节点B读到上游: draftLen={}, url={}", draft.length(), url);
    return Map.of("final_out", "已处理, url=" + url);
}));

graph.addEdge(StateGraph.START, "node_a");
graph.addEdge("node_a", "node_b");
graph.addEdge("node_b", StateGraph.END);
```

**现网 GenericAgent 同一套路**（`GenericAgentNode#toAction` / `#execute`）：

```java
// toAction：从 state 按 inputKeys 取值 → execute → return { outputKey: 模型全文 }
return (OverAllState state) -> {
    Map<String, Object> variables = new LinkedHashMap<>();
    for (String key : spec.inputKeySet()) {
        variables.put(key, state.value(key).orElse(null));
    }
    return execute(variables, readRunId(state), readOverrides(state));
};

// execute 末尾：
Map<String, Object> result = new LinkedHashMap<>();
result.put(resolved.effectiveOutputKey(), response);  // 默认 key 常为 agent_result
return result;   // ← 引擎据此合并进 OverAllState
```

`DynamicGraphBuilder` 用 `node_async(genericAgent.toAction(...))` 挂到 `StateGraph`；图定义里的 `keyStrategies` 必须覆盖该 `outputKey`（`GraphValidator` 会检查缺失）。

| 要点 | 说明 |
|---|---|
| 写入口 | **`return Map`**，不是节点内 `state.put` |
| 合并规则 | 图级 `KeyStrategy`：`REPLACE`（覆盖）/ `APPEND`（追加）等 |
| 多字段 | 一次 return 多个 entry 即可（摘要 + URL 并列，见上节） |
| 读入口 | 下游 `state.value("key")` 或按 `inputKeys` 抽变量 |
| 与 §8.3 | 大文本/URL 都走同一写回机制；二进制仍不要放进 Map 值里 |

#### ace-graph-dsl 怎么给 state「指定 key」赋值？（定案）

**一句话结论**：**写到哪个 key，由节点配置里的 `outputKey`（UI / `GenericAgentSpec`）决定**，不是靠提示词里写「请输出到 xxx」。Agent 节点把**整段模型回复字符串**塞进这一个 key；**首期不做**「按提示词约定 JSON 字段自动拆成多个 state key」，也**未接** Spring AI Structured Output 自动映射多 key。

```text
UI 填 outputKey = "reply_draft"（空则默认 agent_result）
        │
        ▼
GenericAgentNode / StreamingLlmTemplate 调模型
        │
        ▼
return Map.of("reply_draft", 模型全文)   ← 只有这一对
        │
        ▼
引擎按 keyStrategies["reply_draft"]=REPLACE 合并进 OverAllState
```

| 方式 | 现网 / 方案是否支持 | 说明 |
|---|---|---|
| **配置 `outputKey`** | **支持（主路径）** | 属性面板填一个写回 key；与 `inputKeys` 对称 |
| **提示词规定输出长什么样** | **只影响正文内容** | 可要求模型吐 JSON/Markdown；但整段仍进**同一个** `outputKey`，框架**不解析** |
| **格式化 / Structured Output 自动拆多 key** | **首期不做** | 若要 `summary`+`url` 两个 state key，用下面「业务拆分」 |
| **业务 / 脚本节点 `return` 多 entry** | **支持** | 原生 `Map` 可一次写多个 key（§8.3 样例） |

**提示词能做什么、不能做什么**：

- **能**：约束模型「只输出 JSON」「字段含 summary / download_url」——方便人读或下游再解析。  
- **不能**：单靠提示词让框架把 `summary` 写进 `state.summary`、把 `url` 写进 `state.report_url`。框架看不到「字段名 → state key」的契约，除非你另写解析节点。

**若需要多 key，推荐三条落地路径（业务选）**：

```text
① Agent 只写一个 outputKey（整段 JSON 字符串）
    → 下一脚本/Java 节点：解析 JSON → return Map.of("summary",…, "report_url",…)

② 纯业务 NodeAction / Skill 工具回调里已算好字段
    → 直接 return Map.of("summary",…, "report_url",…)（不经过 Agent 单 key）

③ 两个 Agent 串联：节点A 产出摘要写 summary_key；节点B 只负责整理下载说明写 url_key
```

```java
// 路径①：解析节点示意（业务脚本 / Java 节点）
public Map<String, Object> apply(OverAllState state) {
    String raw = state.value("agent_result").map(Object::toString).orElse("");
    // 业务自己用 Jackson 解析；失败打 error 日志
    JsonNode n = objectMapper.readTree(raw);
    String summary = n.path("summary").asText("");
    String url = n.path("download_url").asText("");
    log.info("解析 Agent JSON 写回多 key: summaryLen={}, url={}", summary.length(), url);
    return Map.of(
            "report_summary", summary,
            "report_url", url
    );
}
```

**明确不做（首期）**：

- 不在 Template 里根据 prompt「猜测」要写哪些 key  
- 不把 Structured Output / `BeanOutputConverter` 默认接到多 key 写回（若以后做，单独立项：声明 schema ↔ state key 映射）  
- 不因提示词写了字段名就自动改 `outputKey`

**怎么验收**：

1. 面板把 `outputKey` 改成 `reply_draft` → 跑图后 state 有 `reply_draft`，默认不再出现（或不再依赖）`agent_result`  
2. prompt 要求输出 JSON，但未加解析节点 → state 里仍是**一个**字符串值，不会自动出现多个业务 key  
3. 加解析节点后 → `report_summary` / `report_url` 同时存在且 `keyStrategies` 已声明

#### 为什么用 state，而不是旁路塞给下一节点

图执行的标准总线就是 `OverAllState`：

```text
节点 A return Map.of(outputKey, value)
        → 合并进 OverAllState（KeyStrategy 多为 REPLACE）
        → 节点 B 经 inputKeys / mediaInputKey / 业务自读 state 拿到
```

框架**没有**第二套「节点间私信通道」。旁路（ThreadLocal、外部缓存只记 runId）会在扇出、HITL resume、多实例下丢，首期不推荐。

#### 推荐形态：一个结构化产出，按需多 key（或一 Map 多字段）

不要把「模型全文 + 文件 URL + 给下一模型的摘要」糊成一个巨大纯字符串（下游只能整段吃或整段砍）。建议业务约定一种可 JSON 化的结构（存 state 用 `Map`，理由同 §8.2.1.1）：

```json
{
  "summary": "给下游模型看的短结论（建议 < 2KB）",
  "detail_text": "可选：完整说明文字（几 KB～几十 KB）",
  "artifacts": [
    { "url": "https://oss.../report.xlsx", "mime": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "name": "对账表" },
    { "url": "https://oss.../chart.png", "mime": "image/png", "name": "趋势图" }
  ],
  "meta": { "row_count": 1200, "skill": "monitor-third-mcp" }
}
```

编排时拆 key 更清晰（也方便可达性校验）：

| state key 示例 | 谁写 | 谁读 | 典型用途 |
|---|---|---|---|
| `step_a_summary` | 节点 A | 下游 Agent 的 `inputKeys` / prompt | 进大模型，控制 token |
| `step_a_detail` | 节点 A | 只要全文的脚本节点 / 最终 API | 不进下一模型也可 |
| `step_a_artifacts` | 节点 A（Skill 上传后） | 输出节点、前端、或下游 `mediaInputKey` | 下载链接 / 再挂多模态 |

```java
// 业务节点 / Skill 工具回调结束后写回（示意）
Map<String, Object> artifact = Map.of(
        "url", uploadedUrl,
        "mime", "application/pdf",
        "name", "对账单.pdf");
log.info("节点产物已外置: nodeId={}, url={}, bytes={}", nodeId, uploadedUrl, byteSize);

return Map.of(
        "report_summary", summaryForNextLlm,           // 短
        "report_artifacts", List.of(artifact),         // 只 URL
        spec.effectiveOutputKey(), summaryForNextLlm  // 若该节点仍是 Agent，outputKey 建议放「给下游默认读的那份」
);
```

下游 Agent：`inputKeys` 只勾 `report_summary`，**不要**把 `report_artifacts` 整表 JSON 拼进 system（除非 prompt 明确要列下载链接）。  
下游若要把图片再喂给视觉模型：把 artifacts 里的图 URL 写成 §8.2 的 refs，填 `mediaInputKey`。

#### 「先精简再赋值」还是「原样进 state」？

| 策略 | 何时用 | 代价 |
|---|---|---|
| **A. 原样进 state，消费时再截**（默认） | 下游可能还要全文 / 审计 / 二次加工；只有某一个下游 Agent 嫌长 | state 变大；进 prompt 靠 §4.4.2 护栏；checkpoint 体积上升 |
| **B. 生产时就写摘要 + URL** | 已确定后面只要结论和下载链 | 丢了中间全文就再也没有（除非外置存了一份正文 URL） |
| **C. 全文外置，state 只留指针** | 单字段就要上百 KB，或要过 checkpoint / 跨进程 | 多一次对象存储；读全文要再拉 |

**使用建议（人话）**：

1. **几 KB～几十 KB 文本**：可以进 state（策略 A）。下游 Agent 用 `inputKeys` 引用；真拼进 prompt 时靠护栏，或业务另产 `*_summary`。  
2. **Excel/Word/PDF/大图**：上传后 state **只留 URL**（策略 B/C），与多模态同一原则——二进制不进 OverAllState。  
3. **不要**在「赋值给下一节点入参」前由框架自动摘要——框架不知道下游要细节还是要结论；摘要是**业务节点或单独摘要节点**的事。  
4. **Agent 的 `outputKey`**：写「下游默认会读的那份」（多为摘要或最终回复），大产物用并列 key，避免一个 `agent_result` 既当 prompt 燃料又塞满 URL 列表还难拆。  
5. **最终给用户下载**：输出节点 / Controller 从 state 读 `*_artifacts` 的 url 下发即可，不必再经大模型「复述」一遍二进制。

#### 和现有机制怎么对齐

| 机制 | 角色 |
|---|---|
| `outputKey` / `inputKeys` | 文本与结构化字段在图内的主投递路径 |
| §4.4.2 长度护栏 | **仅**限制「渲染进 prompt 的变量」，**不删** state 里的原值 |
| §8.2 `MediaRef` / `mediaInputKey` | 图片等还要再进模型时用；纯下载链不必进 media |
| Skill `load_skill` 正文 | 进的是**当前节点 messages**，不是自动写成下游 state；要投递下游须业务/工具显式 `return` 写 key |

#### 明确不做

- 框架**不**在节点边界自动「精简后再写入下游入参」  
- 框架**不**把文件字节塞进 state 或 SSE  
- 框架**不**规定必须用上面的 JSON 字段名——那是业务约定；框架只保证 state 透传与 prompt 护栏

#### 怎么验收

1. 节点 A 写出 30KB `detail` + 1 个 xlsx URL；节点 B 的 prompt 只引用 `summary` → B 的请求体无上下文不出现 30KB 全文，但 state 在 B 执行前仍能读到 `detail` 与 URL  
2. checkpoint / resume 后 URL 仍在；从未出现「state 里躺着整个 xlsx 字节数组」  
3. 故意把 30KB 配进 B 的 `{{detail}}` → 触发 §4.4.2 截断 warn，节点不静默撑爆上下文

### 8.4 ChatClient 入参位置（重要）

多模态 **不挂在** `ChatClient.Builder` 上，而挂在 **某次请求的 `UserMessage.media`**：

```text
ChatClient.Builder  →  模型 / tools / defaultSystem 等客户端配置
ChatClient.prompt().user(...).media(...)  →  本次多模态输入
```

官方约定：media 仅对 User 消息有意义。  
Excel 等非视觉文件多数模型不能直接当 media 理解，宜前置解析为文本再入 prompt。

到第三个节点时：若依赖上游传来的 `UserMessage.media`，**有可能已经没有 Media**；应持有 URL/引用并在本节点重新 `.media(...)`。

---

## 9. 流式响应类型（BIZ / OUTPUT / 扩展）

### 9.0 首要原则：协议归业务，框架只给入口（定案）

**ace-graph-dsl 不定义、不强制、不内置任何 SSE 字段协议。** 业务项目往往已有一套成熟且被前端/网关/APP 依赖的流式输出格式，框架无权也无必要替它决定 `thinking` / `isEnd` / `phase` 这类字段。

据此划分职责：

| 职责 | 归属 |
|---|---|
| 决定 SSE 每帧长什么样（字段名、层级、编码） | **业务项目** |
| 提供挂载入口，让业务已有协议实现能接进来 | 框架 |
| 保证「当前片段属于哪个节点、哪个流式响应类型、是否末帧」这些**事实**准确送达业务（仅 Java 侧，不写入 SSE） | 框架 |
| 提供 kind KEY 目录给 UI 下拉，保证设计期与运行期同一套 KEY | 框架 |

框架**唯一的新增职责**是把 `streamResponseKind` 这个标签从节点配置无损透传到业务的格式化实现手里。**怎么用这个标签，是业务的事。**

> 这一条同时消解了早期方案「框架内置 BIZ/OUTPUT Codec 带 `thinking:true`」的设计——那实质上是框架在定协议。已废弃，见 §9.6.5。

### 9.1 动机

每个节点的流式输出格式可能有细微差异（过程流 vs 终稿流 vs 工具调用流）。需要：

- 节点在设计期声明**流式响应类型 KEY**
- 运行期该 KEY 能被业务的格式化实现读到，从而按类型输出不同形状
- 框架预置两个**语义占位** KEY：`BIZ`（业务处理）、`OUTPUT`（结果输出）
- 业务可扩展任意自定义 KEY

**注意**：`BIZ` / `OUTPUT` 只是**两个字符串 KEY 与它们的中文标签**，框架不附带任何字段实现。选了 `BIZ` 不会自动获得 `thinking:true`——除非业务在自己的 Formatter 里这么写。

| Kind | 语义（仅约定俗成） | 框架内置的字段行为 |
|---|---|---|
| `BIZ` | 业务处理过程流 | **无**。由业务 Formatter 决定 |
| `OUTPUT` | 结果输出流 | **无**。由业务 Formatter 决定 |
| 自定义（如 `TOOL`、`AUDIT`） | 业务自定 | **无**。由业务 Formatter 决定 |

### 9.2 与现有组件正交关系

| 概念 | 含义 | 谁定义值 |
|---|---|---|
| `OutputType`（框架已有） | 帧生命周期：STREAMING / FINISHED | 框架 |
| `StreamResponseKind`（本方案新增） | 业务语义通道标签：BIZ / OUTPUT / 自定义 | 设计期由 UI 选，运行期由框架透传 |
| `StreamingChunkFormatter`（框架已有） | **SSE 协议唯一挂载入口**，语义与现网完全一致 | 业务实现 |

### 9.3 SPI 与核心类型

框架侧只新增「标签类型」与「上下文字段」，**不新增任何协议编码接口**。

#### 9.3.1 标签类型

```java
/**
 * 流式响应类型标签。仅承载 KEY 字符串，不含任何格式语义。
 *
 * <p>BIZ / OUTPUT 为框架预置的两个语义占位 KEY，不附带字段实现；
 * 业务可通过配置扩展任意 KEY。</p>
 */
public record StreamResponseKind(String code) {

    public static final String BIZ_CODE = "BIZ";
    public static final String OUTPUT_CODE = "OUTPUT";

    public static final StreamResponseKind BIZ = new StreamResponseKind(BIZ_CODE);
    public static final StreamResponseKind OUTPUT = new StreamResponseKind(OUTPUT_CODE);

    public StreamResponseKind {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("streamResponseKind code 不能为空");
        }
    }

    /** 归一化：去空白 + 转大写。空值由调用方按 §9.6.4 回落，不在此处写死默认值。 */
    public static StreamResponseKind of(String code) {
        return new StreamResponseKind(code.trim().toUpperCase(Locale.ROOT));
    }
}
```

#### 9.3.2 `TokenChunk` 增加标签

```java
public record TokenChunk(
    String nodeId,
    String token,
    OutputType outputType,
    StreamResponseKind responseKind,   // 新增：可空，空则按 §9.6.4 回落
    boolean last
) {
    /** 向后兼容：不带 kind 的旧构造 */
    public TokenChunk(String nodeId, String token, OutputType outputType, boolean last) {
        this(nodeId, token, outputType, null, last);
    }
}
```

#### 9.3.3 `StreamingContext` 暴露标签（框架的唯一新增职责）

```java
public class StreamingContext {

    // ... 现有字段不变 ...
    private final StreamResponseKind responseKind;

    /**
     * 当前片段的流式响应类型。
     *
     * <p>取值顺序见 §9.6.4：token 自带 → 节点 Spec 配置 → 空。
     * 框架不对该值做任何格式化解释，业务 {@link StreamingChunkFormatter} 自行决定如何使用。</p>
     *
     * @return 可能为 {@code null}（非 Agent 节点片段、框架内置片段）
     */
    public StreamResponseKind getResponseKind() {
        return responseKind;
    }

    /** 便捷判定，避免业务写 null 检查 */
    public boolean isKind(String code) {
        return responseKind != null && responseKind.code().equals(code);
    }
}
```

**到此为止，框架的流式类型能力就结束了。** 没有 Codec、没有 Registry、没有内置字段。

#### 9.3.4 业务挂载已有协议：直接实现现网入口

业务项目已有的格式协议实现，只要包一层 `StreamingChunkFormatter` 即可接入，**不需要迁移到框架的任何新接口**：

```java
/**
 * 业务项目已有协议的挂载示例。
 * MyStreamProtocol 是业务侧已存在的成熟协议组件，框架完全不感知其内部结构。
 */
@Bean
StreamingChunkFormatter myFormatter(MyStreamProtocol protocol) {
    return ctx -> {
        String kind = ctx.getResponseKind() != null
                ? ctx.getResponseKind().code()
                : MyStreamProtocol.KIND_UNKNOWN;
        // 复用业务已有协议构造器，字段形状完全由业务决定
        return protocol.buildChunk(kind, ctx.getNodeId(), textOf(ctx), ctx.isLast());
    };
}
```

`resolveFormatter()` 的现网优先级**保持不变**，业务 Bean 一如既往地整颗生效（§9.6）。

#### 9.3.5 可选便利基类（不用也完全可以）

若业务希望按 kind 分派到不同方法，框架提供一个**纯语法糖**抽象类。它只做 `switch`，不产出任何字段：

```java
/**
 * 按 kind 分派的便利基类（可选）。
 *
 * <p>框架不提供任何分支的默认实现，全部为抽象方法 / 由业务覆盖，
 * 因此本类不构成协议约束。业务不想用时，直接实现 {@link StreamingChunkFormatter} 即可。</p>
 */
public abstract class KindDispatchingChunkFormatter implements StreamingChunkFormatter {

    @Override
    public final Object format(StreamingContext ctx) {
        StreamResponseKind kind = ctx.getResponseKind();
        // 无 kind 的片段（框架内置流式节点、HITL 中断等）走 onOther
        return kind == null ? onOther(ctx, null) : dispatch(ctx, kind);
    }

    private Object dispatch(StreamingContext ctx, StreamResponseKind kind) {
        return switch (kind.code()) {
            case StreamResponseKind.BIZ_CODE -> onBiz(ctx);
            case StreamResponseKind.OUTPUT_CODE -> onOutput(ctx);
            default -> onOther(ctx, kind);
        };
    }

    protected abstract Object onBiz(StreamingContext ctx);

    protected abstract Object onOutput(StreamingContext ctx);

    /** 自定义 kind 与无 kind 片段；kind 可为 null */
    protected abstract Object onOther(StreamingContext ctx, StreamResponseKind kind);
}
```

#### 9.3.6 节点 Spec

```java
String streamResponseKind; // 必填语义：kind 目录（§9.4）中的 KEY，如 "BIZ" | "OUTPUT" | "TOOL"
```

设计器侧每个节点绑定**一个主 kind**。运行期中途切换（先 BIZ 过程、再 OUTPUT 终稿）见 §13.1 B3，首期不做。

### 9.4 已实现流式响应类型 KEY 加载接口（UI 必用）

定义 Agent 节点时，UI **必须**能选择当前节点的流式响应方式。选项不能写死在前端，否则业务扩展的 KEY 无法出现在下拉框、且前后端 KEY 容易漂移。

由于框架不再持有 Codec，kind 目录的**来源改为「配置文件 + 可选 Bean 覆盖」**——这也更贴合「协议归业务」：业务有哪些 kind，业务自己声明。

#### 9.4.1 HTTP API

```text
GET /api/stream-response-kinds
```

可选查询参数（便于多图/多环境过滤，首期可忽略）：

```text
GET /api/stream-response-kinds?graphId={graphId}
```

响应示例：

```json
{
  "items": [
    {
      "key": "BIZ",
      "label": "业务处理",
      "description": "过程/中间推理类流式输出",
      "builtin": true,
      "order": 10
    },
    {
      "key": "OUTPUT",
      "label": "结果输出",
      "description": "对用户可见的终稿流式输出",
      "builtin": true,
      "order": 20
    },
    {
      "key": "TOOL",
      "label": "工具调用过程",
      "description": "业务扩展：工具调用片段",
      "builtin": false,
      "order": 30
    }
  ]
}
```

约定：

| 字段 | 说明 |
|---|---|
| `key` | 与 `agentSpec.streamResponseKind` 及业务 Formatter 内的判定值 **完全一致** |
| `label` | UI 展示名 |
| `description` | 悬停/副文案，帮助选型 |
| `builtin` | 是否框架预置 KEY（BIZ/OUTPUT） |
| `order` | 排序权重；**服务端已按此排好序返回**，前端直接用数组顺序即可，无需二次排序 |

> **已移除 `defaultSelected`**（原 B8）：默认值语义只有一个来源——**列表首位**。同时存在 `defaultSelected` 会让前后端各解读一套（若两者冲突听谁的？），且它与服务端空值回落（§9.7.4 取 `items[0]`）无法保持一致。删除后「默认 = 首位」是全链路唯一规则。

**兜底**：业务未做任何配置时，接口至少返回预置 `BIZ`、`OUTPUT`，保证 UI 可配置。  
**排序**：`order` 升序、同 order 按 key 字典序；期望的默认类型排在 `items[0]`，与前端「默认 KEY = 首位」及服务端空值回落（§13.1 B1）用**同一比较器**。

#### 9.4.2 后端实现要点

```java
/** 供 UI / OpenAPI 使用的流式类型目录项 */
public record StreamResponseKindItem(
    String key,
    String label,
    String description,
    boolean builtin,
    int order          // 排序权重；无 defaultSelected，默认值语义唯一来源是「列表首位」
) {}

/**
 * 流式类型目录 SPI。
 *
 * <p>默认实现读配置 {@code ace.graph.dsl.streaming.response-kinds}；
 * 业务需要动态目录（如按租户 / 从注册中心取）时，提供自己的 @Bean 覆盖即可。</p>
 */
public interface StreamResponseKindCatalog {
    List<StreamResponseKindItem> list(String graphId); // graphId 可空
}
```

**默认实现：配置驱动**

```yaml
ace:
  graph:
    dsl:
      streaming:
        # 不配置时等价于下面这两项（仅 KEY 与标签，无任何字段协议）
        response-kinds:
          - key: BIZ
            label: 业务处理
            description: 过程/中间推理类流式输出
            order: 10
          - key: OUTPUT
            label: 结果输出
            description: 对用户可见的终稿流式输出
            order: 20
          # 业务扩展：加一项即出现在 UI 下拉，无需改代码
          - key: TOOL
            label: 工具调用过程
            order: 30
```

```java
/** 配置驱动的默认目录实现；业务 @Bean 可整体覆盖 */
public class ConfigStreamResponseKindCatalog implements StreamResponseKindCatalog {

    private static final Logger log =
            LoggerFactory.getLogger(ConfigStreamResponseKindCatalog.class);

    /** order 升序 + key 字典序：UI 首位与服务端空值回落共用此序 */
    private static final Comparator<StreamResponseKindItem> ORDER =
            Comparator.comparingInt(StreamResponseKindItem::order)
                      .thenComparing(StreamResponseKindItem::key);

    private final List<StreamResponseKindItem> items;

    public ConfigStreamResponseKindCatalog(StreamingProperties props) {
        // 空配置回落到预置 BIZ/OUTPUT，保证 UI 永不出现空下拉
        List<StreamResponseKindItem> resolved =
                (props.getResponseKinds() == null || props.getResponseKinds().isEmpty())
                        ? builtinDefaults()
                        : normalize(props.getResponseKinds());
        this.items = List.copyOf(resolved);
        log.info("流式响应类型目录初始化完成: size={}, keys={}, 默认首位={}",
                items.size(), items.stream().map(StreamResponseKindItem::key).toList(),
                items.get(0).key());
    }

    @Override
    public List<StreamResponseKindItem> list(String graphId) {
        return items; // graphId 首期忽略，见 §13.1 C3
    }

    /** 去重（按 key 大写归一，后者覆盖前者）+ 排序 */
    private static List<StreamResponseKindItem> normalize(List<...> raw) { /* ... */ }
}
```

Controller 示意：

```java
@GetMapping("/api/stream-response-kinds")
public Map<String, Object> listKinds(@RequestParam(required = false) String graphId) {
    return Map.of("items", streamResponseKindCatalog.list(graphId));
}
```

**校验要点**：`key` 必须非空、去空白转大写后唯一；重复 key 记 warn 并保留最后一项，避免 UI 出现重复选项。

#### 9.4.3 UI 配置逻辑（必选 KEY + 默认首位）

**硬性规则**：

1. Agent 节点 **必须**带有非空的 `streamResponseKind`（节点流式响应类型 KEY）
2. 前端默认 KEY = kinds 接口返回列表的 **第一项** `items[0].key`
3. 用户忘记选择 / 未改下拉：仍使用该默认 KEY 写入 Spec 并随图保存，**禁止空字符串 / null**

**交互步骤**：

1. 打开 Agent 节点属性面板 → 请求 `GET /api/stream-response-kinds`
2. 渲染「流式响应方式」下拉（单选）；选项 = `items[].key`，展示 `label`
3. **初始化赋值**（满足其一即执行）：
   - 新建节点，或
   - 已有节点 `streamResponseKind` 为空 / 不在当前列表中  
   → 立即设置 `agentSpec.streamResponseKind = items[0].key`（前端默认 KEY）
4. 用户改选其他项 → 更新为对应 `key`
5. 接口失败：本地兜底列表仍保证有序，例如 `[{key:BIZ},{key:OUTPUT}]`，默认仍取首位 `BIZ`
6. **不允许**提交空 KEY；保存前若发现空值，自动回填 `items[0].key`（或兜底首位）后再保存
7. 不鼓励自由文本乱填未注册 key；若保留高级输入，保存前校验 ∈ 列表，否则回退默认首位并提示

伪代码：

```javascript
const items = await fetchStreamResponseKinds() // 失败则用 [{ key: 'BIZ' }, { key: 'OUTPUT' }]
const defaultKey = items[0].key

if (!agentSpec.streamResponseKind || !items.some(i => i.key === agentSpec.streamResponseKind)) {
  agentSpec.streamResponseKind = defaultKey
}

// 保存前再次兜底
function ensureStreamKind() {
  if (!agentSpec.streamResponseKind) {
    agentSpec.streamResponseKind = defaultKey
  }
}
```

#### 9.4.4 配置期数据流

```mermaid
sequenceDiagram
  participant UI as ace-graph-dsl-ui
  participant API as GET /api/stream-response-kinds
  participant Cat as StreamResponseKindCatalog
  participant Cfg as 配置 / 业务 Bean

  UI->>API: 打开 Agent 属性面板
  API->>Cat: list(graphId)
  Cat->>Cfg: 读 response-kinds（空则用预置）
  Cfg-->>Cat: BIZ, OUTPUT, TOOL, ...
  Cat-->>UI: items[{key,label,...}] 首位=默认 KEY
  UI->>UI: 未选择时 streamResponseKind=items[0].key
  UI->>UI: 用户可改选其他 key
  Note over UI: 保存图定义（KEY 必非空）
```

### 9.5 运行期流式类型数据流

```mermaid
sequenceDiagram
  participant Node as Agent节点
  participant Tpl as StreamingLlmTemplate
  participant Bridge as GraphStreamBridge
  participant Ctrl as GraphExecutionController
  participant Fmt as 业务 StreamingChunkFormatter
  participant FE as 前端

  Note over Node: Spec.streamResponseKind=BIZ
  Tpl->>Bridge: emit(TokenChunk{token, kind=BIZ})
  Bridge->>Ctrl: TokenChunk
  Ctrl->>Ctrl: ofToken() 填充 responseKind
  Ctrl->>Fmt: format(StreamingContext{kind=BIZ})
  Note over Fmt: 业务自有协议决定字段形状<br/>框架不参与
  Fmt-->>FE: 业务协议 payload
```

框架在这条链路上只做一件事：**保证 `StreamingContext.getResponseKind()` 拿到的是该节点设计期配的那个 KEY**。payload 长什么样，从头到尾没有框架代码参与。

### 9.6 挂载入口定案：`StreamingChunkFormatter` 唯一且不变

#### 9.6.1 定案

| 决策 | 内容 |
|---|---|
| 唯一挂载入口 | **`StreamingChunkFormatter`**（现网已有接口，语义、优先级、注释导向全部不变） |
| 是否 deprecated | **否**。它是本方案推荐的正式扩展点 |
| 框架内置协议实现 | **无**。不提供带 `thinking` / `isEnd` 等字段的 kind 实现 |
| 默认输出是否带 kind 字段 | **不带，且无开关**。框架代码零处产出 kind 相关 SSE 字段（§9.6.3） |
| `GraphExecutionController` | **现有端点零改动**（`/stream`、`/resume`），`resolveFormatter()` 逻辑不变；另新增 `/debug/stream` 调试端点（§9.6.7） |
| 框架新增 | 仅 `StreamingContext.getResponseKind()` 与 `TokenChunk.responseKind`（Java 侧透传标签） |

#### 9.6.2 控制器保持原样

```java
/**
 * 解析流式格式化器（优先级，与现网完全一致）：
 *   1. 自定义 StreamingChunkFormatter Bean → 业务定制格式
 *   2. 自定义旧版 GraphExecutionEventAdapter → 委派（向后兼容）
 *   3. DefaultStreamingChunkFormatter（原生默认格式）
 */
private StreamingChunkFormatter resolveFormatter() {
    StreamingChunkFormatter custom = formatterProvider.getIfAvailable();
    if (custom != null) {
        return custom;      // ← 业务协议整颗生效，框架不再插手
    }
    if (!(eventAdapter instanceof DefaultGraphExecutionEventAdapter)) {
        return new AdapterDelegatingStreamingChunkFormatter(eventAdapter);
    }
    return new DefaultStreamingChunkFormatter();
}
```

建议**仅补一条启动日志**（不改逻辑），便于排查「哪个格式化器在生效」：

```java
// 此处 list(null) 是有意为之：启动期无图上下文，打印全量目录即可；
// 运行/编译期的归一化必须传真实 graphId（§9.7.6）
log.info("流式格式化器生效: {}（kind 目录={}）",
        formatter.getClass().getName(), kindCatalog.list(null).stream()
                .map(StreamResponseKindItem::key).toList());
```

#### 9.6.3 兜底行为（业务未提供 Formatter 时）

沿用现网 `DefaultStreamingChunkFormatter`，输出形状**逐字节保持不变**（`type:chunk|node|interrupt`）。

**定案：默认输出不附带 `streamKind`，也不提供任何开关。** 框架代码路径上零处产出 kind 相关字段。

理由：只要框架在默认 payload 里写了 `streamKind`，它就成了一个事实上的协议字段——前端会开始依赖它，之后就改不掉了，等于框架又悄悄定了半个协议。加开关也不解决问题，反而多一个需要解释的配置项。

由此得到一条干净的边界：

| | 默认（无业务 Formatter） | 业务提供 Formatter |
|---|---|---|
| SSE 字段来源 | 现网 `DefaultStreamingChunkFormatter`，与升级前完全一致 | 100% 业务决定 |
| kind 是否出现在 payload | **不出现** | 业务自己决定是否输出、叫什么名字 |
| kind 是否可被读到 | 可以，但只在 Java 侧：`ctx.getResponseKind()` | 同 |

也就是说，**kind 是一个只对 Java 扩展点可见的标签，不是一个线上协议字段**。业务想让它出现在 SSE 里，就实现自己的 Formatter——这本来就是唯一正确的入口。

> 副作用（可接受）：未实现 Formatter 的项目在浏览器里看不到 kind，调试时需要看服务端日志或临时挂一个 Formatter。相比「框架私自定义协议字段」，这个代价明显更小。

#### 9.6.4 kind 解析回落（闭环 B6）

`StreamingContext` 构造时按顺序取值，业务侧因此拿到尽可能准确的 kind：

| 顺序 | 来源 | 适用片段 |
|---|---|---|
| 1 | `TokenChunk.responseKind()` | 桥接 token（最精确，Agent 节点） |
| 2 | 该 `nodeId` 的节点 Spec `streamResponseKind`（编译期收集 `nodeId → kind` 映射） | `ofNode()` 路径的 Agent 节点片段 |
| 3 | `null` | 框架内置流式节点、普通节点输出、HITL 中断等非 Agent 片段 |

第 3 档**保持 `null` 而不是造一个 `FRAMEWORK` 常量**——因为一旦框架定义了 `FRAMEWORK` 的输出形状，就又回到「框架定协议」。业务在自己的 Formatter 里判 `null` 即可（`KindDispatchingChunkFormatter` 会把 `null` 路由到 `onOther`）。

#### 9.6.5 与早期 Codec 方案的关系（已废弃）

早期方案引入 `StreamChunkCodec` + `StreamChunkCodecRegistry` + `KindAwareStreamingChunkFormatter`，并内置 BIZ/OUTPUT Codec。**本次定案将其整体废弃**，原因：

1. **越界**：内置 Codec 写死 `thinking:true` / `isEnd:true`，等于框架替业务定协议
2. **迁移成本**：要求存量项目把已成熟的 Formatter 拆成多个 Codec，收益为零
3. **自相矛盾**：「不动控制器」与「Codec 优先」严格执行时互斥——不改 `resolveFormatter()` 则业务 Formatter 永远短路 Codec，改了则破坏「不动控制器」

废弃后这组矛盾**自动消失**：只有一个入口、一条优先级链、控制器零改动，不存在两套实现争夺格式化权。

| 早期概念 | 现方案对应物 |
|---|---|
| `StreamChunkCodec` | 删除。业务实现 `StreamingChunkFormatter` |
| `StreamChunkCodecRegistry` / `hasBusinessCodec()` | 删除。无需冲突判定 |
| `KindAwareStreamingChunkFormatter` | 降级为**可选**便利基类 `KindDispatchingChunkFormatter`（§9.3.5） |
| 内置 `FRAMEWORK` kind | 删除。第 3 档回落为 `null`（§9.6.4） |
| Codec 枚举驱动 kinds API | 改为配置驱动 `StreamResponseKindCatalog`（§9.4.2） |

#### 9.6.6 业务接入清单

| 业务现状 | 需要做什么 |
|---|---|
| 已有 `@Bean StreamingChunkFormatter` | **什么都不用改**，继续生效。想用 kind 就在实现里读 `ctx.getResponseKind()` |
| 已有自己的协议组件，但未接入框架 | 写一个 3 行的 `@Bean StreamingChunkFormatter` 委派给它（§9.3.4） |
| 无自定义协议 | 不用管，走默认格式 |
| 需要按 kind 分派 | 可选继承 `KindDispatchingChunkFormatter`，或自己 `switch` |

**升级零破坏**：不新增必填 Bean、不改控制器、不改默认输出形状、`TokenChunk` 旧构造器保留。

### 9.6.7 调试端点：ace-graph-dsl-ui 使用框架标准格式（B7 定案）

#### 定案

`ace-graph-dsl-ui` 的调试界面**不消费业务协议**，改用框架自有的标准 SSE 格式。理由：ui 是通用产品，若跟随业务协议，则每接一个业务方都要在前端做一次协议适配，不可持续。

实现方式为**新增独立调试端点**，而非在 `/stream` 内按「是否调试」分支——避免调试逻辑侵入生产链路：

| 端点 | 使用者 | 格式 |
|---|---|---|
| `POST /execution/{graphId}/stream` | 业务生产 | 业务 `StreamingChunkFormatter`（**行为完全不变**） |
| `POST /execution/{graphId}/debug/stream` | ace-graph-dsl-ui 调试台 | **框架内置 `DebugStreamingChunkFormatter`，强制忽略业务 Bean** |

```java
/** 调试流式执行：固定使用框架标准格式，不受业务 Formatter 影响 */
@PostMapping(value = "/{graphId}/debug/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter debugStream(@PathVariable String graphId,
                              @RequestBody(required = false) ExecutionRequest req) {
    String threadId = resolveThreadId(req);
    CompiledGraph graph = runtime.get(graphId);
    log.info("调试流式执行: graphId={}, threadId={}, 使用框架标准格式", graphId, threadId);
    // 注意：此处刻意不调用 resolveFormatter()，避免业务 Bean 介入
    return toSse(graph.stream(inputs(req, threadId), buildConfig(threadId)),
            graphId, threadId, new DebugStreamingChunkFormatter());
}
```

#### 调试格式可以携带 kind（与 §9.6.3 不矛盾）

§9.6.3 定的是「**默认** Formatter 不附带 `streamKind`」，因为那条链路可能正被业务生产使用，加字段等于框架私自定协议。

调试端点不同：它是**框架自有端点、服务框架自有 UI**，其格式就是框架的内部协议，可以自由定义。因此 `DebugStreamingChunkFormatter` **应当**输出调试所需的完整信息：

```json
{
  "type": "chunk",
  "nodeId": "agent_translate",
  "streamKind": "BIZ",
  "chunk": "正在查询订单",
  "isEnd": false,
  "seq": 12,
  "ts": 1757300000123
}
```

| 字段 | 用途 |
|---|---|
| `type` | `chunk` / `node` / `interrupt` / **`resource_miss`**（仅调试；§7.4） |
| `nodeId` | 调试台按节点分栏 |
| `streamKind` | 调试台标注该片段的响应类型（**调试台能显示 kind 的关键**） |
| `seq` / `ts` | 序号与时间戳，便于排查乱序、卡顿、丢帧 |
| `resourceType` / `key` / `message` / `fatal` | 仅 `resource_miss`：无法加载的资源类型、key、原因、是否导致节点失败 |

该格式属于**框架契约**，需版本化并写入接口文档，`ace-graph-dsl-ui` 可稳定依赖。

#### 已知风险与应对

**调试所见 ≠ 生产所下发**。业务协议只在 `/stream` 生效，调试台看不到，因此调试端点无法验证业务 Formatter 自身的 bug。

应对：

1. UI 调试面板需**明示提示**：「当前为框架调试格式；生产实际下发格式由业务 `StreamingChunkFormatter` 决定」
2. 业务验证自有协议时，直接压 `/stream` 抓包（原有方式不变）
3. 调试端点鉴权与图编辑权限对齐（并入 C4），**禁止对生产终端用户开放**

#### 对「控制器零改动」结论的修正

§9.6.1 原表述为 `GraphExecutionController` **零改动**。现修正为：**现有端点（`/stream`、`/resume`）零改动**，另新增 `/debug/stream` 端点。存量行为不受影响，`resolveFormatter()` 逻辑仍不变。

### 9.7 空 KEY / 无效 KEY 归一化（定案：编译期 normalize + 运行期兜底）

#### 9.7.1 问题：同一个「未指定」有两套默认值

原设计中，设计期默认值来自 **Catalog 返回顺序的首位**（UI 取 `items[0].key`），运行期默认值却是**代码写死的 `BIZ`**（`StreamResponseKind.of(null)`）。两者无任何关联。

空值一定会出现，因此这不是纸面问题：

| 空值来源 | 说明 |
|---|---|
| **存量图** | `streamResponseKind` 是新增字段，所有已发布图的 Agent 节点都没有 |
| 非 UI 写入 | OpenAPI / 脚本 / DSL 导入 / 直接改库 |
| 前端兜底失效 | kinds 接口失败时前端用本地兜底列表，可能与服务端目录不一致 |
| 目录变更 | 业务下线了某个 key，老图里存的还是旧 key（**无效值**，与空值同等处理） |

危害示例：某业务目录只有 `OUTPUT` / `TOOL`（无 `BIZ` 概念），旧图节点字段为空 → 运行期解析成 `BIZ` → 业务 Formatter 无 `BIZ` 分支 → 落 `default`。**输出格式与界面配置不符，且全程不报错**，排查时界面显示一切正常。

#### 9.7.2 为什么不能像 `outputKey` 那样在 record 构造器里兜底

现网 `GenericAgentSpec` 对 `outputKey` 的兜底放在紧凑构造器：

```java
public GenericAgentSpec {
    if (outputKey == null || outputKey.isBlank()) {
        outputKey = DEFAULT_OUTPUT_KEY;   // 静态常量，构造期即可确定
    }
}
```

`streamResponseKind` **不能**照搬：它的默认值是 Catalog 排序首位，**取决于运行时配置**，record 构造器里既拿不到 Bean，也不应该依赖 Spring 上下文（该 record 还用于反序列化与落库脱敏）。

这正是必须把归一化外移到编译期的原因。

#### 9.7.3 第一层：编译期 normalize（一次性修好存量图）

落点选在 `DynamicGraphBuilder#resolveGenericAgent(GraphDefinition, NodeRef)`——它是**内联通道与注册式通道的唯一汇聚点**，两条通道都会经过，无需改两处：

```java
private GenericAgentNode resolveGenericAgent(GraphDefinition def, NodeRef ref) throws GraphStateException {
    if (ref.agentSpec() != null) {
        // 通道一 · 内联：归一化后再装配
        GenericAgentSpec spec = normalizeStreamKind(def.graphId(), ref.nodeId(), ref.agentSpec());
        return new GenericAgentNode(ref.nodeId(), def.graphId(), spec, applicationContext);
    }
    // ... 通道二 · 注册式：取出 registeredAgent 后同样归一化 ...
}

/**
 * 归一化节点流式响应类型：空 KEY / 不在目录内的 KEY 一律回落到 Catalog 排序首位。
 *
 * <p>与 UI 默认值同源（同一比较器、同一目录），杜绝「设计期看到 A、运行期跑成 B」。
 * 编译期只做一次，避免每帧查目录。</p>
 */
private GenericAgentSpec normalizeStreamKind(String graphId, String nodeId, GenericAgentSpec spec) {
    String raw = spec.streamResponseKind();
    // 必须传 graphId：目录可按图/租户过滤，缺省会与 UI 首位不一致（§9.7.6）
    String resolved = kindResolver.resolveOrDefault(graphId, raw).code();
    if (resolved.equals(raw)) {
        return spec;
    }
    log.info("节点 {} 的 streamResponseKind 归一化: {} -> {}",
            nodeId, raw == null ? "<空>" : raw, resolved);
    return spec.withStreamResponseKind(resolved);
}
```

`GenericAgentSpec` 增加 wither，沿用现网 `withResolvedApiKey` / `withOverride` 的惯例：

```java
/** 新增：用归一化后的流式响应类型生成新副本 */
public GenericAgentSpec withStreamResponseKind(String kind) { /* ... */ }
```

**顺带闭环 B6**：同一处循环里收集 `nodeId → streamResponseKind` 映射并交给流式层，供 `StreamingContext.ofNode()` 路径回落（§9.6.4 第 2 档）。一次遍历同时解决两个缺口。

#### 9.7.4 第二层：运行期兜底（最后防线）

归一化集中在一个方法里，编译期与运行期**共用同一份实现**：

```java
/**
 * 空值 / 不在目录内的 KEY 一律回落到目录首位，与 UI 默认同源。
 *
 * <p>必须携带 graphId：目录允许按图/租户过滤，若此处写死 null，业务覆盖
 * Catalog 后运行期取到的首位会与 UI 看到的首位不一致（§9.7.6 / C3）。</p>
 */
public StreamResponseKind resolveOrDefault(String graphId, String raw) {
    List<StreamResponseKindItem> items = catalog.list(graphId);   // 已按 ORDER 排序
    String fallback = items.get(0).key();
    if (raw == null || raw.isBlank()) {
        log.warn("图 {} 节点未配置 streamResponseKind，回落到目录首位: {}", graphId, fallback);
        return StreamResponseKind.of(fallback);
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    boolean known = items.stream().anyMatch(i -> i.key().equals(normalized));
    if (!known) {
        log.warn("图 {} 的 streamResponseKind={} 不在目录内，回落到首位: {}（目录={}）",
                graphId, normalized, fallback,
                items.stream().map(StreamResponseKindItem::key).toList());
        return StreamResponseKind.of(fallback);
    }
    return StreamResponseKind.of(normalized);
}
```

配套：`StreamResponseKind.of()` 对空值 **fail fast**（§9.3.1），杜绝「随手一调就悄悄拿到 BIZ」。空值处理只有 `resolveOrDefault` 这一个入口。

#### 9.7.6 `graphId` 的语义与约束（C3 定案）

**默认实现忽略 `graphId`，直接返回全量**——首期不做按图/租户过滤，需要时由业务覆盖 `StreamResponseKindCatalog` Bean。这部分沿用原建议。

但**签名必须带 `graphId`，不可写死 `null`**。原骨架里 `resolveOrDefault` 内部调 `catalog.list(null)`，而 UI 调 kinds API 时是传 `graphId` 的。一旦业务覆盖 Catalog 做了过滤，这个不对称会让 B1 好不容易闭环的「设计期看到 A、运行期跑成 B」**重新裂开**：

| 环节 | 传入 | 得到列表 | 首位 |
|---|---|---|---|
| UI 属性面板 | `list("graph-A")` | 过滤后 `[K5, K6]` | **K5**（UI 显示的默认） |
| 编译期 / 运行期 | `list(null)` | 全量 `[K1 … K6]` | **K1**（实际生效） |

在按租户过滤的场景下这还不只是不一致，而是**越权**：租户 T2 只被允许用 `K2`，但其图的空值回落会拿到全量首位 `K1`。

`graphId` 在三个调用点都是现成的（`LlmRequestContext.graphId()`、`DynamicGraphBuilder` 的图上下文、kinds API 的请求参数），传递成本为零。

覆盖 Catalog 时须满足两条约束：

| 约束 | 原因 |
|---|---|
| **同一 `graphId` 必须返回同一列表**（幂等、无随机、排序稳定） | UI、编译期归一、运行期兜底三处独立调用，返回不一致即等于配置漂移 |
| **实现须为廉价内存操作**；需查远程（注册中心 / DB）时**自行缓存** | 运行期每次 `execute` 都会调用（§9.7.5），远程调用会被放大到每节点每次执行 |

> 已保存的 KEY 因目录调整而落到过滤范围之外时，沿用既有行为：**warn + 回落该图目录首位**，不 fail fast——线上图不应因目录规则变更而无法执行。

#### 9.7.5 为什么要两层（职责边界）

| 层 | 触发时机 | 解决什么 | 单独用它的不足 |
|---|---|---|---|
| 编译期 normalize | 图加载 / 发布 | 存量图**无需迁移即可正确运行**；`ofNode` 路径拿到确定的 kind；每次编译只查目录一次 | 绕过 builder 的路径（直接构造 Spec 的单测、未来新增入口）仍可能漏 |
| 运行期兜底 | 每次 `execute` | 兜住一切绕过编译期的路径 | 每次调用都查目录；且库里脏数据永不清理，问题被长期掩盖 |

双保险的取舍：**编译期负责「修好并留痕」，运行期负责「绝不崩、绝不静默用错默认值」**。两层都记日志，且日志级别不同（编译期 info 表示例行归一，运行期 warn 表示有路径绕过了归一，值得排查）。

> 明确**不做**图保存时 normalize：存量图不会被触碰，必须等下次人工编辑才修，对「一次性消化存量」没有帮助。

#### 9.7.6 归一化不回写数据库（重要，避免误解）

`withStreamResponseKind()` 返回**新副本**用于装配本次 `GenericAgentNode`，**不回写图定义**。三者状态如下：

| 对象 | 状态 | 说明 |
|---|---|---|
| 运行行为 | **已正确** | 每次编译归一化，幂等无副作用；节点跑的是目录首位，绝不会是写死的 BIZ |
| 库中数据 | 仍为空值 | 每次编译重新归一；归一化是纯函数，成本为一次目录查询，可接受 |
| UI 打开旧图 | 显示目录首位 | 前端读到空值后按 `items[0]` 填充，与服务端归一化结果**同值**（同目录、同比较器），用户保存后才真正落库 |

结论：**界面显示与运行行为在任何时刻一致，无需数据迁移**。若业务另有「SQL 直查统计 `streamResponseKind` 分布」等需求，可另写一次性迁移脚本，本方案不包含。

---

## 10. StreamingLlmTemplate 骨架（需求 11）

```java
public final class StreamingLlmTemplate {

    private static final Logger log = LoggerFactory.getLogger(StreamingLlmTemplate.class);

    /** Resolver 为单例依赖，构造时注入一次（§4.5.1） */
    private final LlmResolvers resolvers;

    public StreamingLlmTemplate(LlmResolvers resolvers) {
        this.resolvers = Objects.requireNonNull(resolvers, "LlmResolvers 不能为空");
    }

    public Map<String, Object> execute(LlmCallRequest req) {
        var ctx = req.context();
        var b = req.binding();                 // 委派 context.binding()，单一来源
        long startNanos = System.nanoTime();

        // 模型解析（§4.4.1）：静态层 key/内联整路二选一（不齐报错），再套 Override 逐字段补丁
        ModelEndpoint endpoint = resolvers.modelEndpoints()
            .resolve(ctx, req.inlineModel(), req.modelOverride());
        ChatClient.Builder builder = ChatClient.builder(
            resolvers.chatModels().create(endpoint)
        );

        List<NamedToolCallback> tools = new ArrayList<>();
        // 内置 skill 工具（§6.4.1）：仅在启用且白名单非空时注册，BUILTIN 优先级最高
        List<SkillDescriptor> skills = (b.enableSkill() && !b.skillKeys().isEmpty())
            ? resolvers.skillCatalog().resolve(ctx, b.skillKeys())
            : List.of();
        if (!skills.isEmpty()) {
            tools.addAll(SkillTools.builtin(ctx, b.skillKeys(),
                resolvers.skillContent(), resolvers.skillResources()));
        }
        if (b.enableLocalTools()) {
            tools.addAll(resolvers.localTools().resolve(ctx, b.localToolKeys()));
        }
        if (b.enableMcp()) {
            // Resolver 只负责按 server 取回；白名单过滤由框架统一施加——
            // 它是安全边界，交给业务 Resolver 实现容易漏（§7.1.1 / D3）
            tools.addAll(McpToolFilter.apply(
                resolvers.mcpTools().resolve(ctx, b.mcpKeys()), b, ctx.nodeId()));
        }
        var unique = ToolDeduper.dedupe(tools, req.conflictPolicy());
        if (!unique.isEmpty()) {
            // 必须用 toModelCallback()：模型可见名取自 ToolDefinition.name，
            // 直接用 ::callback 会让不同 MCP 的同名 tool 撞名（§5.1.1 / D1）
            // conflicted 决定是否给 description 加来源标识——名字唯一不解决模型选择（§5.3）
            Set<String> conflicted = ToolDeduper.conflictedOriginalNames(unique);
            List<ToolCallback> callbacks = unique.stream()
                .map(t -> t.toModelCallback(conflicted.contains(t.originalName())))
                .toList();
            ToolNames.assertUnique(callbacks);          // fail fast，早于 ChatClient 底层异常
            ToolNames.assertLegal(callbacks);           // 名字合法性，早于端点 400（§5.3）
            builder.defaultTools(callbacks.toArray(new ToolCallback[0]));
            log.info("节点 {} 挂载工具 {} 个: {}", ctx.nodeId(), unique.size(),
                unique.stream().map(t -> t.originalName() + "->" + t.uniqueName()).toList());
        }

        // 变量快照取一次，system/user 共用，保证二者看到同一份 state（§4.4.2 定案④）
        Map<String, Object> vars = PromptVars.snapshot(ctx.state(), req.inputKeys());
        // system = 有序 promptKeys 合并 → 单遍渲染 → 再追加 skill L1 目录（不渲染）
        // 注意：不拼工具目录——工具经请求体 tools 参数下发，system 重复一遍纯属冗余（§5.3）
        String system = buildSystem(ctx, b, skills, vars);
        ChatClient client = builder.build();
        // 空 KEY 回落到 kind 目录排序首位（与 UI 默认首位同一比较器，§13.1 B1），不写死 BIZ
        // 传 graphId：目录可按图/租户过滤，写死 null 会让运行期首位偏离 UI 首位（§9.7.6）
        StreamResponseKind kind = resolvers.kinds()
            .resolveOrDefault(ctx.graphId(), req.streamResponseKind());

        // 多模态（§8.2.4）：mediaInputKey 留空 / 无引用时 medias 为空，等价纯文本调用
        List<MediaRef> refs = MediaRefs.readFrom(ctx.state(), req.mediaInputKey());
        List<Media> medias = refs.isEmpty()
            ? List.of()
            : resolvers.media().resolve(ctx, refs);

        // user 同样只在框架层渲染一次，传给 Spring AI 的是纯文本（不走 PromptTemplate，§4.4.2 定案①）
        String userText = resolvers.promptRenderer()
            .render(req.userMessage(), vars, ctx.nodeId());

        // 每次调模型都重新组装 media，勿复用上游 UserMessage（其 media 可能已丢失）
        Consumer<ChatClient.PromptUserSpec> userSpec = u -> {
            u.text(userText);
            for (Media m : medias) {
                u.media(m.getMimeType(), m.getData());
            }
        };

        if (req.streaming() && req.bridge() != GraphStreamBridge.NOOP) {
            StringBuilder full = new StringBuilder();
            client.prompt().system(system).user(userSpec)
                .stream().content()
                .doOnNext(tok -> {
                    full.append(tok);
                    req.bridge().emit(ctx.runId(),
                        new TokenChunk(ctx.nodeId(), tok,
                            OutputType.AGENT_MODEL_STREAMING, kind, false));
                })
                .blockLast();
            req.bridge().emit(ctx.runId(),
                new TokenChunk(ctx.nodeId(), "",
                    OutputType.AGENT_MODEL_FINISHED, kind, true));
            log.info("节点 {} 流式调用完成: kind={}, 字符数={}, 耗时={}ms",
                ctx.nodeId(), kind.code(), full.length(), elapsedMs(startNanos));
            return Map.of(req.outputKey(), full.toString());
        }

        String text = client.prompt().system(system).user(userSpec)
            .call().content();
        log.info("节点 {} 同步调用完成: 字符数={}, 耗时={}ms",
            ctx.nodeId(), text == null ? 0 : text.length(), elapsedMs(startNanos));
        return Map.of(req.outputKey(), text);
    }
}
```

业务薄节点示意（`binding` 随 `context` 传入，不再单独设置）：

```java
public Map<String, Object> apply(OverAllState state) {
    LlmRequestContext ctx = new LlmRequestContext(
        StateValues.getString(state, LlmRequestContext.ACE_AGENT_CODE_KEY, ""),
        graphId, nodeId, StateValues.getString(state, ModelOverrideSpec.ACE_RUN_ID_KEY, ""),
        state, ResourceBindings.fromSpec(spec));
    if (ctx.agentCode() == null || ctx.agentCode().isBlank()) {
        log.error("节点 {} 缺少 agentCode，入口未写入保留键 {}",
                nodeId, LlmRequestContext.ACE_AGENT_CODE_KEY);
    }

    // 请求级覆盖：从 state 保留键读取并解析出本节点生效项（§4.4.1 最高优先级）
    ModelOverrideSpec overrides = StateValues.get(state, ModelOverrideSpec.ACE_MODEL_OVERRIDES_KEY);
    return streamingLlmTemplate.execute(LlmCallRequest.builder()
        .context(ctx)
        .streamResponseKind(spec.streamResponseKind())
        .mediaInputKey(spec.mediaInputKey())   // 可空 → 纯文本
        .userMessage(StateValues.getString(state, "reply_draft", ""))
        .outputKey(spec.effectiveOutputKey())
        .streaming(true)
        .bridge(streamBridge)
        .inlineModel(InlineModels.fromSpec(spec))                    // 内联三字段
        .modelOverride(overrides == null ? null : overrides.effectiveFor(nodeId))
        .build());
}
```

---

## 11. 作用范围（需求 10）

**仅 Agent / GENERIC_AGENT 节点**接入本能力。

| 节点类型 | 是否接入 |
|---|---|
| GENERIC_AGENT / Agent | 是 |
| 脚本节点 | 否（保持原样） |
| 普通 Java `NodeAction` | 默认否；可自愿注入 Template |

避免脚本节点被 LLM 资源模型污染。

---

## 11.1 注册式 Agent 的配置落点（定案：1A + 2B + 3B）

现网 Agent 有两条通道：**内联**（图节点 `properties.agentSpec`）与**注册式**（节点定义库 `GenericAgentDefinition.spec`，多图按 `nodeId` 复用）。新增字段落点定案如下。

| 字段 | 落库位置 | 图内可否覆写 | 编辑入口 |
|---|---|---|---|
| `resourceBindings`（enable + keys） | **节点定义库 `GenericAgentSpec`** | **不可** | 节点面板「通用 Agent」编辑器 |
| `streamResponseKind` | **节点定义库 `GenericAgentSpec`** | **不可** | 节点面板「通用 Agent」编辑器 |

### 11.1.1 规则

1. **单一配置源**：注册式 Agent 的资源勾选与流式响应类型，只在节点定义库存一份；所有引用图行为一致
2. **不引入覆写层**：图节点上不存这两个字段的覆写片段，编译期无需 merge，运行时不存在「哪一层生效」的歧义
3. **属性面板对注册式节点只读**：显示摘要 + 跳转链接（沿用现网 `isGenericAgentRegistered` / `gotoNodePanelAgent` 行为），不提供编辑
4. **内联 Agent 不受影响**：内联节点的这两个字段仍存在图节点 `agentSpec` 上，就地编辑
5. **差异化诉求走多定义**：同一 Agent 在不同图需要不同资源或不同流式类型时，**新建独立定义**（例如 `agent:translator-biz` / `agent:translator-output`），不做图内覆写

### 11.1.2 取值与校验

- 编译期：注册式节点从 `GenericAgentDefinition.spec` 读取 `resourceBindings` 与 `streamResponseKind`，忽略图节点上可能残留的同名字段（若存在则打 warn，便于发现误配）
- `streamResponseKind` 为空时：由后端按 §9.4 的 Registry 排序首位 normalize，与 UI 默认一致
- 保存节点定义时：`streamResponseKind` 仍按 §9.4 校验（必选、目录内）；**prompt/mcp 等资源 key 默认不做存在性校验**（§7.4），除非业务提供了 `ResourceKeyValidator`

### 11.1.3 该决策的已知代价

- 小差异（例如 B 图想少挂一个 MCP、或想改成 `OUTPUT`）必须新建定义，定义数量会增长
- 拆出的多个定义需人工保持同步，存在「改了一个忘了另一个」的风险；可由节点面板提供「复制定义」降低成本

---

## 12. 与现有代码衔接

| 现有 | 演进 |
|---|---|
| `GenericAgentSpec` 单 promptKey/mcpKey | → `ResourceBinding` 多 key + enable；兼容旧字段 |
| `GenericAgentSpec` 紧凑构造器（`outputKey` 静态兜底） | 保持不变；`streamResponseKind` 因默认值依赖运行时 Catalog **不在此兜底**，改由编译期归一（§9.7.2）；新增 `withStreamResponseKind` wither |
| `DynamicGraphBuilder#resolveGenericAgent` | ①改为注入 `GenericAgentNodeFactory` 而非直接 `new GenericAgentNode`，返回类型改 `GraphBoundAgentNode`（§12.1.5）；②新增编译期 normalize + 收集 `nodeId → kind` 映射（内联/注册式唯一汇聚点，§9.7.3） |
| 新模块 `ace-graph-dsl-ai` | 承载模型层：Template / Resolver / `NamedToolCallback` / 迁入的 6 个类（§12.1） |
| `GenericAgentDefinition.spec` | 承载注册式 Agent 的 `resourceBindings` + `streamResponseKind`（§11.1） |
| PromptRepository 等 SPI | 保留；适配为 Resolver |
| `AgentChatClient` 隔离层 | 可选保留；新路径优先原生 `ChatClient` |
| `GraphStreamBridge` | 继续作为流式带外通道；`TokenChunk` 携带 kind 标签 |
| `StreamingChunkFormatter` | **保持不变**，仍为协议唯一挂载入口（不 deprecated、不改优先级） |
| `GraphExecutionController#resolveFormatter` | **零改动**；仅建议补一条「生效格式化器」启动日志 |
| `StreamingContext` | 新增 `getResponseKind()` / `isKind(code)` |
| `TokenChunk` | 增加 `StreamResponseKind`（可空，保留旧构造器） |
| UI `PropertyPanel` agentSpec 区 | **内联节点**：开关 + 资源 catalog 多选 + 流式类型下拉；**注册式节点**：只读摘要 + 跳转节点面板 |
| UI `AgentNodeEditor`（节点面板） | 注册式 Agent 的唯一编辑入口：资源勾选 + 流式类型下拉（kinds API） |

---

## 12.1 模块划分与迁移（A8 定案：新建 `ace-graph-dsl-ai` + 彻底迁移）

### 12.1.1 背景

现状核查结论：**整个 backend 无任何模块直接依赖 spring-ai**。`core/pom.xml` 仅有 `spring-ai-alibaba-graph-core`（图引擎）；根 pom 引入了 `spring-ai-bom` 但无模块声明 `spring-ai-client-chat`；`AgentTool` 的类注释明确写着「隔离 spring-ai ToolCallback，避免 core 依赖具体模型实现」。

而需求 11 要求 Template 用原生 Spring AI（`ChatClient.builder()` / `ToolCallback` / `Media`），必须有模块真实依赖 spring-ai。

**定案**：新建 `ace-graph-dsl-ai` 模块承载全部模型相关能力；`AgentTool` 弃用删除，统一到 spring-ai `ToolCallback`；core 保持不依赖 spring-ai。

### 12.1.2 模块依赖

```text
ace-graph-dsl-core            （编排内核，不依赖 spring-ai）
        ▲
        │ depends
ace-graph-dsl-ai              （模型层，依赖 spring-ai-client-chat）
        ▲
        │ depends
ace-graph-dsl-spring-boot-starter
```

### 12.1.3 归属判定原则

**返回/参数类型中出现 spring-ai 类型的，放 `ai` 模块；纯数据、纯 String、仅依赖 graph-core 的，留 `core`。**

| 组件 | 归属 | 依据 |
|---|---|---|
| `StreamResponseKind` / `StreamResponseKindItem` / `StreamResponseKindCatalog` | **core** | 纯数据；且 `GenericAgentSpec` 需引用 |
| `TokenChunk` / `GraphStreamBridge` / `StreamingContext` / `StreamingChunkFormatter` | **core** | 仅依赖 graph-core 的 `OutputType` / `NodeOutput` |
| `PromptContentResolver` / `SkillCatalogResolver` / `SkillContentLoader` | **core** | 返回 String / 纯描述对象 |
| `MediaRef` | **core** | 纯数据（url + mime） |
| `MediaRefResolver` | **ai** | 返回 `List<Media>` |
| `LocalToolResolver` / `McpToolResolver` / `NamedToolCallback` / `ToolDeduper` | **ai** | 涉及 `ToolCallback` |
| `StreamingLlmTemplate` / `ChatModelFactory` | **ai** | 使用 `ChatClient` |

### 12.1.4 从 core 迁往 ai 的类

| 类 | 处理 |
|---|---|
| `GenericAgentNode` | 迁入 ai，改为实现新抽象 `GraphBoundAgentNode` |
| `AgentChatClient` / `ChatClientFactory` / `StubChatClientFactory` | 迁入 ai |
| `McpServerRegistry` / `McpServerConfig` | 迁入 ai（`McpServerConfig.tools()` 仍是 server 级白名单来源，§7.1.1） |
| `McpToolProvider` / `InMemoryMcpToolProvider` | **删除**，职责由 `McpToolResolver` 承接（返回 `NamedToolCallback`，D3） |
| `AgentTool` | **删除**，其 `name()` / `call(Map)` 语义由 `ToolCallback` + `ToolDefinition` 承担 |
| 对应单测（`GenericAgentNode*Test` 等） | 随类迁入 ai |

### 12.1.5 core 新增两个抽象（依赖倒置）

`DynamicGraphBuilder` 当前直接 `new GenericAgentNode(...)`，节点迁走后无法编译。改为依赖抽象：

```java
/** 可绑定图归属的 agent 节点（core 侧抽象，避免 core 引用 ai 模块的具体类型） */
public interface GraphBoundAgentNode extends RegisteredGraphNode {

    /**
     * 克隆出绑定指定 graphId 的副本。
     *
     * <p>注册中心实例为「无图归属」共享定义，直接复用会让 SecretResolver 取到
     * 错误的图命名空间，故编译期须按当前图克隆。</p>
     */
    GraphBoundAgentNode withGraphId(String graphId);
}

/** GENERIC_AGENT 节点工厂（core 定义，ai 模块实现） */
public interface GenericAgentNodeFactory {

    /** 按元数据构造 agent 节点；graphId 用于 secret 命名空间隔离 */
    GraphBoundAgentNode create(String nodeId, String graphId, GenericAgentSpec spec);
}
```

`resolveGenericAgent` 改造后（两条通道均不再引用具体类型）：

```java
private GraphBoundAgentNode resolveGenericAgent(GraphDefinition def, NodeRef ref) throws GraphStateException {
    GenericAgentNodeFactory factory = agentNodeFactory.getIfAvailable();
    if (factory == null) {
        // 未引入 ai 模块时给出可操作错误，而非 NPE
        throw new GraphStateException("图 " + def.graphId() + " 含 GENERIC_AGENT 节点 " + ref.nodeId()
                + "，但未找到 GenericAgentNodeFactory；请引入 ace-graph-dsl-ai 依赖");
    }
    if (ref.agentSpec() != null) {
        // 通道一 · 内联（含 §9.7.3 编译期 kind 归一化）
        return factory.create(ref.nodeId(), def.graphId(),
                normalizeStreamKind(def.graphId(), ref.nodeId(), ref.agentSpec()));
    }
    if (!nodeRegistry.contains(ref.nodeId())) {
        throw new GraphStateException("通用 agent 节点既无内联 agentSpec，也未在注册中心找到已入库定义: "
                + ref.nodeId() + "（请先在设计器创建 agent 节点定义，或为该节点补内联元数据）");
    }
    RegisteredGraphNode registered = nodeRegistry.get(ref.nodeId());
    if (!(registered instanceof GraphBoundAgentNode agentNode)) {
        throw new GraphStateException("节点 " + ref.nodeId() + " 已注册但并非通用 agent 节点: "
                + registered.getClass().getName());
    }
    // 通道二 · 注册式：克隆绑定当前图
    return agentNode.withGraphId(def.graphId());
}
```

### 12.1.6 starter 的依赖方式

starter **compile 依赖** `ace-graph-dsl-ai`，保证全家桶开箱可用（starter 本已绑定 web/redis，定位为重量级集成包）。仅做纯编排、不需要 Agent 节点的项目直接依赖 `core` 即可，此时 GENERIC_AGENT 节点按 §12.1.5 抛出可操作错误。

---

## 13. 高级工程师审查报告

| 维度 | 结论 | 说明 |
|---|---|---|
| 边界 | 通过 | Function 入参把注册中心选型踢出 backend |
| 请求级模型 | **已闭环** | §4.4.1：Override 补丁 + 静态层 key/内联整路二选一，不齐报错 |
| 工具冲突 | **已闭环** | D1（§5.1.1）uniqueName 经 `toModelCallback` 落到 `ToolDefinition.name`；D2（§5.3）分隔符改 `__` 修正端点 400 缺陷、关闭短名、冲突组 description 补来源标识、system 不拼工具目录；BUILTIN 优先级保留，短名保留名规则取消 |
| Skill 懒加载 | **已闭环** | §6 渐进披露；UI 勾选 = 白名单；内置工具命名/去重/越权与路径校验已定；§10 骨架已接线 |
| 多模态 | **已闭环** | §8.2：`mediaInputKey` UI 可配 / state 存 Map + REPLACE / mime 五级补全 / SSRF 与超时约束 / Template 组装接线 |
| AgentCard | **本期不做** | 已移出 Binding / Resolver / Template；见 §1.3 |
| 流式协议归属 | **已定案** | §9.0：协议归业务，框架只给入口 + 透传 kind；不内置任何字段实现 |
| 流式类型设计期 | 基本闭环 | kinds API（配置驱动）+ 必选 + 首位默认 |
| 流式类型运行期 | **已闭环** | §9.7：编译期 normalize + 运行期兜底双保险，与 UI 默认同源；节点内切 kind 已定案不做，改由「一节点一 kind」编排承载（§13.2.1） |
| Formatter 共存 | **已闭环（矛盾消除）** | §9.6：单一入口、控制器零改动；废弃 Codec 体系后「不动控制器 vs Codec 优先」的互斥自动消失 |
| Catalog↔Runtime | **已定案** | §7.4：软约定 + 运行期真相 + 可选 Validator；不做强制联合试探 |
| 注册式 Agent | **已定案** | §11.1：1A+2B+3B，定义库单一源、无图内覆写、节点面板唯一入口 |
| 执行端 UI | **已定案** | §9.6.7：新增 `/debug/stream` 端点用框架标准格式（带 `streamKind`），ui 不适配业务协议；`/stream` 生产行为不变 |

主要风险（原）：

1. 多 MCP 同名 tool 用原名挂载会直接导致 ChatClient 报错  
2. UI catalog 与 Resolver key 不一致导致运行期失败  
3. BIZ/OUTPUT 字段若完全相同，前端无法分流  

详见下一节「闭环缺口清单」。

---

## 13.1 闭环缺口清单（Review）

下列项为方案中「有半截设计、未形成配置→运行→观测完整闭环」或「互相矛盾」之处，落地前需补齐。

### A. 配置 → 运行 未接通

| # | 缺口 | 现状 | 建议闭环 |
|---|---|---|---|
| A1 | **AgentCard** | ~~Binding 有字段无消费~~ | **本期明确不做**；不阻塞其余闭环。后续单独立项 |
| A2 | **多模态** | 已闭环（§8.2）：UI 填 `mediaInputKey`；state 存 `List<Map>` + `REPLACE`；`MediaRef{url 必有, mime 可空}`；mime 五级补全；失败跳过+warn；SSRF/超时/条数/缓存约束；Template 组装步骤与 `LlmCallRequest` 字段已定 | 仅剩编码实现 |
| A3 | **Skill 触发加载** | **已闭环（§6 + §6.3.1）**：三层披露 + 白名单 + `load_skill`/`read_skill_resource`。**补定用户指定格式**：框架只认 state 保留键 `ace.graph.dsl.forceSkills = ["skill.xxx",…]`（与 skillKeys 同一套 key）；终端聊天口令（`/skill` 等）**不由框架规定**，业务解析后写入该键；模型自选走工具参数 `code` | 实现：读 forceSkills 预激活 + 白名单校验日志 |
| A4 | **Prompt 变量渲染** | **已定案（§4.4.2）**。先纠正一处误解：现网 `{{state.key}}` **从未实现**——`GenericAgentNode` 只把 `inputKeySet()` 收集成 `variables` 原样下传，core 无任何替换，Stub 仅回显；渲染责任被隐式推给 `AgentChatClient` 实现方且无人实现。定案五条：①渲染收归框架层，交给 Spring AI 的是**已渲染纯文本**，不用其 `PromptTemplate`（其单花括号 `{}` 与 prompt 内 JSON 示例冲突）；②语法仅 `{{state.key}}`，`state.` 前缀可选；③**严格单遍替换**防模板注入，替换值不再被扫描，且必须 `Matcher.quoteReplacement`；④system/user **共用同一变量快照**（「谁先」的实质是同源而非先后），多 promptKeys **先按序合并再统一渲染一次**；⑤skill L1 目录与工具目录**在渲染之后**追加，不作为用户模板。缺失变量默认空串+warn，可配严格模式；`Map`/`List` 走 JSON 而非 `toString` | 实现项：`PromptRenderer` + `PromptVars.snapshot` + `PromptRenderProperties`（严格模式/单值上限/总长上限）+ **编译期占位符与 `inputKeys` 双向比对 warn**；`LlmResolvers` 加 `promptRenderer`、`LlmCallRequest` 加 `inputKeys` |
| A5 | **模型三路来源** | **已定案（§4.4.1，已修订）**：优先级仍是 Override > modelConfigKey > 内联，但**静态层改为整路二选一**——勾了 key 就只用注册中心那一套，缺字段**报错**，禁止用内联字段拼盘补齐（避免「配的是 key，日志里却是内联 modelId」）。**仅请求级 Override** 允许在底座上逐字段补丁（只改 modelId 做 A/B）。enableModel=false = 跳过 key 路而非禁用节点 | 实现：requireComplete + 整路底座 + Override 补丁；编译期 key/内联并存 warn |
| A6 | **`LlmCallRequest` / `ChatModelFactory`** | **已闭环（§4.5）**：纠正「Resolver 当请求级字段」的概念错位，拆为单例 `LlmResolvers`（10 项，含非空校验）+ 请求级 `LlmCallRequest`（8 字段 + Builder + 参数归一）；`binding` 唯一来源为 `context`；`ChatModelFactory` 返回原生 `ChatModel`，默认实现按端点 **LRU 有界缓存**（上限 64，超限 warn 提示动态 key 误用）、api-key 严禁入日志；`ChatClientFactory`/`AgentChatClient` 标 deprecated 且**不提供桥接**（语义不可逆） | 实现项：两 record + Builder + 缓存工厂 + 自动配置默认回落 |
| A8 | **Template 的模块归属与 spring-ai 依赖** | **已定案（§12.1）**：新建 `ace-graph-dsl-ai` 承载模型层；`AgentTool` 删除、统一到 `ToolCallback`；core 保持不依赖 spring-ai；core 新增 `GraphBoundAgentNode` + `GenericAgentNodeFactory` 两个抽象，`DynamicGraphBuilder` 改注入工厂；starter compile 依赖 ai | 实现项：建模块 + 6 类迁移（含单测）+ 工厂抽象 + 缺失时可操作报错 |
| A7 | **注册式 Agent** | 已定案（§11.1）：两字段只存 `GenericAgentDefinition.spec`，图内不可覆写，节点面板为唯一编辑入口 | 实现项：属性面板对注册式保持只读+跳转；编译期忽略图上残留字段并 warn |

### B. 流式类型闭环缺口

| # | 缺口 | 现状 | 建议闭环 |
|---|---|---|---|
| B1 | **服务端空 KEY 兜底 ≠ 前端首位** | **已闭环（§9.7）**：统一到 `resolveOrDefault` 单一入口，取 Catalog 排序首位（order 升序 + key 字典序，与 UI 同一比较器）；`StreamResponseKind.of` 对空值 fail fast，不再写死 BIZ | 实现项：`ORDER` 比较器 + `resolveOrDefault` |
| B2 | **旧图无 `streamResponseKind`** | **已闭环（§9.7）**：定案「编译期 normalize + 运行期兜底」双保险；落点 `DynamicGraphBuilder#resolveGenericAgent`（内联/注册式唯一汇聚点）；不做保存时 normalize | 实现项：`GenericAgentSpec.withStreamResponseKind` + 编译期归一并记 info |
| B3 | ~~节点内 BIZ→OUTPUT 切换~~ | **已定案（①首期不做）**：一个节点固定一个 kind。「节点A(BIZ 思考/处理) → 节点B(OUTPUT 总结输出)」的编排方式已完整支持（§13.2.1） | 无。将来如需单节点内分段，可平滑追加 `emitKind` 可选 API，不影响已落库图 |
| B4 | ~~Codec 的 label/description~~ | **已消解**：kind 元数据由配置项 `key/label/description/order` 承载，与协议实现解耦（§9.4.2） | 无 |
| B5 | **与现有 Formatter Bean 冲突** | **已闭环（矛盾消除）**：废弃 Codec 体系，`StreamingChunkFormatter` 为唯一入口，控制器零改动，不存在两套实现争格式化权（§9.6） | 实现项：仅补一条「生效格式化器」启动日志 |
| B6 | **`graph.stream()` 内置片段** | 已闭环（§9.6.4）：kind 三级回落 token → 节点 Spec → `null`；`null` 由业务 Formatter 自行处理，默认格式保持现网形状不变 | 实现项：`nodeId → kind` 映射与 §9.7.3 编译期归一**同一处遍历**完成 |
| B7 | ~~执行端前端~~ | **已定案（§9.6.7）**：新增独立端点 `/execution/{graphId}/debug/stream`，强制用框架内置 `DebugStreamingChunkFormatter`（带 `nodeId`/`streamKind`/`seq`/`ts`），忽略业务 Bean；ui 只依赖此框架契约，不做业务协议适配。`/stream` 行为完全不变 | 实现项：新端点 + Debug Formatter + UI 格式提示文案 + 端点鉴权（并入 C4） |
| B8 | ~~`defaultSelected` 字段~~ | **已闭环**：字段已从 JSON 契约与 `StreamResponseKindItem` 删除，改为 `order`（服务端已排序返回）；「默认 = 列表首位」成为全链路唯一规则（§9.4.1） | 无 |

### C. Catalog / 校验 / 安全

| # | 缺口 | 现状 | 建议闭环 |
|---|---|---|---|
| C1 | **资源 Catalog ↔ Resolver** | **已收束到 §7.4**：不再单独做强制联合试探 API。Catalog 与 Resolver「同一 key 空间」为**软约定**；真相以运行期 Resolver 为准。可选 `ResourceKeyValidator` 供业务在能廉价判断时做保存期校验 | 实现项：可选 SPI；无 Bean 则保存跳过存在性校验 |
| C2 | **资源 key 保存校验** | **已定案（§7.4）**：**默认不做** prompt/mcp/skill/model/localTool 的 key 存在性校验，允许先保存。运行期加载失败 → **error 日志**（含 graphId/nodeId/type/key）；调试 `/debug/stream` 另发 `resource_miss` 事件，UI 列出失败 key。失败策略：Model/Prompt 硬失败；MCP/LocalTool/Skill 单项跳过+日志。仅当业务提供 `ResourceKeyValidator` 时保存期才拦截 `MISSING/ERROR`。**不**用 Catalog.list 差集冒充校验。`streamResponseKind` 仍按 §9.4 必选校验，不在此列 | 实现项：`ResourceLoadDiagnostics` + debug `resource_miss` + 运行期分层失败策略 + 可选 Validator 挂钩保存入口 |
| C3 | **kinds 的 graphId 过滤** | **已定案（§9.7.6）**：默认实现忽略 `graphId` 直接返回全量，需要按图/租户过滤时由业务覆盖 `StreamResponseKindCatalog` Bean——**这部分沿用原建议**。但补一处必改项：原骨架 `resolveOrDefault` 内部写死 `catalog.list(null)`，而 UI 调 kinds API 是传 `graphId` 的；一旦业务覆盖 Catalog 做过滤，该不对称会让 **B1 已闭环的「设计期看到 A、运行期跑成 B」重新裂开**（UI 取过滤后首位 K5、运行期取全量首位 K1），按租户过滤时更构成**越权**（T2 的图回落到 T2 无权使用的 K1）。故 `resolveOrDefault(graphId, raw)` 与 `normalizeStreamKind(graphId, …)` 签名一律带 `graphId`（三个调用点均现成，成本为零）。覆盖 Catalog 的两条约束：同一 `graphId` 必须返回同一列表（幂等、排序稳定）；实现须廉价内存操作，查远程须自行缓存（运行期每次 `execute` 都会调用）。已存 KEY 落到过滤范围外时沿用 warn + 回落该图首位，不 fail fast | 实现项：`resolveOrDefault`/`normalizeStreamKind` 加 `graphId` 参数并贯通三个调用点；默认实现忽略该参数；文档写明覆盖约束 |
| C4 | **鉴权** | **已定案（§7.5，大白话）**。设计器里用的新接口（资源列表、流式类型下拉、调试流）要像「保存图 / 试运行」一样查菜单权限，没权限返回 403。正式给用户跑图的 `/stream` 不走这套，由业务自己的登录管。权限配在业务工程里（见 `MENU_PERMISSION_INTEGRATION.md`）；前端藏按钮不够，后端还要拦。不新造一套权限 | 实现：新接口挂现有权限检查；调试按钮与试运行同一权限 |

### D. 工具与模型调用

| # | 缺口 | 现状 | 建议闭环 |
|---|---|---|---|
| D1 | **uniqueName → 模型可见名** | **已闭环（§5.1.1）**：`NamedToolCallback.toModelCallback()` 包装出改名后的 `ToolCallback`，令 `ToolDefinition.name == uniqueName`（description/inputSchema 原样保留）；同名时直接返回原回调不多包一层；挂载前断言 name 全局唯一并 fail fast；日志打印 `原名->uniqueName` 映射 | 实现项：包装方法 + 唯一性断言 + 映射日志 |
| D2 | **短名映射进 system** | **已定案（§5.3）：关闭短名，一律用 uniqueName；system 不写任何工具目录文案**。查证中发现一处会导致请求直接失败的缺陷：原 uniqueName 格式 `mcp:weather:get_forecast` **含冒号，违反 OpenAI 兼容端点的 `^[a-zA-Z0-9_-]{1,64}$` 约束**，端点会以 400 拒绝整个请求（`ace:skill:load_skill` 同样违规），故分隔符改为 `__` 并强制 sanitize + 超长哈希压缩。更关键的是分清两层：**名字唯一只解决技术路由，不解决模型在同名工具间的语义选择**——两个 MCP 的 `query_order` 改名后 description 仍相同，模型只能猜。故定案冲突组内 **description 前置 `[来源: {serverKey}]`**（无冲突则原样保留，不加噪声）。system 不写目录的理由：工具经请求体 `tools` 参数结构化下发、不走 prompt，重写一遍既每轮烧 token 又有与实际 tools 不一致的风险 | 实现项：`ToolNames.toModelName`（sanitize + 64 上限哈希压缩）+ `assertLegal` 断言 + `ToolDeduper.conflictedOriginalNames` + `toModelCallback(boolean conflicted)`；§6.4.1 保留名规则取消；MCP 配置项增「人类可读来源说明」用于消歧文案 |
| D3 | **本地 tools 与现网 `spec.tools` 名列表** | **已定案（§7.1.1）**。前提：确认无存量图，故**零兼容映射**，原「`promptKey` → `promptKeys=[promptKey]`」等自动转换一并取消。查证纠正一处误判：`spec.tools` **从来不是本地工具 key，而是 MCP 工具级过滤白名单**（`InMemoryMcpToolProvider#effectiveToolNames` 将其与 `serverConfig.tools()` 取交集，节点未声明则取全集），故直接删它会**丢失能力**而非仅破兼容——而工具级过滤是刚需（几十个工具全挂会同时恶化 token、选择准确率与安全暴露面）。定案：删 `tools`，能力平移为 `ResourceBinding.mcpToolWhitelist`（`serverKey → 工具名`，缺省=全选）；过滤由**框架** `McpToolFilter` 施加而非下放 SPI（白名单兼安全边界，业务漏做即绕过；且 `list_tools` 本就全量返回，框架过滤无额外远程开销）。旧字段处置：`promptKey`/`skillKey`/`mcpKey`/`skill`/`mcp` 全删，**仅保留 `prompt`** 且语义由「与 key 二选一」改为**纯追加**（拼在 promptKeys 合并结果之后，无优先级概念）。废弃字段检测分层：反序列化保留 `ignoreUnknown=true`（灰度期两版本需互读图），保存与编译入口**检出即 fail fast**并给出替代项——因 `ignoreUnknown` 会静默吞掉残留 `tools`，使人误以为工具已挂载 | 实现项：`ResourceBinding` 加 `mcpToolWhitelist` + `McpToolFilter` + `NamedToolCallback.serverKey()` 派生访问器 + `REMOVED_FIELDS` 映射与两处 fail fast；删除 `McpToolProvider`/`InMemoryMcpToolProvider`；UI 的 MCP 勾选改三级树 |

### E. 文档/示意自相矛盾（小）

| # | 问题 | 处理 |
|---|---|---|
| E1 | ~~流程图节点 `K` 既表示 kinds API 又表示写回 outputKey~~ | **已修**：§3.1 中「写回 outputKey」节点已改名为 `W`，不再与 kinds API 的 `K` 冲突 |
| E2 | ~~BIZ/OUTPUT 默认 Codec 均 `thinking:true`~~ | **已消解**：框架不再内置任何 kind 的字段实现（§9.0） |
| E3 | ~~第 13 节原「流式类型通过」与真实缺口不符~~ | **已修**：§13 审查表已逐项改为「已闭环 / 已定案 / 待确认」并与 §13.1 对齐（含流式设计期、运行期、Formatter 共存、执行端 UI 四行） |

### F. 已基本闭环（对照）

- Function 抽象 Prompt/Model/LocalTool/MCP 的职责边界  
- 工具去重命名空间思路  
- 资源勾选 enable + keys（设计意图完整，差校验）  
- 流式 kinds 列表 API + UI 必选 + 默认首位 + 禁止空 KEY（**设计期闭环**）  
- 仅 Agent 节点接入范围  
- 多模态「勿信 UserMessage.media 过 checkpoint」结论  

---

## 13.2 待产品确认清单（流式类型）

流式类型 8 项缺口 **B1–B8 已全部闭环**（B3 定案①见 §13.2.1，B7 定案「独立调试端点」见 §13.2.2 与 §9.6.7）。本节保留各项候选与取舍记录，供后续回溯。

### 13.2.1 B3：节点内能否中途切换 kind（已定案：①首期不做）

**定案理由**：主流编排方式是「一个节点干一件事、各配一个 kind」，该方式已完整支持，无需节点内切换。典型链路：

```text
节点A（kind=BIZ）业务思考与处理，流式吐字 → 前端灰字折叠区
   │ 完整文本写入 outputKey
   ▼
节点B（kind=OUTPUT）总结 A 的结果，流式吐字 → 前端主气泡黑字
```

三点保障：

| 关注点 | 机制 |
|---|---|
| 前端实时收字，不等节点跑完 | `GraphStreamBridge` 为**带外通道**，模型每出一个 token 直接推 SSE；§10 骨架中 `blockLast()` 阻塞的是图引擎（B 需等 A 的完整结果），**不影响前端实时性** |
| A 的输出传给 B | A 用 `StringBuilder` 边推边攒，完整文本写 `outputKey`；B 经 `inputKeys` 读取（现有 `OverAllState` 机制，无需新增） |
| 前端区分渲染位置 | 每个片段携带 `nodeId` + 该节点的 kind；追加到哪个区域、折叠区样式等**全归前端**（§9.0） |

**动因（保留备查）**：一次模型调用内部有时也有两种性质不同的输出——过程性内容（推理、工具调用，前端通常折叠）与给用户看的终稿。二者渲染逻辑不同，故曾考虑节点内切换。

**当前无落点的原因**：`GenericAgentSpec.streamResponseKind` 只有一个字段；§10 骨架中 `kind` 是 `execute()` 内的循环外局部变量，全流复用同一值。「切换」既无触发者，也无第二个值的存放处。

> 早期 §9.5 时序图曾画「emit(BIZ) → 切换 → emit(OUTPUT)」，但未定义切换的触发者与判据，与数据结构自相矛盾。该图已在重写时统一为单 kind，**文档现已自洽**；B3 是「是否新增该能力」的产品决策，非文档缺陷。

| 候选 | 效果 | 好处 | 负面影响 |
|---|---|---|---|
| **①首期不做**（倾向） | 一个节点固定一个 kind，Spec 与运行期一一对应 | 无歧义；设计器上看到的就是实际输出的；实现成本为零。**「节点A(kind=BIZ) + 节点B(kind=OUTPUT)」的编排方式完全支持**，多数场景够用 | 仅一种情况受限：**单节点、单次调用内部**想分段标记。典型为节点内挂了工具（模型自行决定调几轮，全在一次 `stream()` 内）或用推理模型（自己先出思维链再出答案）。此时只能整段一个 kind；改拆两节点则变成两次模型调用，且后一次拿不到前一次的完整推理过程 |
| ②开放 `emitKind(kind, token)` | 业务可在节点内自由分段 | 灵活，单节点即可完成多阶段输出 | 设计器上配的 kind 退化为「主 kind」，与实际输出可能不符，排查变难；需额外校验 emit 的 kind ∈ 目录 |
| ③内置两段式（`processKind` + `outputKind`） | Template 自动在流末切换 | 覆盖最常见场景且行为可预期 | 框架又对「什么算过程、什么算终稿」做了假设，与 §9.0「协议归业务」相悖 |

> 选 ① 时的迁移路径：将来若确需中途切换，可平滑升级到 ②，不影响已落库的图（多出一个可选 API 而已）。

### 13.2.2 B7：`ace-graph-dsl-ui` 执行/调试面板如何渲染（已定案：独立调试端点 + 框架标准格式）

**定案理由**：`ace-graph-dsl-ui` 是通用产品，若跟随业务协议渲染，则每接一个业务方都要在前端做一次协议适配，不可持续。故调试台只认框架标准格式。

**落地**：新增 `POST /execution/{graphId}/debug/stream`，强制使用框架内置 `DebugStreamingChunkFormatter`，不调用 `resolveFormatter()`、业务 Bean 完全不介入；`/stream` 保持原样供生产使用。完整设计见 **§9.6.7**（含调试格式字段表、为何调试格式可带 `streamKind`、风险与鉴权要求）。

> 原「候选③强制 debug Formatter」的顾虑（调试所见≠生产所下发）依然存在，应对方式是 UI 明示格式提示 + 业务验证协议时直接压 `/stream` 抓包，见 §9.6.7。

张力所在（保留备查）：按 §9.0 协议归业务，但框架自己的调试面板也要显示流式输出，业务自定义 Formatter 后它就读不懂该协议了。

| 候选 | 效果 | 好处 | 负面影响 |
|---|---|---|---|
| **①默认格式正常渲染，检测到自定义 Formatter 则降级为原始 SSE 文本**（倾向） | 分两档体验 | 无自定义协议的项目开箱好用；有自定义的也不会渲染错乱 | 需要一个「是否存在自定义 Formatter」的探测端点；两档体验不一致 |
| ②一律只显示原始 SSE 文本 | 调试台对协议零假设 | 最干净，永不出错，无需探测 | 即使没自定义协议，调试体验也偏原始 |
| ③调试时强制使用框架 debug Formatter，绕过业务实现 | 调试台体验统一 | 渲染始终可用且结构化 | **调试看到的与生产实际下发的不是同一份报文**，容易掩盖协议 bug；违反「所见即所得」 |

---

## 14. 分阶段落地建议

| 阶段 | 内容 |
|---|---|
| **P0（最先做）** | **建 `ace-graph-dsl-ai` 模块 + 迁移**（§12.1）：6 类从 core 迁入、删除 `AgentTool`、core 新增 `GraphBoundAgentNode`/`GenericAgentNodeFactory`、`DynamicGraphBuilder` 改注入工厂、starter 加依赖、单测随迁。**此项完成后其余 P0 才能编译** |
| P0 | `StreamingLlmTemplate` + Resolver Function + ToolDeduper；Agent 切换骨架 |
| P0 | `NamedToolCallback.toModelCallback(conflicted)` 改名包装 + 挂载前 name 唯一性断言 + 映射日志（§5.1.1 / D1） |
| P0 | **工具名合法化**：分隔符改 `__`、`ToolNames.toModelName` sanitize + 64 上限哈希压缩、`assertLegal` 断言；冲突组 description 前置来源标识；system 不拼工具目录（§5.3 / D2） |
| P0 | `StreamResponseKind` 标签 + `TokenChunk.responseKind` + `StreamingContext.getResponseKind()`（框架流式侧全部改动） |
| P0 | **`GET /api/stream-response-kinds` + 配置驱动 Catalog**（含 `ORDER` 比较器）；UI 下拉；默认首位；保存禁止空 KEY |
| P0 | `resolveOrDefault(graphId, raw)` 单一归一化入口（**签名带 graphId**，三调用点贯通）；`StreamResponseKind.of` 空值 fail fast（§9.7.4 / §9.7.6） |
| P0 | `DynamicGraphBuilder#resolveGenericAgent` 编译期 normalize + `GenericAgentSpec.withStreamResponseKind`（§9.7.3） |
| P0 | 同一处遍历收集 `nodeId → streamResponseKind` 映射，供 `ofNode` 路径回落（§9.6.4 / §9.7.3） |
| P0 | `resolveFormatter()` 补一条生效日志（**不改逻辑**）；可选 `KindDispatchingChunkFormatter` 便利基类 |
| P0 | `ResourceBinding` + UI 开关/手动 key（**零兼容映射**） |
| P0 | **旧字段清理**：删 `tools`/`promptKey`/`skillKey`/`mcpKey`/`skill`/`mcp`，`prompt` 改纯追加语义；`mcpToolWhitelist` + `McpToolFilter` 承接 MCP 工具级过滤；`REMOVED_FIELDS` 检出 fail fast；UI MCP 改三级树（§7.1.1 / D3） |
| P0 | `PromptRenderer` 单遍渲染（防注入 + quoteReplacement）+ 统一变量快照 + 集合类 JSON 化 + 长度护栏 + 编译期占位符/`inputKeys` 比对 warn（§4.4.2） |
| P0 | `ModelEndpointResolver`：静态层 key/内联**整路二选一**（不齐报错、禁止拼盘）+ Override 逐字段补丁 + 来源日志 + 编译期并存 warn（§4.4.1） |
| P0 | `LlmRequestContext` 必含 **`agentCode`**：Controller 入口写死并写入 state 保留键 `ace.graph.dsl.agentCode`，节点只读不猜；缺则 error 日志（§4.2） |
| P0 | `LlmResolvers` + `LlmCallRequest`（含 Builder 与校验）+ `CachingChatModelFactory`（LRU 有界）+ 自动配置默认回落；`ChatClientFactory`/`AgentChatClient` 标 deprecated（§4.5） |
| P0 | 注册式 Agent：两字段写入 `GenericAgentDefinition.spec`；节点面板编辑器补资源勾选 + 流式类型；属性面板保持只读+跳转（§11.1） |
| P1 | `AgentResourceCatalog` 列表 API（**不做**默认保存期 key 存在性校验）；Nacos/K8s 示例 |
| P0 | 运行期资源加载失败：**error 日志** + 分层策略（Prompt/Model 硬失败，MCP/Tool/Skill 单项跳过）；`ResourceLoadDiagnostics` |
| P0 | `/debug/stream` 增发 `resource_miss`；调试 UI 展示失败 key 列表（§7.4 / C2） |
| P1 | 可选 `ResourceKeyValidator` 挂钩保存入口（有 Bean 才校验；无则跳过）（§7.4 / C1） |
| P1 | Skill：L1 白名单 + `load_skill` / `read_skill_resource` + 读 `ACE_FORCE_SKILLS_KEY` 预激活（§6 / §6.3.1） |
| P0 | **调试端点** `/execution/{graphId}/debug/stream` + `DebugStreamingChunkFormatter`（带 `nodeId`/`streamKind`/`seq`/`ts`）+ 接口文档版本化；UI 加「调试格式」提示文案（§9.6.7） |
| P0 | 新设计器 API 挂菜单守卫：catalog/kinds→`graph:view`，`/debug/stream`→`graph:validate`（§7.5 / C4）；生产 `/stream` 不挂图编辑菜单 |
| P1 | 调试端点鉴权与图编辑权限对齐（并入 C4）；禁止对生产终端用户开放；UI 调试按钮绑 `MENU.GRAPH_VALIDATE` |
| P1 | 业务侧协议文档：`/stream` 按 `streamKind` 的前端消费契约（框架不提供字段示例） |
| P2 | （可选）AgentCard：绑定 + Resolver + Template 消费 skill/工具/安全，并与独立 keys 定优先级 |
| P2 | 多模态实现（§8.2）：`MediaRef` / `MediaRefs.readFrom` 容错解析 / `MediaRefResolver` 默认实现（含 SSRF 校验、超时、大小与条数上限、mime 缓存）/ Template 组装 / UI 「多模态入参 key」输入框与可达性提示 |
| P2 | MCP 缓存/热刷新；节点内中途切 kind（若需要） |
| P2 | （已取消强制）联合试探 API；需要时由业务基于 `ResourceKeyValidator` 自行暴露 |

---

## 15. 需求对照表

| # | 需求要点 | 方案落点 |
|---|---|---|
| 1 | Prompt 等用 Function 抽象 | `PromptContentResolver` |
| 2 | 请求级模型挂载 | `ModelMountResolver` + Override |
| 3 | 本地 Tools Function | `LocalToolResolver` |
| 4 | MCP Function | `McpToolResolver` |
| 5 | 工具去重与同名区分 | `NamedToolCallback` + `ToolDeduper` |
| 6 | Skill 渐进披露；UI 勾选=白名单 | 第 6 节 |
| 7 | 多模态不截断 | MediaRef/URL；第 8 节 |
| 8 | UI 勾选资源与 key | `ResourceBinding` + PropertyPanel；MCP 为三级树（§7.1.1） |
| 9 | Catalog 列表 API | `AgentResourceCatalog` |
| 10 | 仅 Agent 节点 | 第 11 节 |
| 11 | 原生 Spring AI 骨架 | `StreamingLlmTemplate` |
| + | 流式响应类型 BIZ/OUTPUT/扩展 | 第 9 节 |
| + | UI 选择流式方式 + kinds KEY 加载接口 | 第 9.4 节 `GET /api/stream-response-kinds` |

---

## 16. 附录：相关现状类

- `io.acelance.graph.dsl.agent.GenericAgentNode`
- `io.acelance.graph.dsl.definition.GenericAgentSpec`
- `io.acelance.graph.dsl.streaming.GraphStreamBridge`
- `io.acelance.graph.dsl.streaming.TokenChunk`
- `io.acelance.graph.dsl.execution.StreamingChunkFormatter`
- `io.acelance.graph.dsl.execution.StreamingContext`
- `io.acelance.graph.dsl.execution.DefaultStreamingChunkFormatter`
- Demo：`TranslationService` / `TranslateJaNode`（业务委托模式参考）

---

## 17. 修订记录

| 日期 | 说明 |
|---|---|
| 2026-09-07 | 初稿：模板服务、资源勾选、多模态、BIZ/OUTPUT 流式类型 |
| 2026-09-07 | 补充：UI 必选流式响应方式；`GET /api/stream-response-kinds` 已实现类型 KEY 加载接口 |
| 2026-09-07 | 明确：节点流式 KEY 必选；默认取列表首位；忘记选择则用前端默认 KEY |
| 2026-09-07 | Review：补充 §13.1 闭环缺口清单，并调整分期优先级 |
| 2026-09-07 | 确认：本期不做 AgentCard 绑定/解析/消费及函数接口 |
| 2026-09-07 | 定案：Skill 三层渐进披露；UI `skillKeys` 勾选即节点白名单；`load_skill` Tool 回灌 |
| 2026-09-07 | 明确 Skill 两级勾选：类型总开关 + 条目多选；未勾选条目不在白名单 |
| 2026-09-07 | Prompt（及 MCP/LocalTools）同两级勾选；补充 NODE-A/B/C promptKeys 示例 |
| 2026-09-08 | 定案多模态协议：Agent inputKey 非必填；`MediaRef.url` 必有、`mime` 可为 null 并由 Resolver 五级补全，失败跳过 + warn |
| 2026-09-08 | 定案注册式 Agent 落点（1A+2B+3B）：`resourceBindings` 与 `streamResponseKind` 只存节点定义库、图内不可覆写、节点面板为唯一编辑入口 |
| 2026-09-08 | 多模态补齐闭环：新增 `mediaInputKey`（UI 可填 state key）、state 存 Map + `REPLACE`、SSRF/超时/大小/条数约束、`LlmCallRequest` 与 §10 骨架接线 |
| 2026-09-08 | Skill 补齐闭环：新增 §6.4.1 内置工具命名/去重/越权与路径校验、§6.4.2 多轮与流式关系；§10 骨架注册 skill 工具 |
| 2026-09-08 | ~~定案 Formatter 共存（§9.6）：Codec 为唯一扩展点、全局 Formatter deprecated、Codec 优先 + 三态判定与启动日志；新增内置 `FRAMEWORK` kind 完成 B6 回落~~ **（已被下一条取代）** |
| 2026-09-08 | **重大定案（§9.0）：流式协议归业务，框架只提供挂载入口。** 废弃 `StreamChunkCodec` / Registry / `KindAware` / 内置 BIZ-OUTPUT-FRAMEWORK Codec 全套；`StreamingChunkFormatter` 恢复为唯一且不 deprecated 的入口，`GraphExecutionController` 零改动；框架流式侧仅保留 kind 标签透传（`TokenChunk.responseKind` + `StreamingContext.getResponseKind()`）；kind 目录改为配置驱动；`KindDispatchingChunkFormatter` 降级为可选便利基类。B4/B5/E2 随之消解 |
| 2026-09-08 | 补充定案（§9.6.3）：默认输出**不附带** `streamKind`、不提供 `expose-kind-in-default` 开关；kind 定位为「仅 Java 扩展点可见的标签」，非线上协议字段。框架产出的 SSE 与升级前逐字节一致 |
| 2026-09-08 | 闭环 B8：删除 `defaultSelected` 字段（JSON 契约 + `StreamResponseKindItem`），改为 `order`；「默认 = 列表首位」成为全链路唯一规则 |
| 2026-09-08 | 新增 §13.2 待产品确认清单：B3（节点内中途切 kind）、B7（执行/调试面板渲染）各 3 个候选及取舍；修正 B3 原误标「定案」为待确认。两项均不阻塞 P0 |
| 2026-09-08 | **定案 A4（§4.4.2）：Prompt 变量渲染**。查证发现现网 `{{state.key}}` **从未实现**（javadoc 有承诺、core 无替换代码、责任被推给 `AgentChatClient` 实现方），故本次为首次定义并将渲染收归框架层。五条：①不使用 Spring AI `PromptTemplate`（单花括号与 prompt 内 JSON 示例冲突），传入已渲染纯文本；②`{{state.key}}` 单命名空间、前缀可选；③**严格单遍替换**，替换值不再参与扫描（防模板注入），替换时用 `Matcher.quoteReplacement` 规避 `$`/`\` 组引用；④system/user 共用**同一变量快照**，多 promptKeys 先合并后渲染一次；⑤skill L1 目录与工具目录在渲染后追加。缺失变量默认空串+warn（可切严格模式），`Map`/`List` 走 JSON 序列化。新增编译期占位符与 `inputKeys` 双向比对 warn，可回吐 UI 做自动补全（P1）；新增单变量/总长护栏防上下文击穿 |
| 2026-09-09 | **补定 §6.3.2：forceSkills 多节点传递**。入口写入的 `ace.graph.dsl.forceSkills` 随 OverAllState 贯穿整次 run，能传到第 N 个节点。前序节点白名单不含该 skill → 只跳过+日志、**不删列表**；第 N 个节点勾选后才预激活。若前序节点也勾了同一 skill 则会在那些节点也激活——「只在第 N 个用」靠白名单控制，不靠框架按节点序号丢弃。默认激活后不从 state 清除 |
| 2026-09-09 | **补定 §6.3.1：用户/上游指定 Skill 的格式**。此前只写「可强制 load_skill」未写格式。定案：框架唯一认 state 保留键 `ace.graph.dsl.forceSkills`（JSON 数组 / `List&lt;String&gt;`，元素与 UI skillKeys 同一 key，如 `skill.refund`）；进节点前按序预激活。终端聊天口令（`/skill`、自然语言等）**框架不规定**，业务解析后写入该键——与「流式协议归业务」一致。模型自选则调 `ace__skill__load_skill`，参数 `{"code":"skill.refund"}`。不在白名单则跳过+日志；不做框架内正则抠用户原文 |
| 2026-09-09 | **修订 A5（§4.4.1）：取消静态层逐字段拼盘**。原「baseUrl/apiKey/modelId 各路取第一个非空」会导致：勾了 modelConfigKey 但注册中心缺 modelId 时，静默用上内联 modelId，开发和用户都觉得怪。改为：**静态层整路二选一**（有 key 用 key，否则用内联），选中路不齐就**报错**，禁止跨路补缺；**仅请求级 Override** 仍可在底座上逐字段补丁（只改 modelId 做 A/B 的合理需求）。异常与不完整一律 fail fast |
| 2026-09-08 | **定案 A5（§4.4.1）：模型来源优先级 = 请求级 Override > modelConfigKey（需 enableModel）> 节点内联字段**。意图是让注册中心成为权威来源（改一处 key 指向即全局切换模型），内联字段退化为兜底与本地调试。明确四点：①**逐字段独立取首个非空**而非整体替换，延续现网 `withOverride` 语义，使「只覆盖 modelId」不会丢掉 baseUrl/apiKey；②`enableModel=false` 含义为「跳过 key 这一路并省掉远程调用」，**不是禁用节点**；③**key 解析抛异常时 fail fast、不回落内联**（异常=故障，静默降级会让线上悄悄用错模型），仅「正常返回但字段为空」才逐字段回落；④三路皆空 fail fast 并提示三种补救方式。api-key 仅在「内联且 `apiKeyMasked`」时走 SecretResolver，Override/注册中心来源视为明文；日志打印各字段来源但**不打 key 值**。新增框架内部 `ModelEndpointResolver`（业务 SPI `ModelMountResolver` 仅负责 key 这一路，保持单一职责）与 `InlineModel`；`LlmCallRequest` 增 `inlineModel`/`modelOverride` 两字段。**副作用与缓解**：勾选 Model 并填 key 后内联字段静默失效，故要求编译期检测二者并存并 warn |
| 2026-09-08 | **闭环 A6（§4.5）**：纠正早期骨架把 Resolver 当请求级字段的概念错位（原致 `LlmCallRequest` 需 18 个构造参数），拆为**单例 `LlmResolvers`**（10 项 + 非空校验）与**请求级 `LlmCallRequest`**（8 字段 + Builder + 参数归一）；`binding` 唯一来源为 `context.binding()`；新增 `ChatModelFactory`（返回原生 `ChatModel`），默认 `CachingChatModelFactory` 按端点 LRU 有界缓存（上限 64，超限 warn 提示动态 api-key 误用），api-key 严禁进日志与异常；`ChatClientFactory`/`AgentChatClient` 标 deprecated 且不提供桥接（`String` vs `ChatModel` 语义不可逆）。§10 骨架调用点同步改为 `resolvers.xxx()` 并补充耗时日志 |
| 2026-09-08 | **定案 B7（§9.6.7）：ace-graph-dsl-ui 调试界面只用框架标准 SSE 格式**。理由：ui 为通用产品，跟随业务协议将导致每接一个业务方都要做一次前端协议适配。落地为新增独立端点 `/execution/{graphId}/debug/stream` + 内置 `DebugStreamingChunkFormatter`（含 `nodeId`/`streamKind`/`seq`/`ts`），刻意不调 `resolveFormatter()` 使业务 Bean 不介入；调试格式属框架自有契约故**可带 `streamKind`**，与 §9.6.3「默认输出不带」不矛盾。风险「调试所见≠生产所下发」由 UI 明示提示 + 业务压 `/stream` 抓包应对。§9.6.1「控制器零改动」修正为「**现有端点**零改动 + 新增调试端点」。流式类型 B1–B8 至此全部闭环 |
| 2026-09-08 | **定案 A8（§12.1）：新建 `ace-graph-dsl-ai` 模块 + 彻底迁移**。核查发现 backend 原先无任何模块直接依赖 spring-ai（`AgentTool` 刻意隔离），与需求 11 冲突。定案：模型层全部移入 ai 模块、删除 `AgentTool` 统一到 `ToolCallback`、core 保持干净并新增 `GraphBoundAgentNode`/`GenericAgentNodeFactory` 两个抽象（依赖倒置），`DynamicGraphBuilder` 改为注入工厂、缺失时给可操作报错；starter compile 依赖 ai。该项为 P0 最先做，否则其余 P0 无法编译 |
| 2026-09-08 | **闭环 D1（§5.1.1）**：Spring AI 从 `ToolCallback.getToolDefinition().name()` 读工具名，仅改 record 字段无效。定案 `toModelCallback()` 包装改名（保留 description/inputSchema）、同名不多包一层、挂载前断言 name 全局唯一 fail fast、日志打印 `原名->uniqueName`。`NamedToolCallback.source` 由字符串改为 `ToolSource` 枚举 |
| 2026-09-09 | **定案 `agentCode`（§4.2）**：`LlmRequestContext` 必含 `agentCode`。在 Controller 入口写死（或按路径解析一次），写入 state 保留键 `ace.graph.dsl.agentCode`，与 `runId` 一样贯穿整次执行；图内每个 Agent 节点只读该键填入上下文，**禁止在节点内按 nodeId/graphId 猜默认**。与 graphId（哪张图）、nodeId（哪个节点）、runId（哪次执行）分工明确。框架不提供全局默认码；缺键打 error。Resolver/日志/观测一律可从 `ctx.agentCode()` 取值；Catalog 的 `agentId`（节点定义 id）不等于 `agentCode` |
| 2026-09-08 | **文风加严**：方案糊了不但读者费解，后续 review 也会「不知道自己写了什么」而靠聊天/代码推断。定案必须自带五句话（结论、对象、怎么做、不做什么、怎么验收）；缺口表「已定案」若链过去仍要猜，视为未闭环。review 以本节白纸黑字为准，不得用推断补结论 |
| 2026-09-08 | **定案 C4（§7.5）：设计器接口鉴权对齐现网菜单权限**。澄清「与图编辑权限对齐」并非新造权限模型，而是把 catalog / kinds / `/debug/stream` 挂到现有 `GraphMenuAccessControl` + `MenuPermissionGuard`。映射表：`GET /api/agent-resources/**` 与 `GET /api/stream-response-kinds` → `graph:view`（Agent 库场景允许 OR `agent-node:view`）；dry-run 与 **`/debug/stream`** → `graph:validate`；Agent 试跑保持 `agent-node:test`。生产 `/stream` **明确不挂**图编辑菜单（受众是业务运行态，由宿主 Security/网关保护；框架仅用 `ace.graph.dsl.web.execution.enabled` 开关）。权限配置入口：宿主实现 `GraphMenuAccessControl` Bean（见 `MENU_PERMISSION_INTEGRATION.md`），前端 `stores/permissions.js`；未接入默认全放行。资源内容级 ACL（某个 prompt key 谁能看）不归菜单管，由业务 Catalog/Resolver 过滤。不新增菜单 key，降低宿主映射成本 |
| 2026-09-08 | **定案 C1/C2（§7.4）：资源 key 默认先保存、运行期验证**。prompt/mcp/skill/model/localTool **不做**默认保存期存在性校验（流式 kind 仍按 §9.4 必选校验）。理由：资源常在外部系统、Catalog≠Resolver、硬拦会误伤「先画图后上资源」。运行期加载失败必须 **error 日志**（graphId/nodeId/type/key）；失败策略分层——Model/Prompt 硬失败，MCP/LocalTool/Skill 单项跳过继续。调试 `/debug/stream` 增发 `resource_miss`（仅调试、不进生产 `/stream`），UI 列出失败 key。可选 `ResourceKeyValidator`：业务能廉价判断时提供 Bean，保存期才拦截 MISSING/ERROR；无 Bean 则跳过。明确**不用** Catalog.list 差集冒充校验。C1「同一 key 空间」收束为软约定 + 可选 Validator + 运行期真相 |
| 2026-09-08 | **定案 C3（§9.7.6）：kinds 的 `graphId` 语义**。默认实现忽略 `graphId` 返回全量、按图/租户过滤交由业务覆盖 Catalog Bean（沿用原建议），但发现一处必改项：原 `resolveOrDefault` 写死 `catalog.list(null)`，而 UI 调 kinds API 传的是 `graphId`——业务一旦覆盖 Catalog 做过滤，此不对称将使 **B1 已闭环的「设计期看到 A、运行期跑成 B」重新裂开**（UI 取过滤后首位、运行期取全量首位），按租户过滤时更构成**越权**。故 `resolveOrDefault(graphId, raw)` 与 `normalizeStreamKind(graphId, …)` 一律带 `graphId` 并贯通三个调用点（`LlmRequestContext.graphId()` / `DynamicGraphBuilder` / kinds API 均现成）。追加覆盖约束两条：同一 `graphId` 须返回同一列表（幂等、排序稳定，因 UI/编译期/运行期三处独立调用）；实现须为廉价内存操作、查远程须自行缓存（运行期每次 `execute` 均调用，否则远程调用被放大到每节点每次执行）。已存 KEY 落到过滤范围外沿用 warn + 回落该图首位，不 fail fast |
| 2026-09-08 | **定案 D3（§7.1.1）：零兼容映射 + 旧字段清理 + MCP 工具级过滤补位**。前提为确认无存量图，故取消原「`promptKey` → `promptKeys=[promptKey]`」等全部自动转换。查证纠正一处误判：`spec.tools` **并非本地工具 key，而是 MCP 工具级过滤白名单**（现网与 `serverConfig.tools()` 取交集、未声明则取全集），故删它属**功能缺口**而非兼容问题；工具级过滤为刚需（几十个工具全量挂载同时恶化 token、选择准确率与安全暴露面）。定案：能力平移为 `ResourceBinding.mcpToolWhitelist`（`serverKey → 工具名`，缺省=全选），两级白名单分工为「server 级由业务 Resolver 施加、节点级由框架 `McpToolFilter` 施加」——白名单兼安全边界，下放 SPI 则任一实现漏做即形成绕过，且 `list_tools` 本就全量返回、框架过滤无额外远程开销。旧字段：`promptKey`/`skillKey`/`mcpKey`/`skill`/`mcp` 全删（均为跨节点共享资源，天然应注册后按 key 引用），**仅保留 `prompt`** 并将语义由「与 key 二选一」改为**纯追加**（拼在 promptKeys 之后、无优先级、不影响 §4.4.2 单遍渲染）。废弃字段检测分层：反序列化保留 `ignoreUnknown=true` 以保灰度期两版本互读，保存与编译入口检出即 fail fast 并给出替代项——因 `ignoreUnknown` 会静默吞掉残留字段，使人误以为配置生效。连带删除 `McpToolProvider`/`InMemoryMcpToolProvider`（职责归 `McpToolResolver`），保留 `McpServerConfig` |
| 2026-09-08 | **定案 D2（§5.3）：关闭短名，模型可见名一律用 uniqueName，system 不写工具目录文案**。查证中发现一处会让请求直接失败的缺陷：原格式 `mcp:weather:get_forecast` **含冒号，违反端点 `^[a-zA-Z0-9_-]{1,64}$` 约束会被 400 拒绝**（`ace:skill:load_skill` 同），故分隔符改 `__` 并 sanitize，超 64 时保留 originalName 尾部 + 8 位哈希（哈希基于完整 uniqueName，压缩后仍唯一）。核心澄清：**技术路由与语义选择是两层**——name 闭环（发出与回查同一字符串）保证路由正确，但两个 MCP 的同名工具改名后 description 仍相同，模型无从选择，故冲突组内 description 前置 `[来源: {serverKey}]`，无冲突则原样保留；§5.1.1「description 原样保留」修正为仅适用无冲突场景。不写 system 目录：工具经请求体 `tools` 参数下发不走 prompt，重复一遍既每轮烧 token 又可能与实际 tools 不一致。不做「无冲突用短名」：会使工具名随新增 MCP 突然变化，破坏 prompt 与埋点稳定性。顺带取消 §6.4.1 短名保留名规则（统一前缀后天然不撞）；挂载前断言升级为唯一性 + 合法性两条 |
| 2026-09-08 | **定案 B3 = ①首期不做**：一个节点固定一个 kind；「节点A(BIZ 思考/处理) → 节点B(OUTPUT 总结)」编排已完整支持——带外通道保证前端实时收字（`blockLast()` 只阻塞图引擎不影响前端）、`outputKey`→`inputKeys` 传递完整文本、片段携带 `nodeId`+kind 供前端路由渲染。将来如需单节点内分段可平滑追加 `emitKind` 可选 API，不影响已落库图。流式类型仅剩 B7 待拍 |
| 2026-09-08 | 定案 B1/B2（§9.7）：空 KEY / 无效 KEY 归一化采用**编译期 normalize + 运行期兜底**双保险，统一走 `resolveOrDefault` 并与 UI 默认同源（同一 Catalog、同一 `ORDER` 比较器）；落点 `DynamicGraphBuilder#resolveGenericAgent`，同处收集 `nodeId → kind` 映射顺带闭环 B6；明确不做保存时 normalize，且不在 `GenericAgentSpec` 构造器兜底（默认值依赖运行时配置） |
| 2026-09-09 | **澄清设计期 vs 运行期（§4.3 / §7.2）**：UI 勾选的 promptKeys/mcpKeys 等**设计期尚不存在**，不能作为「按 agentCode 初筛」的入参。初筛走 Catalog.list（入参 agentCode，无节点 keys）；运行期 Resolver.resolve(ctx, keys) 时 keys 已从节点 Binding 落库，可同时读 agentCode 做范围校验。Catalog 查询参数改为 `agentCode` + 可选 `agentDefId`（原 agentId 易混淆） |
| 2026-09-10 | **补定 §4.2.1：Controller 如何选对图**。必须有明确 `graphId`；现网口 `POST /execution/{graphId}/stream`；业务可写死/映射 graphId，框架不按 agentCode 猜图。补全入口样例（常量 GRAPH_ID + agentCode + runId）。**补定 §7.1：`ResourceBindings.fromSpec` 归框架**——纯 Spec→Binding 字段映射，业务不实现；业务只实现按 key 取数的 Resolver。此前样例裸写 graphId/fromSpec 未交代归属，视为文档缺口已闭环 |
| 2026-09-10 | **补定 §8.3：节点间大结果与产物 URL 投递**。默认完整结构化结果进 `OverAllState` 透传（`outputKey`→`inputKeys`）；精简发生在消费方拼 prompt（§4.4.2 护栏），框架不在节点边界自动摘要。文件本体不进 state，只传 URL/mediaId（与 MediaRef 同原则）。推荐拆 `summary` / `detail` / `artifacts` 多 key；Agent 的 outputKey 放下游默认读的那份 |
| 2026-09-10 | **补定 §8.3 原生写回样例**：节点通过 `NodeAction.apply` **return Map** 由引擎按 `KeyStrategy` 合并进 state（非手写 `state.put`）；附原生双节点样例 + 现网 `GenericAgentNode` return `outputKey` 片段；`keyStrategies` 须覆盖输出 key |
| 2026-09-10 | **补定 §8.3：ace-graph-dsl 指定 key 赋值**。Agent 写回 key 唯一来源是配置字段 `outputKey`（默认 `agent_result`），整段模型回复进这一个 key。提示词只能约束正文形态（含 JSON），框架不解析、不拆多 key；Structured Output 首期不做。多 key 靠下游解析节点或业务 NodeAction `return` 多 entry |
