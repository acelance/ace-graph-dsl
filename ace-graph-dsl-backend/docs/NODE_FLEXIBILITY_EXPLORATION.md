# Ace Graph DSL — 节点灵活性增强探索方案

> 版本：v0.1（探索稿）  
> 日期：2026-06-29  
> 状态：方案讨论，尚未实施

---

## 1. 背景与动机

当前 ace-graph-dsl 采用 **Spring Bean 静态注册** 模式：宿主应用实现 `RegisteredGraphNode` / `RegisteredEdgeDispatcher` 并标注 `@Component`，框架在启动时一次性注入 `GraphNodeRegistry` / `EdgeDispatcherRegistry`，之后注册表**不可变**。

```
宿主 @Component Bean  →  GraphNodeRegistry（启动时注入，只读）
                              ↓
GraphDefinition JSON  →  DynamicGraphBuilder.build()  →  CompiledGraph
```

该模式适合**强类型、依赖 Spring 容器**的业务节点，但在以下场景存在明显摩擦：

| 场景 | 现状痛点 |
|------|----------|
| 简单数据变换（trim、格式化、数值计算） | 必须写 Java 类 + 重新部署 |
| 运营/实施人员自定义逻辑 | 无法在前端完成，依赖研发排期 |
| 多租户/多角色节点可见性 | `GET /api/graph/nodes` 全量暴露，无权限过滤 |
| 节点元数据变更 | 无持久化，无法运行时发布新节点类型 |

本方案围绕三个方向展开探索：

1. **JVM 内嵌脚本模板支持**
2. **动态节点注册与前端定义**
3. **用户节点权限抽象**

---

## 2. 现状基线（约束与可复用能力）

### 2.1 已有扩展点

| 组件 | 路径 | 可复用性 |
|------|------|----------|
| `RegisteredGraphNode` | `ace-graph-dsl-core/registry/` | 脚本节点可实现此接口 |
| `GraphNodeDescriptor.configurableProps` | 同上 | 已定义 PropertySchema，前端尚未渲染 |
| `NodeRef.config` | `ace-graph-dsl-core/definition/` | 图级节点实例配置，编译时传入 `NodeRuntimeContext` |
| `NodeRuntimeContext` | `ace-graph-dsl-core/registry/` | 提供 Spring 容器 + nodeConfig |
| `GraphDefinitionRepository` | persistence 模块 | 可参照扩展节点定义持久化 |
| `NodeRegistryController` | starter/web/ | 可扩展过滤逻辑 |

### 2.2 关键限制

- `GraphNodeRegistry` 构造后无 `register()` / `unregister()` API
- 持久化层**仅存储 GraphDefinition**，无节点定义表
- `DynamicGraphBuilder` 在 **build 时**调用 `toAction()`，非每次 invoke 解释脚本
- Dispatcher **不支持** per-edge config
- 框架**不含**任何鉴权能力

---

## 3. 方向一：JVM 内嵌脚本模板支持

### 3.1 目标

引入可在 JVM 内安全执行的脚本引擎，使节点逻辑以**模板 + 参数**形式表达，而非硬编码 Java 类。

### 3.2 候选引擎对比

| 引擎 | 嵌入方式 | 性能 | 沙箱能力 | Spring 集成 | 适用场景 |
|------|----------|------|----------|-------------|----------|
| **Groovy** | 原生 JVM | 高（可预编译） | 可配置 SandboxTransformer | 成熟 | 通用脚本节点，语法灵活 |
| **Aviator** | 纯 Java 表达式引擎 | 极高 | 内置白名单函数 | 轻量 | 数学/逻辑/字符串运算 |
| **Spring SpEL** | Spring 内置 | 中 | 有限 | 原生 | 简单表达式，与 Spring 深度集成 |
| **GraalVM JS** | polyglot | 中-高 | 可配置 ResourceLimits | 需额外依赖 | 前端 JS 技能复用 |
| **Janino** | Java 子集编译 | 高 | 编译期限制 | 无 | 类 Java 语法，编译为字节码 |

### 3.3 推荐策略：分层脚本引擎

采用 **「表达式层 + 脚本层」** 双引擎，按节点复杂度分流：

