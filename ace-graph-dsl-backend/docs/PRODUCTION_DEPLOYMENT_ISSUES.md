# Ace Graph DSL — 生产部署问题分析与解决方案

> 版本：v1.0  
> 日期：2026-07-03  
> 状态：方案讨论，待人工评审后实施

---

## 1. 问题清单

本文分析四个生产级部署中暴露的架构问题：

| # | 问题 | 严重度 | 推荐方案 | 预估成本 |
|---|------|--------|---------|---------|
| 1 | 孤儿脚本节点检测 | 🟡 低 | MySQL JSON_EXTRACT 视图查询 | <0.5 人日 |
| 2 | 脚本节点修改/删除 UI + 引用检查 | 🟠 中 | NodePanel 扩展 + 删除前引用检查 | 2-3 人日 |
| 3 | 多实例图发布通知 | 🔴 高 | `GraphRuntime.get()` 懒加载 | 0.5 人日 |
| 4 | 多实例脚本节点注册通知 | 🔴 高 | `DynamicGraphBuilder.build()` 编译前按需加载 | 0.5 人日 |

---

## 2. 问题 1：孤儿脚本节点检测

### 2.1 现状

`ace_graph_dsl_definition.content_json` 存储了完整的图定义 JSON，其中包含 `nodes[].nodeId`。但没有独立的 `graph_id ↔ node_id` 映射表，无法用 SQL 快速查出：

- 哪些节点被哪些图引用
- 哪些节点无任何图引用（孤儿节点）

### 2.2 影响

孤儿脚本节点不会导致运行时错误，仅浪费存储空间。真实风险场景是：

```
用户创建 script:A → 在图中使用 → 从图中移除 → 节点留在库中无引用
```

大量积累后影响 DB 查询性能和维护可读性。

### 2.3 MySQL 5.7 兼容性

**完全兼容**。MySQL 5.7.8+ 内置 JSON 类型和全套函数：

| 函数 | 5.7 支持 | 说明 |
|------|---------|------|
| `JSON_EXTRACT(doc, path)` | ✅ 5.7.8+ | 提取 JSON 路径值 |
| `JSON_CONTAINS(doc, val)` | ✅ 5.7.8+ | 检查是否包含指定值 |
| `JSON_QUOTE(str)` | ✅ 5.7.8+ | 字符串转 JSON 值 |

### 2.4 方案：视图查询

```sql
-- 查找孤儿脚本节点（未被任何图定义引用）
SELECT n.node_id, n.display_name
FROM ace_graph_dsl_node_definition n
WHERE n.enabled = 1
  AND NOT EXISTS (
    SELECT 1 FROM ace_graph_dsl_definition d
    WHERE JSON_CONTAINS(
      JSON_EXTRACT(d.content_json, '$.nodes[*].nodeId'),
      JSON_QUOTE(n.node_id)
    )
  );

-- 查找某个节点被哪些图引用
SELECT d.graph_id, d.version
FROM ace_graph_dsl_definition d
WHERE JSON_CONTAINS(
  JSON_EXTRACT(d.content_json, '$.nodes[*].nodeId'),
  JSON_QUOTE('script:append')
);
```

**性能注意**：MySQL 5.7 不能给 JSON 列内部字段建索引，大量图定义时全表扫描。建议：
- 当前数据量下（通常几十个图）直接使用，无性能问题
- 后续量大后可在 `node_definition` 表加 `referenced_by_count` 冗余列，发布时增量更新

### 2.5 实现建议

加一个管理 API `GET /api/graph/nodes/orphans`，无需改持久化层。

---

## 3. 问题 2：脚本节点管理 UI + 引用检查

### 3.1 现状

