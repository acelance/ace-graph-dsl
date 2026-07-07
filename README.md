# Ace Graph DSL

基于 [spring-ai-alibaba-graph](https://github.com/alibaba/spring-ai-alibaba) **1.1.2.2** 的可视化图编排（Graph Orchestration）平台。通过 JSON DSL 定义工作流，提供拖拽式前端设计器与 Spring Boot 后端运行时，支持版本管理、热发布、动态脚本节点扩展。

```
┌─────────────────┐    JSON DSL / REST    ┌──────────────────────────┐
│ ace-graph-dsl-ui │ ───────────────────→ │ ace-graph-dsl-backend     │
│ (Vue 设计器)     │ ←─────────────────── │ (Spring Boot Starter)     │
└─────────────────┘   节点/Dispatcher 元数据 └──────────────────────────┘
```

## 特性

- **可视化编排**：拖拽节点与连线，自定义 SVG 节点与贝塞尔边；连线参数实时校验、失败连线标红
- **DSL 驱动**：前端产出 JSON Graph DSL，后端编译为 Spring AI Alibaba `CompiledGraph`
- **热发布与回滚**：草稿保存 → 校验（含连线参数可达性）→ 发布，无需重启即可切换运行时图版本
- **节点注册中心**：自动发现 Spring Bean 业务节点与 Dispatcher，供设计器渲染节点面板
- **动态脚本节点**：基于 Aviator 的运行时脚本节点，无需重新部署 Java 代码
- **多持久化后端**：SQLite（默认）、Redis、JDBC、内存，可按环境自动选择
- **权限与多租户**：节点可见性过滤、菜单权限 SPI，可对接宿主权限框架

## 仓库结构

本仓库为 Monorepo，包含后端 Maven 多模块与前端 npm 组件库，可独立发版：

```
ace-graph-dsl/
├── ace-graph-dsl-backend/          # Maven 后端库
│   ├── ace-graph-dsl-core/         # DSL 模型、编译器、运行时、注册中心
│   ├── ace-graph-dsl-persistence/  # SQLite / Redis / JDBC / 内存持久化
│   └── ace-graph-dsl-spring-boot-starter/  # 自动配置 + REST API
├── ace-graph-dsl-ui/               # npm 包 @acelance/graph-dsl-ui
├── docs/                           # 仓库级文档
└── LICENSE                         # Apache-2.0
```

| 子项目 | 包坐标 | 说明 |
|--------|--------|------|
| 后端 | `io.acelance:ace-graph-dsl-spring-boot-starter` | 引入宿主 Spring Boot 应用即可启用 `/api/graph/**` |
| 前端 | `@acelance/graph-dsl-ui` | Vue 3 组件库，提供 `GraphDslManager` / `GraphDslDesigner` |

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17、Spring Boot 3.5、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2 |
| 前端 | Vue 3、Pinia、Element Plus、LogicFlow、Axios |
| 脚本引擎 | Aviator 5.4 |

## 快速开始

### 1. 引入后端

在宿主 Spring Boot 项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

本地开发时先安装到本地 Maven 仓库：

```bash
cd ace-graph-dsl-backend
mvn clean install
```

最小配置（默认 SQLite 持久化，文件位于 `~/.ace-graph-dsl/graph-dsl.db`）：

```yaml
ace:
  graph:
    dsl:
      enabled: true
```

实现 `RegisteredGraphNode` 并注册为 `@Component`，即可在设计器中使用自定义业务节点。运行时通过 `GraphRuntime.get(graphId)` 获取已发布的图并执行。

### 2. 引入前端

```bash
npm install @acelance/graph-dsl-ui
npm install vue pinia element-plus axios @logicflow/core @logicflow/extension
```

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@logicflow/core/dist/index.css'
import '@logicflow/extension/lib/style/index.css'
import { GraphDslManager } from '@acelance/graph-dsl-ui'

createApp(GraphDslManager).use(createPinia()).use(ElementPlus).mount('#app')
```

组件默认请求 `/api/graph/**`，开发环境可通过 Vite 代理转发到后端（默认 `http://127.0.0.1:8087`）。

### 3. 端到端流程

```
[UI 拖拽设计] → buildDefinition() 生成 JSON DSL
    → POST /api/graph/definitions/{graphId}/draft      (保存草稿)
    → POST /api/graph/definitions/{graphId}/validate   (校验)
    → POST /api/graph/definitions/{graphId}/publish    (发布)
         ↓ 后端 GraphRuntime：校验 → 编译 → 热更新内存池
    → 业务代码 GraphRuntime.get(graphId) 执行最新图
```

## 本地开发

**后端**

```bash
cd ace-graph-dsl-backend
mvn clean install
```

**前端 Demo**

```bash
cd ace-graph-dsl-ui
npm install
npm run dev    # 默认 http://127.0.0.1:5173
```

请先启动 Graph DSL 后端服务，否则设计器无法加载节点列表与图定义数据。

## 文档

| 文档 | 说明 |
|------|------|
| [后端 README](ace-graph-dsl-backend/README.md) | 配置项、REST API、Graph DSL 模型、扩展点 |
| [前端 README](ace-graph-dsl-ui/README.md) | 组件 Props/Events、Store、API 客户端 |
| [项目总览（架构说明）](ace-graph-dsl-backend/docs/PROJECT_OVERVIEW.md) | 前后端整体架构与核心机制 |
| [脚本节点样例](ace-graph-dsl-backend/docs/SCRIPT_NODE_EXAMPLES.md) | Aviator 脚本节点填写与 API 契约 |
| [菜单权限接入指南](ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md) | 对接宿主权限框架的 SPI 与前端用法 |
| [GitHub Packages 发布指南](docs/GITHUB_PACKAGES_GUIDE.md) | Monorepo 发布、Maven / npm 独立发版 |

## Graph DSL 示例

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

保留字 `__START__` / `__END__` 分别表示图的起点与终点。完整字段说明见[后端 README](ace-graph-dsl-backend/README.md#graph-dsl-模型)。

## License

[Apache License 2.0](LICENSE)