```
┌─────────────────────────────────────────────────────────┐
│                    ScriptNodeAdapter                     │
│              (实现 RegisteredGraphNode)                  │
├──────────────────────┬──────────────────────────────────┤
│   Expression Engine  │        Script Engine             │
│   (Aviator / SpEL)   │   (Groovy Sandbox)               │
│   简单运算/判断       │   多行逻辑/集合操作               │
└──────────────────────┴──────────────────────────────────┘
```

**Phase 1 优先 Aviator + SpEL**（零额外重型依赖，满足 80% 简单节点）  
**Phase 2 引入 Groovy Sandbox**（覆盖复杂脚本场景）

### 3.4 脚本模板契约

定义统一的脚本执行上下文 `ScriptExecutionContext`：

```java
/**
 * 脚本可见的上下文对象（白名单暴露，禁止反射/IO/网络）
 */
public record ScriptExecutionContext(
    Map<String, Object> state,      // 当前 OverAllState 快照（只读 inputKeys）
    Map<String, Object> config,     // NodeRef.config
    Map<String, Object> vars        // 脚本内临时变量
) {}
```

**节点脚本模板示例（Aviator 表达式节点）：**

```json
{
  "nodeId": "script:normalize_score",
  "engine": "aviator",
  "script": "let s = double(state.score); return seq.map('normalized_score', s > 100 ? 100 : s);",
  "inputKeys": ["score"],
  "outputKeys": ["normalized_score"]
}
```

**Dispatcher 脚本模板示例：**

```json
{
  "dispatcherId": "script:route_by_intent",
  "engine": "aviator",
  "script": "state.intent == 'refund' ? 'refund_path' : 'default_path'",
  "possibleTargets": ["refund_path", "default_path"]
}
```

### 3.5 安全沙箱设计

| 层级 | 措施 |
|------|------|
| **引擎白名单** | 禁用 `Runtime.exec`、文件 IO、网络、反射、类加载 |
| **API 白名单** | 仅暴露 `state.get(key)`、`config.get(key)`、内置数学/字符串函数 |
| **资源限制** | 单次执行超时（默认 500ms）、脚本体大小上限（64KB）、递归深度限制 |
| **编译缓存** | 脚本 AST/字节码缓存，避免每次 invoke 重新 parse |
| **审计日志** | 记录 script hash、执行耗时、异常栈（不含 state 敏感值） |

### 3.6 与现有编译流程的集成

```
DynamicGraphBuilder.doBuild()
    │
    ├─ nodeId 以 "script:" 前缀 或 registry 标记为 SCRIPT 类型
    │       ↓
    │   ScriptRegisteredGraphNode.toAction(ctx)
    │       ↓
    │   预编译 script → CompiledScript（build 时）
    │       ↓
    │   NodeAction: state → scriptEngine.execute(ctx, state) → Map<outputKey, value>
    │
    └─ 普通 Java Bean 节点（现有路径不变）
```

**关键决策：** 脚本在 **build 时预编译**，invoke 时只执行，与现有 `toAction()` 语义一致，避免运行时 parse 开销。

---

## 4. 方向二：动态节点注册与前端定义

### 4.1 目标

对于**不依赖外部服务/数据库**、仅基于入参和简单运算即可完成功能的节点，允许用户在前端定义参数解析规则和脚本，**无需后端 Java 编码**。

### 4.2 节点分类模型

引入 `NodeOrigin` 区分节点来源与能力边界：

| 类型 | 标识 | 定义方式 | 能力边界 | 示例 |
|------|------|----------|----------|------|
| **BUILTIN** | Java Bean | 代码 + @Component | 可访问 Spring 容器、DB、外部 API | RAG 检索、LLM 调用 |
| **SCRIPT** | 持久化脚本 | 前端设计器 / REST API | 仅 state + config + 白名单函数 | 字符串格式化、数值计算、条件赋值 |
| **COMPOSITE** | 子图引用（远期） | 前端嵌套 | 复用已有 GraphDefinition | 可复用流程片段 |

**SCRIPT 节点的准入条件（自动校验）：**

- `inputKeys` / `outputKeys` 均为 primitive / String / Map / List
- 脚本 AST 静态分析：无外部 Bean 引用、无 IO/网络调用
- 可选：前端保存时调用 `POST /api/graph/nodes/validate-script` 预检

### 4.3 持久化模型

新增 `DynamicNodeDefinition` 及对应 Repository：