| 能力 | 后端 API | 前端 UI |
|------|----------|---------|
| 创建 | `POST /nodes` ✅ | `ScriptNodeEditor.vue`「创建」✅ |
| 修改 | `PUT /nodes/{nodeId}` ✅ | **无入口** ❌ |
| 删除 | `DELETE /nodes/{nodeId}` ✅ | **无入口** ❌ |
| 校验 | `POST /nodes/validate-script` ✅ | 「校验语法」✅ |
| 试跑 | `POST /nodes/test-run` ✅ | 「试跑」✅ |

前端 `ScriptNodeEditor.vue` 只实现了「新建」模式，`NodePanel.vue` 列表中也没有编辑/删除按钮。

### 3.2 对象生命周期（JVM 视角）

#### 对象模型

```
GraphNodeRegistry.nodesById (ConcurrentHashMap) = {
    "script:append" → ScriptRegisteredGraphNode  ← 一个 nodeId 一个实例
        ├── DynamicNodeDefinition definition
        ├── Object compiledScript (Aviator Expression)
        └── toAction() → NodeAction lambda (捕获 compiledScript)
}
```

**关键理解**：一个 `nodeId` 对应一个 `ScriptRegisteredGraphNode` 实例，不是每个图/版本一个。

#### 修改脚本节点的完整流程

```
PUT /nodes/script:append { scriptBody: "new code" }
  ↓
ScriptNodeService.update()
  → 校验新脚本语法 ✅
  → DB 写入新 definition ✅
  → new ScriptRegisteredGraphNode(newDef)        ← 创建新节点对象
  → nodeRegistry.registerDynamic(newNode)          ← 注册
      → nodesById.put("script:append", newNode)    ← 旧对象被替换
      → 旧 ScriptRegisteredGraphNode → 无引用 → GC ✅
```

#### 发布时的对象创建

```
publish("test_create", "1.0.2")
  ↓
DynamicGraphBuilder.build(def)
  → 遍历 def.nodes[] 
  → nodeRegistry.get("script:append")              ← 取 Registry 中当前唯一实例
  → node.toAction() → lambda（捕获 currentNode.compiledScript）
  ↓
创建 new CompiledGraph(test_create, v1.0.2)        ← 新图对象
  → enabledGraphs.put("test_create", newGraph)     ← 替换旧图
  → 旧 CompiledGraph(v1.0.1) → 无引用 → GC ✅
```

**结论**：
- 节点是**共享的**（一个 `nodeId` 一个实例）
- 图是**新建的**（每次发布都 new `CompiledGraph`）
- 发布不会复制节点对象，只是新图的 lambda 捕获了当前节点的 `compiledScript`

#### 旧 CompiledGraph 的 GC 分析

```java
// GraphRuntime.publish() 核心代码
enabledGraphs.put(graphId, compiled);  // ConcurrentHashMap.put → 原子替换
```

引用链分析：

```
发布前：
  enabledGraphs["test_create"] → CompiledGraph(v1.0.1)  ← 旧对象，有引用

发布后：
  enabledGraphs["test_create"] → CompiledGraph(v1.0.2)  ← 新对象
  CompiledGraph(v1.0.1) → GC Roots 不可达 → 等待 GC ✅
```

**唯一风险：进行中的请求**：

```
T0: 请求A → graphRuntime.get("test_create") → 拿到 v1.0.1
T1: 管理员发布 v1.0.2 → enabledGraphs.put → 替换
T2: 请求A 继续用 v1.0.1 执行（已在栈上）
T3: 请求A 完成 → v1.0.1 引用释放 → GC ✅
```

- ✅ 旧 `CompiledGraph` 会被 GC，延迟取决于进行中请求的完成时间
- ✅ `ConcurrentHashMap.put` 是原子操作，不会出现「新旧混淆」
- ⚠️ HITL 注意：`resume` 时 `graphRuntime.get("test_create")` 会返回**新图**，这是正确的——resume 应使用当前启用的版本

#### 为什么修改脚本节点不会立即影响正在运行的图（Lambda 闭包引用链分析）

**关键机制**：`ScriptRegisteredGraphNode.toAction()` 返回的 lambda 闭包捕获的是 `this`，即整个 `ScriptRegisteredGraphNode` 实例的引用，而不是值的副本。

