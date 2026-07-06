# Ace Graph DSL — 内置图（Built-in Graph）集成指南

> 版本：v1.0  
> 日期：2026-07-06  

---

## 一、概述

内置图（Built-in Graph）是宿主应用在代码中通过 `StateGraph` API 定义的图拓扑，注册后可通过 ace-graph-dsl 的设计器 API 供前端**只读渲染**查看，但**不持久化**到任何存储媒介（SQLite / Redis / MySQL）。

### 与持久化 DSL 图的区别

| 特性 | 内置图（内存） | 持久化 DSL 图（DB） |
|------|:---:|:---:|
| 定义方式 | 代码 StateGraph | 前端设计器 / boostrap JSON |
| 存储 | `BuiltinGraphRegistry`（内存） | `GraphDefinitionRepository`（DB） |
| 前端编辑 | ❌ 只读 | ✅ 可编辑 |
| 版本管理 | 单版本 | 完整版本历史 |
| 发布/运行 | 不影响 | 通过 `GraphRuntime` |

两者完全独立，互不影响。`listSummaries()` 会自动合并两者。

---

## 二、架构原理

### 数据流

```
宿主应用代码
    │ 原生 spring-ai-alibaba-graph StateGraph API
    │ .addNode("intake", action).addEdge(START, "intake")
    ▼
StateGraph 对象
    │ GraphDefinition.fromStateGraph()
    │ 反射提取 StateGraph.nodes / StateGraph.edges
    ▼
GraphDefinition (bootstrap=true)
    │ BuiltinGraphRegistry.register()
    ▼
内存 Map<String, GraphDefinition>
    │  合并返回
    ▼
GET /api/graph/catalog/summaries ──┐
GET /api/graph/definitions/{id}   ──┤  自动回退到 BuiltinGraphRegistry
POST .../draft                     ──┤  内置图拒绝写入
```

### 核心类

| 类 | 职责 |
|----|------|
| `GraphDefinition.fromStateGraph()` | 反射提取 StateGraph 拓扑 → GraphDefinition |
| `BuiltinGraphRegistry` | 内存级注册中心，管理所有内置图 |
| `GraphCatalogController` | 合并 `BuiltinGraphRegistry` + `GraphDefinitionRepository` 返回目录 |
| `GraphDefinitionController` | 查询时回退到 `BuiltinGraphRegistry`，写操作拒绝内置图 |

### `fromStateGraph()` 反射原理

```
StateGraph (spring-ai-alibaba-graph)
  ├── final Nodes nodes       ← 反射访问
  │     └── elements: Set<Node>
  │           └── Node.id() → "intake_normalize"
  └── final Edges edges       ← 反射访问
        └── elements: List<Edge>
              ├── Edge.sourceId() → "standard_entry"
              └── Edge.targets() → List<EdgeValue>
                    ├── EdgeValue.id() → 目标节点
                    └── EdgeValue.value() → EdgeCondition（条件边有）
                          └── EdgeCondition.mappings() → Map<路由key, 目标节点>
```

- **普通边**：`targets` 中所有 `EdgeValue.value() == null` → 生成独立 `GraphEdge.TYPE_NORMAL`
- **条件边**：存在 `EdgeValue.value() != null` → 从 `EdgeCondition.mappings()` 提取路由映射 → `GraphEdge.TYPE_CONDITIONAL`
- **并行边**：如 `addEdge("standard_entry", "rag_retrieve")` + `addEdge("standard_entry", "sentiment_intent")` → 生成多条 TYPE_NORMAL 边

---

## 三、快速开始

### 3.1 依赖

确保项目已引入 `ace-graph-dsl-spring-boot-starter`：

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.2 最小示例

```java
@Configuration
public class MyBuiltinGraphConfiguration {

    private final BuiltinGraphRegistry registry;

    // 构造注入 BuiltinGraphRegistry
    public MyBuiltinGraphConfiguration(BuiltinGraphRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerBuiltinGraph() throws GraphStateException {
        StateGraph stateGraph = new StateGraph(() -> {
            Map<String, KeyStrategy> s = new HashMap<>();
            s.put("key1", new ReplaceStrategy());
            return s;
        })
            .addNode("step1", node_async(state -> Map.of()))
            .addNode("step2", node_async(state -> Map.of()))
            .addEdge(StateGraph.START, "step1")
            .addEdge("step1", "step2")
            .addEdge("step2", StateGraph.END);

        GraphDefinition def = GraphDefinition.fromStateGraph(
            stateGraph,
            "my-builtin-graph",    // graphId
            "我的内置图",           // displayName
            "1.0.0",               // version
            "一个简单的内置流程图"   // description
        );

        registry.register(def);
    }
}
```

### 3.3 完整示例（含条件边 + HITL）

参考 `CsReplyM2BuiltinGraphConfiguration.java`（位于 demo 模块），展示了：

- KeyStrategyFactory 的定义
- 条件边（`addConditionalEdges`）+ 路由映射
- 编译配置（`interruptBefore`/`saver`）
- 完整拓扑提取和注册

---

## 四、API 说明

### 4.1 GraphDefinition.fromStateGraph()

```java
// 简化版
GraphDefinition.fromStateGraph(stateGraph, graphId, displayName, version, description);

// 完整版（含编译配置）
GraphDefinition.fromStateGraph(stateGraph, graphId, displayName, version, description,
    List.of("human_review"),  // interruptBefore
    "memory"                  // saver
);
```

返回的 `GraphDefinition` 自动设置 `bootstrap=true`，前端识别为只读。

### 4.2 BuiltinGraphRegistry

```java
// 注册
registry.register(graphDefinition);

// 查询
GraphDefinition def = registry.get("my-builtin-graph");
List<GraphDefinition> all = registry.listAll();
boolean exists = registry.contains("my-builtin-graph");
```

### 4.3 前端效果

- 目录列表中显示**黄色「内置」标签**
- 点击后画布渲染拓扑，可**校验**、**预览 PlantUML**
- 工具栏隐藏**保存草稿**和**发布**按钮
- 显示橙色「内置图 · 只读」标识

---

## 五、与已有 StateGraph Bean 共存

宿主应用可能已有使用原生 `StateGraph` 定义的运行时图 Bean：

```java
@Bean
public StateGraph csReplyM2Graph(...) { ... }

@Bean
public CompiledGraph csReplyM2CompiledGraph(StateGraph csReplyM2Graph) { ... }
```

内置图**不影响**这些 Bean：

- 运行时图 Bean 继续负责消息处理/执行
- 内置图仅负责前端拓扑可视化
- 两者通过不同的路径工作，完全解耦

---

## 六、注意事项

1. **NodeAction 可用 dummy**：`fromStateGraph()` 只提取拓扑，不需要真实业务逻辑，`node_async(state -> Map.of())` 即可
2. **注册时机**：建议在 `@EventListener(ApplicationReadyEvent.class)` 中注册，确保所有基础设施就绪
3. **graphId 唯一性**：内置图 graphId 不应与其他持久化 DSL 图冲突，内置图优先级更高
4. **不持久化**：重启后需重新注册，配置类确保每次启动都执行
5. **NodeRegistry 联动**：节点显示名由 `GraphNodeRegistry` 中的 NodeBean descriptor 提供，需确保节点已注册