```java
public record DynamicNodeDefinition(
    String nodeId,              // 全局唯一，建议前缀 "script:"
    String displayName,
    String category,            // NORMAL / ROUTER
    String description,
    Set<String> inputKeys,
    Set<String> outputKeys,
    String engine,              // aviator | groovy | spel
    String scriptBody,          // 脚本正文
    String scriptVersion,       // 脚本版本 hash
    Map<String, PropertySchema> configurableProps,
    NodeOrigin origin,          // SCRIPT
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    boolean enabled
) {}
```

**存储表（JDBC/SQLite 示例）：**

```sql
CREATE TABLE ace_graph_dsl_node_definition (
    node_id         VARCHAR(128) PRIMARY KEY,
    display_name    VARCHAR(256) NOT NULL,
    category        VARCHAR(32)  NOT NULL,
    content_json    TEXT         NOT NULL,   -- DynamicNodeDefinition 序列化
    script_hash     VARCHAR(64)  NOT NULL,   -- 用于编译缓存失效
    enabled         BOOLEAN      DEFAULT TRUE,
    created_by      VARCHAR(128),
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);
```

Dispatcher 定义可复用同表，增加 `definition_type` 字段（`NODE` / `DISPATCHER`），或独立 `ace_graph_dsl_dispatcher_definition` 表。

### 4.4 注册中心改造：MutableNodeRegistry

将 `GraphNodeRegistry` 从**不可变**升级为**双层注册表**：

```
┌──────────────────────────────────────────────────┐
│              CompositeGraphNodeRegistry             │
├────────────────────┬─────────────────────────────┤
│  Static Layer      │  Dynamic Layer               │
│  (Spring Beans)    │  (DB/Redis 持久化)           │
│  启动时注入         │  启动加载 + 运行时 CRUD       │
│  优先级：高         │  优先级：低（ID 不可冲突）     │
└────────────────────┴─────────────────────────────┘
         ↓ merge by nodeId
    listDescriptors() / get(nodeId)
```

**核心 API 扩展：**

```java
public interface GraphNodeRegistry {
    // 现有
    RegisteredGraphNode get(String nodeId);
    List<GraphNodeDescriptor> listDescriptors();

    // 新增
    void registerDynamic(DynamicNodeDefinition def);
    void unregisterDynamic(String nodeId);
    void reloadDynamic();  // 从 Repository 全量刷新

    Optional<GraphNodeDescriptor> getDescriptor(String nodeId);
}
```

### 4.5 实例化时机

| 时机 | 行为 | 适用场景 |
|------|------|----------|
| **启动时加载** | `@PostConstruct` 从 Repository 读取所有 `enabled=true` 的 SCRIPT 节点，注册到 Dynamic Layer | 常规部署 |
| **运行时发布** | `POST /api/graph/nodes` → 校验 → 持久化 → `registerDynamic()` → **不影响已编译图** | 新增节点类型 |
| **图发布时绑定** | `GraphRuntime.publish()` 时若发现 DSL 引用了新 SCRIPT 节点，确保已注册并重新编译 | 新图引用新节点 |
| **热更新脚本** | `PUT /api/graph/nodes/{nodeId}` → 更新 scriptBody → 使 CompiledGraph 编译缓存失效 → 通知重新 publish 受影响图 | 脚本迭代 |

**注意：** 修改 SCRIPT 节点定义**不会自动**更新已在内存中的 `CompiledGraph`，需要：
1. 标记引用该节点的 GraphDefinition 为 stale
2. 由 operator 触发 re-publish，或配置 `ace.graph.dsl.auto-republish-on-node-change=true`

### 4.6 前端设计器改造

#### 4.6.1 新增「脚本节点」创建入口

```
NodePanel
├── 内置节点（来自 GET /api/graph/nodes?origin=BUILTIN）
└── 脚本节点（来自 GET /api/graph/nodes?origin=SCRIPT）
    └── [+ 新建脚本节点]  →  ScriptNodeEditor 对话框
```

#### 4.6.2 ScriptNodeEditor 组件

| 区域 | 内容 |
|------|------|
| 基本信息 | displayName、category、description |
| 输入/输出 Key | 可视化编辑 inputKeys / outputKeys |
| 可配置属性 | 基于 PropertySchema 动态表单（复用 configurableProps） |
| 脚本编辑器 | Monaco Editor，Aviator/Groovy 语法高亮 |
| 实时校验 | 调用 validate-script API，展示错误行 |
| 测试运行 | 输入 mock state JSON，调用 test-run API 查看 output |