```java
// ScriptRegisteredGraphNode.toAction()
public NodeAction toAction(NodeRuntimeContext ctx) {
    ScriptEngine engine = engineRegistry.require(definition.engine());
    // ...
    return state -> {                                    // ← 这个 lambda
        Object result = engine.execute(compiledScript,   // ← 捕获了 this.compiledScript
                                       scriptCtx);
    };
}
```

当 lambda 访问 `compiledScript` 字段时，实际访问路径是 `this.compiledScript`。由于 `this` 是闭包捕获的外部引用，lambda 始终持有构造它的那个 `ScriptRegisteredGraphNode` 实例的引用。

**完整时间线验证：**

```
T0: 创建脚本节点
    → nodeRegistry["script:append"] = ScriptRegisteredGraphNode(v1)
        └── compiledScript = "seq.map('normalized_query', state.query)"

T1: 发布图 test_create
    → DynamicGraphBuilder.build()
        → nodeRegistry.get("script:append")  ← 返回 Node_v1
        → Node_v1.toAction() → lambda
        → lambda 闭包捕获 this(Node_v1)       ← 关键！
    → new CompiledGraph(test_create, v1.0.1)
        └── pipeline → lambda → this → Node_v1.compiledScript = old_script
    → enabledGraphs["test_create"] = CompiledGraph(v1.0.1)

━━━━━━━━━━━━━━━━━ 修改脚本节点 ━━━━━━━━━━━━━━━━━

T2: 修改 script:append 的 scriptBody
    → new ScriptRegisteredGraphNode(v2)
        └── compiledScript = "seq.map('normalized_query', state.query + 'NEW')"
    → nodeRegistry.registerDynamic(Node_v2)
        → nodesById.put("script:append", Node_v2)  ← 注册中心指向新对象
        → Node_v1 仍被 CompiledGraph 的 lambda 引用 → 不会被 GC

此时 JVM 中的引用链：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  GraphNodeRegistry.nodesById["script:append"]
      └── ScriptRegisteredGraphNode(v2)     ← 注册中心指向新对象 ✅
              └── compiledScript = NEW_CODE

  GraphRuntime.enabledGraphs["test_create"]
      └── CompiledGraph(v1.0.1)             ← 缓存仍指向旧图 ❗
              └── pipeline
                    └── NodeAction lambda     ← 闭包捕获了 this(Node_v1)
                          └── this → ScriptRegisteredGraphNode(v1)
                                    └── compiledScript = OLD_CODE

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

T3: 业务请求 → graphRuntime.get("test_create")
    → 返回 CompiledGraph(v1.0.1)
    → 执行 pipeline → lambda → this(Node_v1).compiledScript
    → **执行的是旧逻辑！** ← 关键结论

T4: 重新发布 test_create
    → build() 调用 registry.get("script:append") → 返回 Node_v2 ✅
    → Node_v2.toAction() → new lambda → 捕获 this(Node_v2)
    → new CompiledGraph(v1.0.2) → 替换旧图
    → Node_v1 失去最后一个引用 → GC ✅
    → **新请求使用新逻辑 ✅**
```

| 操作 | Registry 中节点 | 已发布图的 lambda 引用 | 新请求执行的逻辑 |
|------|----------------|---------------------|----------------|
| 仅修改脚本节点 | 替换为 v2 ✅ | 仍指向 Node_v1（旧闭包） | **旧逻辑** |
| 修改 + 重新发布 | 保持 v2 ✅ | 重新构建 → 指向 Node_v2 | **新逻辑** ✅ |

**为什么这样设计是安全的：**
- 修改脚本立即生效 → 正在运行的线上流程行为突变 ❌ 危险
- 修改后需显式发布才能切换 → 管理员可控的变更窗口 ✅ 安全

### 3.3 方案

