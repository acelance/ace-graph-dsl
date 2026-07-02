# Ace Graph DSL Backend

基于 [Spring AI Alibaba Graph](https://github.com/alibaba/spring-ai-alibaba) 的动态图编排后端。将前端可视化设计器产出的 JSON DSL 编译为可执行的 `CompiledGraph`，并提供版本管理、发布回滚、节点注册中心等能力。

## 特性

- **动态图编译**：将 JSON 图定义编译为 Spring AI Alibaba `CompiledGraph`，无需重启即可发布新版本
- **版本管理**：支持草稿保存、多版本历史、启用版本切换与回滚
- **节点注册中心**：自动发现 Spring Bean 形式的业务节点与条件边 Dispatcher，供设计器渲染节点面板
- **多持久化后端**：SQLite（默认）、Redis、JDBC、内存，可按环境自动选择
- **可视化预览**：支持导出 PlantUML / Mermaid 格式
- **Golden DSL 引导**：启动时可自动加载并发布预置图定义

## 模块结构

```
ace-graph-dsl-backend/
├── ace-graph-dsl-core/              # 核心：DSL 模型、校验、动态构建、运行时
├── ace-graph-dsl-persistence/       # 持久化：SQLite / Redis / JDBC / 内存
└── ace-graph-dsl-spring-boot-starter/  # Spring Boot 自动配置与 REST API
```

| 模块 | 职责 |
|------|------|
| `ace-graph-dsl-core` | `GraphDefinition` DSL 模型、`DynamicGraphBuilder` 编译器、`GraphRuntime` 运行时、`GraphNodeRegistry` / `EdgeDispatcherRegistry` 注册中心 |
| `ace-graph-dsl-persistence` | `GraphDefinitionRepository` 接口及 SQLite、Redis、JDBC、内存实现 |
| `ace-graph-dsl-spring-boot-starter` | 自动配置、配置属性、REST Controller，引入 starter 即可启用全部能力 |

## 文档

| 文档 | 说明 |
|------|------|
| [项目总览（前后端架构说明）](docs/PROJECT_OVERVIEW.md) | `ace-graph-dsl-backend` 与 `ace-graph-dsl-ui` 两个模块的整体架构、核心机制与端到端流程 |
| [作为第三方通用组件的优化与演进规划](docs/LIBRARY_EMBEDDING_ROADMAP.md) | 库嵌入业务系统的痛点、已修复缺口、优化项与演进路线 |
| [后续优化与功能规划建议](docs/FUTURE_OPTIMIZATION_PLAN.md) | 按 P0–P3 优先级梳理的后续优化项、落地顺序与近期起步包 |
| [节点灵活性增强探索方案](docs/NODE_FLEXIBILITY_EXPLORATION.md) | 动态节点、脚本节点与权限抽象的设计背景 |
| [脚本节点填写与使用样例](docs/SCRIPT_NODE_EXAMPLES.md) | 设计器字段说明、Aviator 样例、cs-reply 集成与 API 契约 |
| [菜单/功能权限抽象与接入指南](docs/MENU_PERMISSION_INTEGRATION.md) | 对接外部权限框架的 SPI、标准菜单 key、REST API 与前端接入 |

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.5.10 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.2 |

## 快速开始

### 1. 引入依赖

在宿主 Spring Boot 项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

本地开发时，先在项目根目录安装到本地 Maven 仓库：

```bash
mvn clean install
```

### 2. 最小配置

Starter 开箱即用，默认使用 SQLite 持久化（文件位于 `~/.ace-graph-dsl/graph-dsl.db`）：

```yaml
ace:
  graph:
    dsl:
      enabled: true
```

### 3. 注册业务节点

实现 `RegisteredGraphNode` 接口并声明为 Spring `@Component`：

```java
@Component
public class IntakeNormalizeNode implements RegisteredGraphNode {

    @Override
    public GraphNodeDescriptor descriptor() {
        return new GraphNodeDescriptor(
                "intake_normalize",           // nodeId：全局唯一
                "入参标准化",                  // displayName
                GraphNodeDescriptor.CATEGORY_NORMAL,
                "将用户输入标准化为结构化数据",
                Set.of("raw_input"),          // inputKeys
                Set.of("normalized_input"),   // outputKeys
                false,                        // supportsParallel
                "1.0.0",
                Map.of()                      // configurableProps
        );
    }

    @Override
    public NodeAction toAction(NodeRuntimeContext ctx) {
        return state -> {
            // 业务逻辑 ...
            return Map.of("normalized_input", result);
        };
    }
}
```

条件路由边需额外实现 `RegisteredEdgeDispatcher`：

```java
@Component
public class InquiryDispatcher implements RegisteredEdgeDispatcher {

    @Override
    public String dispatcherId() {
        return "inquiryDispatcher";
    }

    @Override
    public Set<String> possibleTargets() {
        return Set.of("handle_inquiry", "handle_complaint", "__END__");
    }

    @Override
    public EdgeAction toAction(NodeRuntimeContext ctx) {
        return state -> {
            // 根据 state 返回 routing key
            return "handle_inquiry";
        };
    }
}
```

### 4. 运行时使用

注入 `GraphRuntime` 获取已发布的图并执行：

```java
@Service
public class MyGraphService {

    private final GraphRuntime runtime;

    public MyGraphService(GraphRuntime runtime) {
        this.runtime = runtime;
    }

    public void run(String graphId, Map<String, Object> input) {
        CompiledGraph graph = runtime.get(graphId);
        // graph.invoke(...) 或 stream(...)
    }
}
```

## 配置项

完整配置前缀为 `ace.graph.dsl`：

```yaml
ace:
  graph:
    dsl:
      enabled: true                          # 是否启用自动配置

      persistence:
        type: auto                           # auto | sqlite | redis | jdbc
        prefer-redis: true                   # type=auto 时，Redis 可用则优先使用

        sqlite:
          path: ~/.ace-graph-dsl/graph-dsl.db

        redis:
          key-prefix: ace-graph:dsl

        jdbc:
          table-prefix: ace_graph_dsl_

      bootstrap:
        auto-load: false                     # 启动时自动加载 golden DSL
        golden-definitions:                  # 资源路径列表
          - classpath:graph-definitions/cs-reply-m2.json
```

### 持久化后端选择逻辑

`type=auto`（默认）时按以下优先级自动选择：

1. 若 `prefer-redis=true` 且 Redis 可用 → **Redis**
2. 若配置了非 SQLite 的 DataSource → **JDBC**
3. 否则 → **SQLite**

显式指定 `type` 可跳过自动检测：

| type | 说明 |
|------|------|
| `sqlite` | 本地 SQLite 文件，适合开发与小规模部署 |
| `redis` | 需配置 `spring.data.redis`，适合分布式环境 |
| `jdbc` | 使用宿主应用 DataSource，适合已有数据库的场景 |
| `auto` | 按上述规则自动选择 |

## REST API

所有接口均以 `/api/graph` 为前缀。

### 节点与 Dispatcher 注册中心

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/graph/nodes` | 列出所有已注册节点元数据（供设计器渲染节点面板） |
| `GET` | `/api/graph/dispatchers` | 列出所有已注册 Dispatcher 及其可能目标 |

### 图定义管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/graph/definitions` | 列出所有图定义（最新版本） |
| `GET` | `/api/graph/definitions/{graphId}` | 获取最新版本 |
| `GET` | `/api/graph/definitions/{graphId}/versions` | 列出所有历史版本 |
| `GET` | `/api/graph/definitions/{graphId}/versions/{version}` | 获取指定版本 |
| `GET` | `/api/graph/definitions/{graphId}/enabled` | 获取当前启用版本 |
| `POST` | `/api/graph/definitions/{graphId}/draft` | 保存草稿 |
| `POST` | `/api/graph/definitions/{graphId}/validate` | 校验图定义 |
| `POST` | `/api/graph/definitions/{graphId}/preview/plantuml` | 生成 PlantUML 预览 |
| `POST` | `/api/graph/definitions/{graphId}/preview/mermaid` | 生成 Mermaid 预览 |
| `POST` | `/api/graph/definitions/{graphId}/publish` | 发布指定版本 |
| `POST` | `/api/graph/definitions/{graphId}/rollback` | 回滚到指定版本 |

发布 / 回滚请求体：

```json
{
  "version": "1.0.0",
  "operator": "admin"
}
```

发布响应：

```json
{
  "success": true,
  "version": "1.0.0",
  "message": "OK"
}
```

### 图目录

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/graph/catalog/graph-ids` | 列出所有 graphId |
| `GET` | `/api/graph/catalog/summaries` | 列出所有图定义摘要 |

## Graph DSL 模型

前端设计器保存的 JSON 对应 `GraphDefinition` 结构：

```json
{
  "graphId": "cs-reply-m2",
  "displayName": "客服回复 M2",
  "version": "1.0.0",
  "description": "客服自动回复流程",
  "keyStrategies": {
    "messages": "APPEND",
    "context": "REPLACE"
  },
  "nodes": [
    {
      "nodeId": "intake_normalize",
      "config": {}
    },
    {
      "nodeId": "llm_reply",
      "config": { "temperature": 0.7 }
    }
  ],
  "edges": [
    { "from": "__START__", "to": "intake_normalize", "type": "normal" },
    { "from": "intake_normalize", "to": "llm_reply", "type": "normal" },
    {
      "from": "llm_reply",
      "to": "",
      "type": "conditional",
      "dispatcher": "inquiryDispatcher",
      "mapping": {
        "handle_inquiry": "handle_inquiry",
        "handle_complaint": "handle_complaint",
        "__END__": "__END__"
      }
    },
    { "from": "handle_inquiry", "to": "__END__", "type": "normal" }
  ],
  "compile": {
    "interruptBefore": ["human_review"],
    "saver": "memory"
  }
}
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `graphId` | 图定义业务标识，全局唯一 |
| `version` | Semver 版本号 |
| `keyStrategies` | State key 合并策略：`REPLACE`（覆盖）/ `APPEND`（追加） |
| `nodes` | 节点引用列表，引用已注册的 `nodeId` 并可携带 `config` |
| `edges` | 边列表，支持普通边（`normal`）和条件边（`conditional`） |
| `compile` | 编译配置：`interruptBefore`（HITL 中断点）、`saver`（checkpoint 存储） |

### 保留字

| 保留字 | 含义 |
|--------|------|
| `__START__` | 图起点 |
| `__END__` | 图终点 |

### 节点类别

| 类别 | 说明 |
|------|------|
| `NORMAL` | 普通处理节点 |
| `ROUTER` | 路由节点 |
| `MERGE` | 并行分支合并节点 |
| `HITL` | Human-in-the-Loop 人工介入节点 |

## 发布流程

```
保存草稿 → 校验 → 发布 → 编译 CompiledGraph → 切换 enabled 版本
                              ↓
                        运行时 GraphRuntime 热更新
```

1. 设计器通过 `POST .../draft` 保存草稿
2. 调用 `POST .../validate` 校验节点引用、边连通性等
3. 调用 `POST .../publish` 触发：校验 → 编译 → 持久化 enabled 标记 → 更新内存中的 `CompiledGraph`
4. 业务代码通过 `GraphRuntime.get(graphId)` 获取最新编译结果

回滚流程与发布类似，直接切换到目标历史版本并重新编译。

## 架构概览

```
┌─────────────────┐     REST API      ┌──────────────────────────┐
│  前端设计器      │ ────────────────→ │  spring-boot-starter     │
└─────────────────┘                   │  (Controllers + AutoConfig)
                                      └────────────┬─────────────┘
                                                   │
                    ┌──────────────────────────────┼──────────────────────────┐
                    ↓                              ↓                          ↓
           GraphNodeRegistry              GraphDefinitionRepository    GraphRuntime
           EdgeDispatcherRegistry         (SQLite/Redis/JDBC/Memory)   (CompiledGraph 池)
                    ↓                              ↑
           DynamicGraphBuilder ────────────────────┘
                    ↓
           Spring AI Alibaba CompiledGraph
```

## 构建

```bash
# 编译并安装到本地仓库
mvn clean install

# 仅编译（跳过测试，默认配置）
mvn clean package
```

## 自定义扩展

| 扩展点 | 接口 | 说明 |
|--------|------|------|
| 业务节点 | `RegisteredGraphNode` | 实现 `descriptor()` 和 `toAction()`，注册为 `@Component` |
| 条件边 Dispatcher | `RegisteredEdgeDispatcher` | 实现 `dispatcherId()`、`possibleTargets()` 和 `toAction()` |
| 持久化 | `GraphDefinitionRepository` | 自定义实现并注册为 Spring Bean，覆盖自动配置 |
| ObjectMapper | `ObjectMapper` | 注册名为默认或自定义 Bean 可覆盖 JSON 序列化行为 |

## License

待定