#### 4.6.3 PropertyPanel 增强

选中画布节点后，根据 `configurableProps` schema 渲染配置表单，写入 `NodeRef.config`：

```
选中节点 "入参标准化"
├── 节点 ID: intake_normalize（只读）
├── 节点类型: SCRIPT（只读）
└── 配置项
    ├── trimMode: [both | start | end]  (select)
    └── maxLength: 256                   (number)
```

#### 4.6.4 DSL 结构扩展（可选）

SCRIPT 节点在图 DSL 中可内联脚本（适合一次性节点）或引用全局定义（适合复用）：

```json
// 方式 A：引用全局 SCRIPT 节点定义（推荐）
{ "nodeId": "script:normalize_query", "config": { "trimMode": "both" } }

// 方式 B：内联脚本（一次性，不持久化到节点库）
{
  "nodeId": "__inline_script_1",
  "inlineScript": {
    "engine": "aviator",
    "script": "seq.map('result', string.trim(state.query))",
    "inputKeys": ["query"],
    "outputKeys": ["result"]
  }
}
```

方式 B 适合快速试验；方式 A 适合团队复用。建议 Phase 1 仅实现方式 A。

### 4.7 REST API 扩展

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/graph/nodes?origin=SCRIPT\|BUILTIN\|ALL` | 按来源过滤 |
| POST | `/api/graph/nodes` | 创建 SCRIPT 节点定义 |
| PUT | `/api/graph/nodes/{nodeId}` | 更新脚本/元数据 |
| DELETE | `/api/graph/nodes/{nodeId}` | 禁用/删除（需检查引用） |
| POST | `/api/graph/nodes/validate-script` | 静态校验 + 沙箱试跑 |
| POST | `/api/graph/nodes/{nodeId}/test-run` | 传入 mock state 测试 |
| GET | `/api/graph/dispatchers` | 同上，含 SCRIPT dispatcher |

### 4.8 与 BUILTIN 节点的协作边界

```
                    ┌─────────────────┐
                    │  GraphDefinition │
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   BUILTIN 节点          SCRIPT 节点         条件边
   (Java Bean)          (脚本引擎)       (SCRIPT/Java Dispatcher)
         │                   │                   │
   可注入 Spring         仅 state+config      读 state 返回 route key
   可访问 DB/API         白名单函数
```

**原则：** SCRIPT 节点**不能**直接调用 Spring Bean。若需组合，应通过图编排：SCRIPT 节点处理数据 → BUILTIN 节点调用外部服务。

---

## 5. 方向三：用户节点权限

### 5.1 目标

提供标准权限校验接口抽象，宿主应用无论使用 Spring Security、Shiro 还是自定义安全框架，只需实现该接口，即可在 `GET /api/graph/nodes` 和 `GET /api/graph/dispatchers` 返回**当前用户有权使用的节点**。

### 5.2 权限模型

#### 5.2.1 权限粒度

| 粒度 | 说明 | 示例 |
|------|------|------|
| **节点类型级** | 用户可见/可用的 nodeId 集合 | 客服只能看到 `cs_*` 节点 |
| **操作级** | 对 SCRIPT 节点的 CRUD 权限 | 只有 ADMIN 可创建脚本节点 |
| **图级** | 与现有 GraphDefinition 权限正交 | 已有 graphId 维度权限可独立扩展 |

本方案聚焦**节点类型级 + SCRIPT CRUD 操作级**。

#### 5.2.2 权限元数据扩展

在 `GraphNodeDescriptor` 增加可选权限标签：

```java
public record GraphNodeDescriptor(
    // ... 现有字段 ...
    Set<String> permissionTags,     // 如 ["cs", "admin", "public"]
    NodeOrigin origin
) {}
```

- BUILTIN 节点：开发者在 Java `descriptor()` 中声明 `permissionTags`
- SCRIPT 节点：创建时指定 tags，或继承创建者角色

### 5.3 核心抽象接口

```java
/**
 * 节点访问控制 SPI。
 * 宿主应用实现此接口并注册为 Spring Bean，框架自动注入。
 */