| 方案 | 实现 | 成本 | 风险 |
|------|------|------|------|
| **A. 列表增加操作按钮** | `NodePanel.vue` 脚本节点列表项加编辑 ✏️ / 删除 🗑️ 图标，编辑时打开 `ScriptNodeEditor` 并预填已有数据 | 低（1-2人日） | 低 |
| **B. 删除前引用检查** | 调用 JSON_CONTAINS 查询，有引用则弹窗确认，列出引用该节点的图清单 | 中（需后端新 API） | 低 |
| **C. 修改后影响提示** | 展示引用此节点的图列表，提示需手动重新发布 | 低（纯提示） | 低 |

### 3.4 删除流程设计

```
用户点击删除 script:append
  ↓
1. 检查引用 ← 后端 API
   GET /api/graph/nodes/references?nodeId=script:append
   → ["test_create", "another_graph"]
   
2. 如果有引用
   → 前端弹窗：
     "以下图引用了此节点，删除后将无法编译：
      - test_create (v1.0.1)
      - another_graph (v2.0.0)
      确认删除？"
   
3. 用户确认
   → DELETE /nodes/script:append
   → DB 删除 + nodeRegistry.unregisterDynamic()
   → 已发布图不立即重新编译（下次 publish 时报错，需操作者手动修复）
```

### 3.5 修改 vs 重新发布的关系

| 操作 | DB | Registry | 已发布图 | 建议 |
|------|-----|----------|---------|------|
| 修改脚本节点 | 更新 ✅ | 替换对象 ✅ | **不受影响**（仍用旧的 compiled graph） | 提示需要重新发布 |
| 删除脚本节点 | 删除 ✅ | 移除对象 ✅ | **下次编译失败** | 强制检查引用 |
| 重新发布图 | 更新 enabled 表 | 不变 | **新建 CompiledGraph** ✅ | 自动生效 |

---

## 4. 问题 3 + 4：多实例发布/节点通知

### 4.1 现状

```
实例A：GraphRuntime.enabledGraphs   ← 内存缓存
实例B：GraphRuntime.enabledGraphs   ← 内存缓存（独立）
       GraphNodeRegistry.nodesById  ← 内存缓存（独立）
              └── ace_graph_dsl_enabled / node_definition   ← 共享 MySQL
```

- 实例 A 发布图 → 更新 DB + A 的本地缓存
- 实例 B 的本地缓存**仍是旧版本**
- 实例 B 请求 → `graphRuntime.get()` → 返回旧 CompiledGraph
- 实例 A 创建脚本节点 → 写 DB + 注册到 A 的 Registry
- 实例 B 编译引用了该节点的图 → **「节点未注册」**

### 4.2 方案对比：懒加载 vs Pub/Sub

#### 懒加载方案（推荐）

```
实例B 收到请求 → graphRuntime.get("test_create")
  → 查本地缓存 version: "1.0.1"
  → 查 DB enabled 表 → version: "1.0.2"  ← 不一致！
  → DynamicGraphBuilder.build(最新定义)
    → 编译时检查脚本节点是否在 nodeRegistry
      → 缺失？从 DB 加载并 registerDynamic()
    → 编译成功
  → 更新缓存 → 返回新版本
```

| 维度 | 评估 |
|------|------|
| **首次请求延迟** | +1 次 DB 查询（~1ms 本地 MySQL） |
| **后续请求延迟** | 0（命中缓存，走 ConcurrentHashMap） |
| **实例抖动** | 零。无网络广播，无消息丢失，无断连重连 |
| **内存占用** | 无增加 |
| **代码改动** | `GraphRuntime.get()` + `DynamicGraphBuilder.build()` 各加一步 DB 检查 |
| **DB 压力** | 每 request 多 1 次主键查询，可加 5 秒本地 TTL 抵消 |
| **版本不一致窗口** | 最多 5 秒（TTL 后感知）或 0 秒（无 TTL） |
| **故障恢复** | 自动（DB 是唯一真相源） |

