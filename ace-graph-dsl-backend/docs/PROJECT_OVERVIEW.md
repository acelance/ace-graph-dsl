# Ace Graph DSL — 项目总览（前后端架构说明）

> 版本：v1.0
> 日期：2026-06-30
> 范围：`ace-graph-dsl-backend`（后端库） + `ace-graph-dsl-ui`（前端组件库）

---

## 1. 项目定位

`ace-graph-dsl` 是一套**可视化图编排（Graph Orchestration）平台**，基于阿里 [spring-ai-alibaba-graph](https://github.com/alibaba/spring-ai-alibaba)。

- **前端**拖拽设计流程图，产出 JSON 格式的 Graph DSL。
- **后端**将 DSL 编译为可执行的 Spring AI Alibaba `CompiledGraph`，并提供版本管理、热发布、回滚、节点注册中心等能力。
- 两端通过统一的 `/api/graph/**` REST 接口对接。

```
┌─────────────────┐    JSON DSL / REST    ┌──────────────────────────┐
│ ace-graph-dsl-ui │ ───────────────────→ │ ace-graph-dsl-backend     │
│ (Vue 设计器)     │ ←─────────────────── │ (Spring Boot Starter)     │
└─────────────────┘   节点/Dispatcher 元数据 └──────────────────────────┘
```

---

## 2. 后端：`ace-graph-dsl-backend`

Maven 多模块项目，作为可被宿主 Spring Boot 应用引入的库使用。

### 2.1 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.5.10 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.2 |

### 2.2 模块划分

| 模块 | 职责 |
|------|------|
| `ace-graph-dsl-core` | DSL 模型、编译器、运行时、注册中心、脚本引擎、安全控制 |
| `ace-graph-dsl-persistence` | 持久化实现：SQLite（默认）、Redis、JDBC、内存 |
| `ace-graph-dsl-spring-boot-starter` | Spring Boot 自动配置 + REST Controller，引入即用 |

### 2.3 核心运行机制

整体数据流：

```
GraphDefinition (DSL 模型)
   → DynamicGraphBuilder.build()  （校验 → 构建 StateGraph → 编译）
   → Spring AI Alibaba CompiledGraph
   → GraphRuntime（已启用 CompiledGraph 池，支持热更新）
```

- **`DynamicGraphBuilder`**（`builder/DynamicGraphBuilder.java`）
  - 先经 `GraphValidator` 校验，再构建 `StateGraph`。
  - 将 DSL 保留字 `__START__` / `__END__` 映射为 `StateGraph.START` / `StateGraph.END`。
  - 支持普通边（`addEdge`）和条件分发边（`addConditionalEdges`，由 Dispatcher 决定路由）。
  - `keyStrategies` 映射为 `ReplaceStrategy`（覆盖）/ `AppendStrategy`（追加）。
  - 编译配置支持 `interruptBefore`（HITL 中断点）与 `saver`（当前统一落到 `MemorySaver`）。

- **`GraphRuntime`**（`store/GraphRuntime.java`）
  - 使用 `ConcurrentHashMap` 维护已启用的 `CompiledGraph` 池。
  - 启动时 `@PostConstruct` 加载所有 enabled 版本并编译。
  - `publish` / `rollback` 走「校验 → 编译 → 切换 enabled → 热更新内存」流程，实现**无需重启发布**。

### 2.4 扩展点与注册中心

| 扩展点 | 接口 | 说明 |
|--------|------|------|
| 业务节点 | `RegisteredGraphNode` | 实现 `descriptor()` 和 `toAction()`，标注 `@Component` 自动收集 |
| 条件边 Dispatcher | `RegisteredEdgeDispatcher` | 实现 `dispatcherId()`、`possibleTargets()`、`toAction()` |
| 持久化 | `GraphDefinitionRepository` | 自定义实现并注册为 Bean，覆盖自动配置 |

- **`GraphNodeRegistry`** 采用**双层注册**：
  - 静态 Spring Bean 节点（`BUILTIN`）：启动时一次性注入，不可变。
  - 动态脚本节点（`SCRIPT`）：运行时可增删，防止与内置节点 ID 冲突。

### 2.5 动态脚本节点（运行时扩展）

`script/` 包提供基于 **Aviator** 的脚本引擎（`AviatorScriptEngine`），允许在运行时通过 API 创建脚本节点，**无需编写 Java 代码、无需重新部署**：

- `DynamicNodeDefinition`（`definition/DynamicNodeDefinition.java`）持久化脚本节点定义（脚本体、引擎、输入/输出 key、权限标签等）。
- `ScriptNodeFactory` 将定义转为 `ScriptRegisteredGraphNode` 并注册到 `GraphNodeRegistry`。
- 相关设计背景见 [`NODE_FLEXIBILITY_EXPLORATION.md`](./NODE_FLEXIBILITY_EXPLORATION.md)。

### 2.6 安全与权限

`security/` 包提供节点级访问控制：

- `GraphNodeAccessControl`：权限判定抽象（默认 `PermissiveGraphNodeAccessControl` 放行全部）。
- `NodeAccessFilter`：按权限标签过滤 `GET /api/graph/nodes` 与 `/dispatchers` 的返回，实现多租户/多角色节点可见性。

### 2.7 持久化后端

| type | 说明 |
|------|------|
| `sqlite` | 本地 SQLite 文件（默认 `~/.ace-graph-dsl/graph-dsl.db`），适合开发与小规模部署 |
| `redis` | 需配置 `spring.data.redis`，适合分布式环境 |
| `jdbc` | 复用宿主应用 DataSource，适合已有数据库的场景 |
| `auto` | 自动选择：`prefer-redis` 且 Redis 可用 → Redis；有非 SQLite DataSource → JDBC；否则 → SQLite |

---

## 3. 前端：`ace-graph-dsl-ui`

发布为 npm 包 `@acelance/graph-dsl-ui` 的 Vue 3 组件库。

### 3.1 技术栈

| 依赖 | 用途 |
|------|------|
| Vue 3 | 组件框架 |
| Pinia | 状态管理 |
| Element Plus | UI 组件 |
| LogicFlow | 流程图画布 |
| Axios | HTTP 客户端 |

### 3.2 对外组件

| 组件 | 说明 |
|------|------|
| `GraphDslManager` | 带左侧目录的完整管理页 |
| `GraphDslDesigner` | 单 Graph 设计器（emit `saved` / `published`） |
| `DesignerToolbar` / `DesignerNodePanel` / `DesignerCanvas` / `DesignerPropertyPanel` | 可拆分组合的子组件 |
| `ScriptNodeEditor` | 脚本节点编辑器，对应后端动态脚本节点能力 |

### 3.3 状态与数据流

- **`stores/graphEditor.js`**（编辑器核心）
  - `setFromLfData()`：将 LogicFlow 画布数据转为 DSL。
  - `normalizeDefinition()`：清洗（剔除保留节点、统一 START/END、条件边去重、丢弃自环）。
  - `buildDefinition()`：产出标准 `GraphDefinition`。
  - 封装 `save` / `validate` / `loadPlantUml` / `publishCurrent` / `loadLatest` 等动作。
  - 会基于节点 `outputKeys` 自动补全 `keyStrategies`（默认 `REPLACE`）。
- **`stores/nodeRegistry.js`**：节点 / Dispatcher 注册表缓存。
- **`api/graph.js`**：`createGraphApi(baseURL)` 工厂封装全部 `/api/graph/**` 接口（含脚本节点 CRUD、`validate-script`、`test-run`）。

### 3.4 本地联调

- 开发时通过 Vite 代理 `/api` → 后端（默认 `http://127.0.0.1:8087`）。
- Demo 入口 `demo/main.js` 挂载 `GraphDslManager`。

---

## 4. 端到端流程

```
[UI 拖拽设计]
   → buildDefinition() 生成 JSON DSL
   → POST /api/graph/definitions/{graphId}/draft     (保存草稿)
   → POST /api/graph/definitions/{graphId}/validate  (校验节点引用、边连通性)
   → POST /api/graph/definitions/{graphId}/publish   (发布)
        ↓ 后端 GraphRuntime
        校验 → 编译 CompiledGraph → 切换 enabled 版本 → 热更新内存池
   → 业务方 GraphRuntime.get(graphId) 获取最新可执行图
```

回滚流程与发布类似，直接切换到目标历史版本并重新编译。

---

## 5. Graph DSL 模型（速查）

```json
{
  "graphId": "cs-reply-m2",
  "displayName": "客服回复 M2",
  "version": "1.0.0",
  "keyStrategies": { "messages": "APPEND", "context": "REPLACE" },
  "nodes": [
    { "nodeId": "intake_normalize", "config": {} },
    { "nodeId": "llm_reply", "config": { "temperature": 0.7 } }
  ],
  "edges": [
    { "from": "__START__", "to": "intake_normalize", "type": "normal" },
    { "from": "intake_normalize", "to": "llm_reply", "type": "normal" },
    {
      "from": "llm_reply", "to": "", "type": "conditional",
      "dispatcher": "inquiryDispatcher",
      "mapping": { "handle_inquiry": "handle_inquiry", "__END__": "__END__" }
    }
  ],
  "compile": { "interruptBefore": ["human_review"], "saver": "memory" }
}
```

| 保留字 | 含义 | | 节点类别 | 含义 |
|--------|------|-|----------|------|
| `__START__` | 图起点 | | `NORMAL` | 普通处理节点 |
| `__END__` | 图终点 | | `ROUTER` | 路由节点 |
| | | | `MERGE` | 并行分支合并节点 |
| | | | `HITL` | Human-in-the-Loop 人工介入节点 |

---

## 6. 相关文档

- [后端 README](../README.md) — 后端使用说明、配置项、完整 REST API
- [前端 README](../../ace-graph-dsl-ui/README.md) — 前端组件库使用说明
- [节点灵活性增强探索方案](./NODE_FLEXIBILITY_EXPLORATION.md) — 动态节点 / 脚本 / 权限设计背景