public interface GraphNodeAccessControl {

    /**
     * 获取当前请求用户有权使用的节点 ID 集合。
     * 返回 empty = 无限制（兼容未启用权限的场景）。
     */
    default Optional<Set<String>> allowedNodeIds() {
        return Optional.empty();
    }

    /**
     * 获取当前用户有权使用的 Dispatcher ID 集合。
     */
    default Optional<Set<String>> allowedDispatcherIds() {
        return Optional.empty();
    }

    /**
     * 基于 permissionTags 的标签匹配（可选，与 ID 白名单二选一或组合）。
     * 返回 empty = 无限制。
     */
    default Optional<Set<String>> allowedTags() {
        return Optional.empty();
    }

    /** 当前用户是否有权创建/修改 SCRIPT 节点 */
    default boolean canManageScriptNodes() {
        return true;
    }

    /** 当前用户是否有权删除 SCRIPT 节点 */
    default boolean canDeleteScriptNodes() {
        return canManageScriptNodes();
    }
}
```

**默认实现（无鉴权）：**

```java
public class PermissiveGraphNodeAccessControl implements GraphNodeAccessControl {
    // 所有 default 方法已返回 empty/true，无需覆写
}
```

**AutoConfiguration 注册逻辑：**

```java
@Bean
@ConditionalOnMissingBean(GraphNodeAccessControl.class)
GraphNodeAccessControl graphNodeAccessControl() {
    return new PermissiveGraphNodeAccessControl();
}
```

### 5.4 框架集成点

#### 5.4.1 NodeRegistryController 改造

```java
@GetMapping("/nodes")
public List<GraphNodeDescriptor> listNodes(
        @RequestParam(defaultValue = "ALL") NodeOrigin origin) {

    List<GraphNodeDescriptor> all = nodeRegistry.listDescriptors(origin);

    return accessControl.allowedNodeIds()
        .map(ids -> all.stream().filter(d -> ids.contains(d.nodeId())).toList())
        .orElseGet(() -> accessControl.allowedTags()
            .map(tags -> all.stream()
                .filter(d -> d.permissionTags().stream().anyMatch(tags::contains))
                .toList())
            .orElse(all));
}
```

`GET /api/graph/dispatchers` 同理。

#### 5.4.2 SCRIPT 节点 CRUD 守卫

```java
@PostMapping("/nodes")
public DynamicNodeDefinition createNode(@RequestBody CreateNodeRequest req) {
    if (!accessControl.canManageScriptNodes()) {
        throw new AccessDeniedException("无权创建脚本节点");
    }
    // ...
}
```

框架**不引入** Spring Security 依赖；`AccessDeniedException` 为框架自定义异常，由宿主 Security 层映射为 403。

### 5.5 宿主应用集成示例

#### 5.5.1 Spring Security

```java
@Component
public class SecurityGraphNodeAccessControl implements GraphNodeAccessControl {

    @Override
    public Optional<Set<String>> allowedTags() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.of(Set.of("public"));
        }
        Set<String> tags = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        tags.add("public");
        return Optional.of(tags);
    }

    @Override
    public boolean canManageScriptNodes() {
        return hasRole("GRAPH_ADMIN");
    }
}
```

#### 5.5.2 Apache Shiro

```java
@Component
public class ShiroGraphNodeAccessControl implements GraphNodeAccessControl {

    @Override
    public Optional<Set<String>> allowedNodeIds() {
        Subject subject = SecurityUtils.getSubject();
        if (subject.hasRole("admin")) {
            return Optional.empty(); // 无限制
        }
        // 从数据库/缓存加载该用户/角色的节点白名单
        return Optional.of(nodePermissionService.loadAllowedIds(subject.getPrincipal()));
    }
}
```

#### 5.5.3 自定义 JWT 方案

```java
@Component
public class JwtGraphNodeAccessControl implements GraphNodeAccessControl {

    private final JwtTokenParser tokenParser;