#### Pub/Sub 方案（对比）

| 维度 | 评估 |
|------|------|
| **即时性** | 理论上实时，但消息可能丢失 |
| **实例抖动** | 订阅重连、消息反序列化失败、广播风暴 |
| **代码改动** | 新增 Redis Listener + 消息格式 + 序列化 |
| **运维复杂度** | 需保证所有实例 Redis 连接正常，断连需自动重订阅 + 全量 reload |
| **调试难度** | 消息链路不透明，排查问题需看 Redis 日志 |

### 4.3 对比结论

| | 懒加载 | Pub/Sub |
|------|--------|---------|
| 成本 | **低**（~60 行代码） | 中（Listener + 序列化 + 重连） |
| 首次请求延迟 | **+1ms DB 查询** | +0ms |
| 运行时稳定性 | **极高**（无外部依赖额外耦合） | 中（依赖 Redis 连接健康） |
| 版本延迟 | 0~5s（可配 TTL） | 0s（理论） |
| 故障恢复 | **自动** | 需处理消息积压/丢失 |

### 4.4 最终推荐：统一懒加载

```
┌─────────────────────────────────────────────────┐
│               统一懒加载策略                       │
├─────────────────────────────────────────────────┤
│ GraphRuntime.get(graphId)                       │
│   → 缓存版本 ≠ DB enabled 版本？                 │
│     → DynamicGraphBuilder.build()               │
│       → ensureScriptNodesLoaded()               │
│         → 遍历图引用所有 script:* 节点             │
│         → 每个节点从 DB 加载最新定义                │
│         → registerDynamic() 覆盖注册中心旧实例      │
│         → 编译成功 → 更新缓存                     │
│   → 返回 CompiledGraph                          │
│                                                 │
│ 关键设计：每次编译都从 DB 重新加载脚本节点定义，     │
│ 确保多实例下节点修改也能被其他实例感知。             │
│                                                 │
│ 配置：ace.graph.dsl.runtime.cache-ttl-seconds=5  │
│ 生产环境 5 秒 TTL，开发环境 0（即时）              │
└─────────────────────────────────────────────────┘
```

#### 核心代码示意

```java
// GraphRuntime.get() 改造
private final Map<String, String> versionCache = new ConcurrentHashMap<>();

public CompiledGraph get(String graphId) {
    CompiledGraph cached = enabledGraphs.get(graphId);
    if (cached == null || isStale(graphId)) {
        GraphDefinition latest = repository.getEnabled(graphId);
        if (latest == null) {
            throw new IllegalStateException("Graph 未启用: " + graphId);
        }
        cached = builder.build(latest);
        enabledGraphs.put(graphId, cached);
        versionCache.put(graphId, latest.version());
    }
    return cached;
}

private boolean isStale(String graphId) {
    String cached = versionCache.get(graphId);
    if (cached == null) return true;
    GraphDefinition latest = repository.getEnabled(graphId);
    return latest != null && !latest.version().equals(cached);
}
```

```java
// DynamicGraphBuilder.build() 增加节点按需加载
for (GraphNode node : definition.nodes()) {
    String nodeId = node.nodeId();
    if (nodeId.startsWith("script:") && nodeRegistry.get(nodeId) == null) {
        nodeDefRepository.findById(nodeId).ifPresent(def -> {
            RegisteredGraphNode rn = scriptNodeFactory.create(def);
            nodeRegistry.registerDynamic(rn);
            log.info("懒加载脚本节点: {}", nodeId);
        });
    }
}
```

### 4.5 多实例节点传递与更新流程

当实例 A 创建/修改脚本节点并发布图后，实例 B 如何获取最新节点定义：

