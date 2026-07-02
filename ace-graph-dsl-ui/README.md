# @acelance/graph-dsl-ui

面向 [spring-ai-alibaba-graph](https://github.com/alibaba/spring-ai-alibaba) 的 **Graph DSL 可视化设计器** Vue 组件库。基于 LogicFlow 提供拖拽式流程编排，支持草稿保存、校验、PlantUML 预览与版本发布。

## 功能特性

- **可视化编排**：拖拽节点、连线，支持普通边与条件分发边（Dispatcher）
- **Graph 管理**：目录列表、新建 / 切换 Graph DSL、版本摘要展示
- **草稿与发布**：保存草稿、服务端校验、发布并切换运行时 Graph
- **编译配置**：Key Strategy、Saver（Memory / JDBC / Redis）、HITL 中断点
- **预览能力**：PlantUML / Mermaid 预览（通过后端 API）
- **可组合**：提供完整页面组件，也支持拆分使用工具栏、画布、属性面板等子组件
- **脚本节点**：设计器内新建 Aviator 脚本节点，支持语法校验与试跑（详见 [脚本节点样例文档](../ace-graph-dsl-backend/docs/SCRIPT_NODE_EXAMPLES.md)）
- **菜单权限**：按后端返回的菜单权限自动控制按钮显隐，支持对接宿主权限框架（详见 [菜单权限接入指南](../ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md)）

## 文档

| 文档 | 说明 |
|------|------|
| [脚本节点填写与使用样例](../ace-graph-dsl-backend/docs/SCRIPT_NODE_EXAMPLES.md) | 字段说明、9 类场景样例、cs-reply 集成、REST API |
| [菜单/功能权限抽象与接入指南](../ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md) | 菜单权限 SPI、REST API 与前端 `usePermissionStore` 用法 |
| [后续优化与功能规划建议](../ace-graph-dsl-backend/docs/FUTURE_OPTIMIZATION_PLAN.md) | 按 P0–P3 优先级的后续优化项与落地顺序 |

## 技术栈

| 依赖 | 用途 |
|------|------|
| Vue 3 | 组件框架 |
| Pinia | 状态管理 |
| Element Plus | UI 组件 |
| LogicFlow | 流程图画布 |
| Axios | HTTP 客户端 |

## 安装

```bash
npm install @acelance/graph-dsl-ui
```

同时安装 peer dependencies：

```bash
npm install vue pinia element-plus axios @logicflow/core @logicflow/extension
```

## 快速开始

### 1. 注册依赖与样式

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@logicflow/core/dist/index.css'
import '@logicflow/extension/lib/style/index.css'

import { GraphDslManager } from '@acelance/graph-dsl-ui'

createApp(GraphDslManager)
  .use(createPinia())
  .use(ElementPlus)
  .mount('#app')
```

容器需具备明确高度，例如：

```css
html, body, #app {
  height: 100%;
}
```

### 2. 配置后端代理（开发环境）

组件默认请求相对路径 `/api/graph/**`，开发时可通过 Vite 代理转发到 Graph DSL 后端：

```js
// vite.config.js
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8087',
        changeOrigin: true
      }
    }
  }
})
```

## 组件说明

### GraphDslManager

带左侧目录的完整管理页，适合作为独立页面嵌入。

| Prop | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `title` | `String` | `'Graph DSL 管理中心'` | 目录面板标题 |
| `apiBaseUrl` | `String` | `'/'` | 后端 API 根路径 |

```vue
<GraphDslManager title="流程编排中心" api-base-url="/" />
```

### GraphDslDesigner

单 Graph 设计器，不含目录，适合嵌入已有路由或弹窗。

| Prop | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `graphId` | `String` | **必填** | Graph 唯一标识 |
| `apiBaseUrl` | `String` | `'/'` | 后端 API 根路径 |
| `title` | `String` | `''` | 工具栏显示标题，默认同 `graphId` |

| 事件 | 说明 |
|------|------|
| `saved` | 草稿保存成功后触发 |
| `published` | 发布成功后触发 |

```vue
<GraphDslDesigner
  graph-id="order-flow"
  title="订单流程"
  @saved="onSaved"
  @published="onPublished"
/>
```

### 子组件（高级用法）

可按需组合布局：

| 导出名称 | 说明 |
|----------|------|
| `DesignerToolbar` | 顶部工具栏（保存 / 校验 / 预览 / 发布） |
| `DesignerNodePanel` | 左侧节点面板 |
| `DesignerCanvas` | LogicFlow 画布 |
| `DesignerPropertyPanel` | 右侧属性面板 |

## Store 与 API

### Pinia Stores

```js
import { useGraphEditorStore, useNodeRegistryStore } from '@acelance/graph-dsl-ui'

const editor = useGraphEditorStore()   // 当前 Graph 编辑状态
const nodeStore = useNodeRegistryStore() // 节点 / Dispatcher 注册表
```

`useGraphEditorStore` 主要能力：

- `loadLatest()` / `save()` / `validate()` / `publishCurrent()`
- `buildDefinition()` / `applyDefinition()` — DSL 对象读写
- `setFromLfData()` — 从 LogicFlow 画布同步到 store

### API 客户端

默认导出基于 `baseURL='/'` 的便捷方法；也可创建自定义实例：

```js
import { createGraphApi, listSummaries, saveDraft } from '@acelance/graph-dsl-ui'

const api = createGraphApi('https://your-backend.example.com')

await api.listSummaries()
await api.saveDraft('order-flow', definition)
```

## 后端 API 约定

组件库依赖以下 REST 接口（路径前缀 `/api/graph`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/nodes` | 可用节点类型列表 |
| GET | `/dispatchers` | 条件边 Dispatcher 列表 |
| GET | `/catalog/summaries` | Graph 摘要列表 |
| GET | `/definitions/{graphId}` | 获取最新定义 |
| GET | `/definitions/{graphId}/versions` | 版本历史 |
| POST | `/definitions/{graphId}/draft` | 保存草稿 |
| POST | `/definitions/{graphId}/validate` | 校验定义 |
| POST | `/definitions/{graphId}/preview/plantuml` | PlantUML 预览 |
| POST | `/definitions/{graphId}/preview/mermaid` | Mermaid 预览 |
| POST | `/definitions/{graphId}/publish` | 发布版本 |
| POST | `/definitions/{graphId}/rollback` | 回滚版本 |
| GET | `/definitions/{graphId}/enabled` | 当前启用版本 |

## 本地开发

```bash
# 安装依赖
npm install

# 启动 Demo（默认 http://127.0.0.1:5173）
npm run dev

# 构建
npm run build

# 预览构建产物
npm run preview
```

Demo 入口为 `demo/main.js`，挂载 `GraphDslManager` 组件。请确保 Graph DSL 后端服务（默认 `8087` 端口）已启动，否则节点列表与 Graph 数据将无法加载。

## 项目结构

```
ace-graph-dsl-ui/
├── demo/                    # 本地 Demo 入口
├── src/
│   ├── api/graph.js         # Graph DSL HTTP 客户端
│   ├── stores/
│   │   ├── graphEditor.js   # 编辑器状态与 DSL 转换
│   │   └── nodeRegistry.js  # 节点 / Dispatcher 注册表
│   ├── components/
│   │   ├── GraphDslManager.vue    # 管理页（目录 + 设计器）
│   │   ├── GraphDslDesigner.vue   # 单 Graph 设计器
│   │   └── Designer/              # 设计器子组件
│   └── index.js             # 库导出入口
├── index.html
├── vite.config.js
└── package.json
```

## GraphDefinition 数据模型（简要）

设计器维护的核心 DSL 结构：

```json
{
  "graphId": "order-flow",
  "displayName": "订单流程",
  "version": "1.0.0",
  "description": "",
  "keyStrategies": { "messages": "REPLACE" },
  "nodes": [
    { "nodeId": "llmNode", "config": {} }
  ],
  "edges": [
    { "from": "__START__", "to": "llmNode", "type": "normal" },
    { "from": "routerNode", "to": "", "type": "conditional", "dispatcher": "routeDispatcher", "mapping": { "A": "nodeA" } }
  ],
  "compile": {
    "interruptBefore": ["hitlNode"],
    "saver": "memory"
  }
}
```

画布中的 `START` / `END` 节点会自动映射为保留字 `__START__` / `__END__`。

## 节点分类

| 分类 | 说明 | 画布配色 |
|------|------|----------|
| `NORMAL` | 普通业务节点 | 蓝色 |
| `ROUTER` | 路由 / 分支节点 | 橙色 |
| `MERGE` | 合并节点 | 绿色 |
| `HITL` | 人机协同节点 | 红色 |

## 许可证

与上游 spring-ai-alibaba-graph 项目保持一致，使用前请查阅对应仓库的许可证说明。