    @Override
    public Optional<Set<String>> allowedTags() {
        return RequestContextHolder.currentUser()
            .map(user -> user.getNodeTags());
    }
}
```

### 5.6 权限数据存储（宿主侧）

框架**不内置**用户-节点权限表，由宿主应用决定存储方式。推荐宿主提供：

```java
public interface NodePermissionResolver {
    Set<String> resolveNodeIds(Object principal);
    Set<String> resolveTags(Object principal);
}
```

框架 SPI 内部可委托给 `NodePermissionResolver`（可选第二个扩展点），进一步解耦安全框架。

### 5.7 与 GraphDefinition 权限的关系

节点权限与图权限**正交**：

| 维度 | 控制对象 | 建议实现层 |
|------|----------|------------|
| 节点权限 | 设计器节点面板可见性 | `GraphNodeAccessControl`（本方案） |
| 图权限 | 哪些用户可编辑/发布哪些 graphId | 宿主 Controller 拦截或扩展 `GraphDefinitionRepository` |
| 执行权限 | 哪些用户可 invoke 已发布图 | 宿主 `GraphRuntime` 调用入口 |

---

## 6. 整体架构（目标态）

```
┌─────────────────────────────────────────────────────────────────────┐
│                         宿主 Spring Boot 应用                        │
│  ┌─────────────────────┐    ┌──────────────────────────────────┐   │
│  │ BUILTIN 节点 Beans   │    │ GraphNodeAccessControl 实现       │   │
│  │ (RegisteredGraphNode)│    │ (Spring Security / Shiro / JWT)  │   │
│  └──────────┬──────────┘    └───────────────┬──────────────────┘   │
└─────────────┼───────────────────────────────┼───────────────────────┘
              │                               │
┌─────────────▼───────────────────────────────▼───────────────────────┐
│                    ace-graph-dsl-spring-boot-starter                 │
│  NodeRegistryController ──► AccessControl 过滤 ──► 返回可见节点      │
│  ScriptNodeController   ──► CRUD + validate + test-run              │
└─────────────┬───────────────────────────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────────────────────────┐
│                       ace-graph-dsl-core                             │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ CompositeGraphNodeRegistry                                   │   │
│  │   Static Layer (Beans) + Dynamic Layer (Script Nodes)        │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ ScriptEngine │  │ DynamicGraph │  │ GraphNodeAccessControl   │  │
│  │ Aviator/Groovy│  │ Builder      │  │ (SPI)                    │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘  │
└─────────────┬───────────────────────────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────────────────────────┐
│                   ace-graph-dsl-persistence                          │
│  GraphDefinitionRepository  +  DynamicNodeDefinitionRepository     │
└─────────────────────────────────────────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────────────────────────┐
│                       ace-graph-dsl-ui                               │
│  NodePanel (分来源) + ScriptNodeEditor + PropertyPanel (schema 表单) │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 分阶段实施路线

### Phase 1 — 脚本引擎 MVP（4-6 周）

| 任务 | 模块 | 优先级 |
|------|------|--------|
| 引入 Aviator 依赖，实现 `ScriptEngine` 抽象 | core | P0 |
| 实现 `ScriptRegisteredGraphNode` 适配器 | core | P0 |
| 硬编码 2-3 个 SCRIPT 节点验证端到端 | integration-demo | P0 |
| PropertyPanel 渲染 `configurableProps` | ui | P0 |
| 单元测试：沙箱逃逸、超时、编译缓存 | core | P0 |

**交付标准：** 可通过 Java 代码注册 Aviator 脚本节点，前端可配置 `NodeRef.config` 并发布图。

### Phase 2 — 动态节点持久化（4-6 周）

| 任务 | 模块 | 优先级 |
|------|------|--------|
| `DynamicNodeDefinition` 模型 + Repository | persistence | P0 |
| `CompositeGraphNodeRegistry` 改造 | core | P0 |
| REST CRUD + validate-script + test-run | starter | P0 |
| ScriptNodeEditor 前端组件 | ui | P0 |
| 脚本变更 → stale 图标记 | core | P1 |

**交付标准：** 用户可在前端创建 SCRIPT 节点，持久化后出现在 NodePanel，可拖入画布并发布。

### Phase 3 — 权限 SPI（2-3 周）

| 任务 | 模块 | 优先级 |
|------|------|--------|
| `GraphNodeAccessControl` 接口 + 默认实现 | core | P0 |
| Controller 集成过滤逻辑 | starter | P0 |
| integration-demo 提供 Spring Security 示例 | demo | P1 |
| SCRIPT CRUD 权限守卫 | starter | P0 |

**交付标准：** 宿主实现 SPI 后，不同角色看到不同节点列表。