```
实例A                                    实例B
───────                                  ───────
T0: 创建 script:new_node
    → DB 写入 node_definition ✅
    → nodeRegistry.registerDynamic() ✅

T1: 发布 test_create (v1.0.2)
    → DB enabled 表版本更新 ✅

                                        T2: 收到业务请求
                                        T3: graphRuntime.get("test_create")
                                            → isStale() 检查 DB
                                            → 版本 1.0.2 ≠ 缓存 1.0.1
                                            → refresh() → build()
                                              → ensureScriptNodesLoaded()
                                                → 遍历 def.nodes[]
                                                → script:new_node → DB 查找
                                                  → 找到 ✅ → create() → registerDynamic()
                                                → script:append → DB 查找
                                                  → 找到（可能是 v2）→ create() → registerDynamic()
                                                  → 覆盖注册中心旧实例 ✅
                                              → 编译成功 → 缓存更新
                                        T4: 返回新 CompiledGraph ✅
```

**关键**：`ensureScriptNodesLoaded()` 不是「只加载缺失节点」，而是**每个编译周期都遍历所有 `script:*` 节点并从 DB 重新加载**：

```java
private void ensureScriptNodesLoaded(GraphDefinition def) {
    for (NodeRef ref : def.nodes()) {
        String nodeId = ref.nodeId();
        if (!nodeId.startsWith("script:")) continue;
        // 不检查 nodeRegistry.contains()，始终从 DB 重新加载
        DynamicNodeDefinition nodeDef = nodeDefRepository.findById(nodeId).orElse(null);
        if (nodeDef != null) {
            RegisteredGraphNode rn = scriptNodeFactory.create(nodeDef);
            nodeRegistry.registerDynamic(rn);  // 覆盖旧实例
        }
    }
}
```

这样无论节点是「新增」还是「修改」，其他实例都能在一次编译中得到最新定义。

### 4.6 收益

- **全链路懒加载**：发布/回滚/节点变更后，其他实例在下次业务请求时自动感知
- **零额外基础设施**：不依赖 Redis Pub/Sub，不需要消息队列
- **DB 是唯一真相源**：无一致性问题
- **可配 TTL**：按环境调整版本检查频率

---

## 5. 对象生命周期速查表

| 对象 | 何时创建 | 何时释放 | nodeId / graphId 唯一性 |
|------|---------|---------|------------------------|
| `ScriptRegisteredGraphNode` | 创建/修改脚本节点时 | 被新版本覆盖时，或删除时 | ✅ 一个 nodeId 一个实例 |
| `CompiledGraph` | 每次 publish / rollback | 被新版本 put 覆盖后，等待 GC | ✅ 一个 graphId 一个（最新启用版本） |
| `compiledScript` (Aviator Expression) | ScriptRegisteredGraphNode 构造时 | 随宿主 ScriptRegisteredGraphNode 释放 | — |
| `NodeAction` (lambda 闭包) | `toAction()` 调用时（build 阶段） | 随宿主 CompiledGraph 释放 | — |

---

## 6. 建议实施顺序

| 批次 | 内容 | 成本 | 理由 |
|------|------|------|------|
| **第一批** | 问题 3 + 4：多实例懒加载 | ~1 人日 | 生产部署的核心瓶颈，必须解决 |
| **第二批** | 问题 2：脚本节点管理 UI + 引用检查 | 2-3 人日 | 完善用户体验 |
| **第三批** | 问题 1：孤儿节点检测 API | <0.5 人日 | 锦上添花，维护用途 |

---

## 7. 相关文档

| 文档 | 说明 |
|------|------|
| [FUTURE_OPTIMIZATION_PLAN.md](./FUTURE_OPTIMIZATION_PLAN.md) | 总体优化规划 |
| [SCRIPT_NODE_EXAMPLES.md](./SCRIPT_NODE_EXAMPLES.md) | 脚本节点使用样例 |
| [NODE_FLEXIBILITY_EXPLORATION.md](./NODE_FLEXIBILITY_EXPLORATION.md) | 节点灵活性设计 |
| [PROJECT_OVERVIEW.md](./PROJECT_OVERVIEW.md) | 架构总览 |