### Phase 4 — 增强（按需）

| 任务 | 说明 |
|------|------|
| Groovy Sandbox 引擎 | 复杂脚本场景 |
| SCRIPT Dispatcher 支持 | 条件边脚本化 |
| 内联脚本（DSL 方式 B） | 快速试验 |
| 节点引用分析 | 删除 SCRIPT 节点前检查哪些图在使用 |
| GraalVM JS 引擎 | 前端 JS 技能复用 |

---

## 8. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| 脚本沙箱逃逸 | 安全漏洞 | 分层引擎 + 静态 AST 分析 + 超时 + 禁用反射/IO；生产环境 SCRIPT 节点创建需 ADMIN 权限 |
| 脚本性能 | 高并发下图执行延迟 | build 时预编译 + CompiledScript 缓存；Aviator 优先 |
| 动态节点与已发布图不一致 | 运行时行为突变 | 脚本变更不自动 re-publish；显式 stale 标记 + operator 确认 |
| nodeId 冲突 | 启动/注册失败 | Static Layer 优先；SCRIPT 节点强制 `script:` 前缀 |
| 前端脚本编辑器复杂度 | 实施门槛 | Phase 1 仅支持 Aviator 表达式子集 + 模板库 |
| 权限模型过于简单 | 无法满足企业 RBAC | SPI 设计留扩展口（allowedNodeIds + allowedTags + NodePermissionResolver） |

---

## 9. 开放问题（待讨论）

1. **SCRIPT 节点是否允许读取 Spring Environment 配置？** 建议否，config 仅来自 `NodeRef.config`。
2. **是否支持 SCRIPT 节点调用其他 SCRIPT 节点（嵌套）？** 建议否，用图编排替代。
3. **Dispatcher 脚本化是否与节点同步推进？** 建议 Phase 4，优先节点。
4. **节点定义是否需要版本历史？** 建议 Phase 2 仅保留最新版，Phase 4 增加版本表。
5. **多租户场景下 SCRIPT 节点是否租户隔离？** 建议在 `DynamicNodeDefinition` 预留 `tenantId` 字段，过滤逻辑由宿主 SPI 实现。

---

## 10. 总结

| 方向 | 核心价值 | 核心改造 |
|------|----------|----------|
| JVM 脚本模板 | 简单逻辑免 Java 编码 | ScriptEngine + ScriptRegisteredGraphNode |
| 动态节点注册 | 前端定义、持久化、运行时发布 | CompositeGraphNodeRegistry + DynamicNodeDefinitionRepository |
| 用户节点权限 | 与安全框架解耦的节点可见性控制 | GraphNodeAccessControl SPI |

三者组合后，ace-graph-dsl 将形成 **「Java 重型节点 + 脚本轻量节点 + 权限可控」** 的分层节点体系，在保持现有 BUILTIN 节点能力不受影响的前提下，显著降低简单逻辑节点的开发成本，并满足企业级权限管控需求。

---

## 附录 A：模块变更清单

| 模块 | 新增/变更 |
|------|-----------|
| `ace-graph-dsl-core` | `ScriptEngine`, `ScriptRegisteredGraphNode`, `CompositeGraphNodeRegistry`, `GraphNodeAccessControl`, `DynamicNodeDefinition` |
| `ace-graph-dsl-persistence` | `DynamicNodeDefinitionRepository` + JDBC/SQLite/Redis 实现 |
| `ace-graph-dsl-spring-boot-starter` | `ScriptNodeController`, AccessControl 注入, AutoConfiguration 扩展 |
| `ace-graph-dsl-ui` | `ScriptNodeEditor.vue`, PropertyPanel schema 表单, NodePanel 分来源展示 |
| `ace-graph-dsl-integration-demo` | Spring Security 示例, 示例 SCRIPT 节点 |

## 附录 B：配置项预览

```yaml
ace:
  graph:
    dsl:
      script:
        enabled: true
        default-engine: aviator          # aviator | groovy | spel
        execution-timeout-ms: 500
        max-script-size-bytes: 65536
        compile-cache-size: 256
      dynamic-nodes:
        enabled: true
        auto-reload-on-startup: true
        auto-republish-on-node-change: false
      access-control:
        enabled: true                    # false 时使用 PermissiveGraphNodeAccessControl
```
