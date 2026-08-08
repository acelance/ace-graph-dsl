# @acelance/graph-dsl-ui

面向 [spring-ai-alibaba-graph](https://github.com/alibaba/spring-ai-alibaba) 的 **Graph DSL 可视化设计器** Vue 组件库。基于 LogicFlow 提供拖拽式流程编排，支持草稿保存、校验、PlantUML 预览与版本发布。

## 功能特性

- **可视化编排**：拖拽节点、连线，支持普通边与条件分发边（Dispatcher）；自定义 SVG 节点（分类配色）与贝塞尔连线
- **连线参数校验**：连线后实时校验入参可达性，失败连线标红，左下角悬浮提示；发布时后端同步校验（保存草稿不校验）
- **Graph 管理**：目录列表、新建 / 切换 Graph DSL、版本摘要展示
- **草稿与发布**：保存草稿、服务端校验、发布并切换运行时 Graph
- **编译配置**：Key Strategy、Saver（Memory / JDBC / Redis）、HITL 中断点
- **预览能力**：PlantUML / Mermaid 预览（通过后端 API）
- **可组合**：提供完整页面组件，也支持拆分使用工具栏、画布、属性面板等子组件
- **脚本节点**：设计器内新建脚本节点，支持 Aviator / SpEL / QLExpress / Groovy（按后端引擎列表），语法校验与试跑（详见 [脚本节点样例文档](../ace-graph-dsl-backend/docs/SCRIPT_NODE_EXAMPLES.md)）
- **通用 Agent 节点**：节点面板 AGENT 标签下新建 / 复用通用 Agent 实体（双通道：注册式复用 / 内联拖入），支持 prompt / skill / mcp 资源引用与试跑
- **子图编排（graph-in-graph）**：从节点面板拖入子图节点，选择内联（下钻编辑空白子图）或引用（指向目录中已有图）；画布上方浮动面包屑支持下钻 / 返回，编译期循环引用与嵌套深度（≤3 层）防护
- **菜单权限**：按后端返回的菜单权限自动控制按钮显隐，支持对接宿主权限框架（详见 [菜单权限接入指南](../ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md)）

## 文档

| 文档 | 说明 |
|------|------|
| [脚本节点填写与使用样例](../ace-graph-dsl-backend/docs/SCRIPT_NODE_EXAMPLES.md) | 四引擎字段说明、样例、条件边、REST API |
| [多脚本引擎方案](../ace-graph-dsl-backend/docs/MULTI_SCRIPT_ENGINE_PLAN.md) | Phase A/B/C、分包、开关与验收进度 |
| [菜单/功能权限抽象与接入指南](../ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md) | 菜单权限 SPI、REST API 与前端 `usePermissionStore` 用法 |
| [后续优化与功能规划建议](../ace-graph-dsl-backend/docs/FUTURE_OPTIMIZATION_PLAN.md) | 按 P0–P3 优先级的后续优化项与落地顺序 |
| [架构总览](../ace-graph-dsl-backend/docs/PROJECT_OVERVIEW.md) | 前后端架构、校验与数据流 |

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
- `refreshEdgeParamValidation()` / `edgeParamIssues` — 连线参数可达性校验（画布连线变更后自动刷新）

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
| POST | `/definitions/{graphId}/draft` | 保存草稿（**不**做连线参数可达性校验） |
| POST | `/definitions/{graphId}/validate` | 校验定义（含连线参数可达性等） |
| POST | `/definitions/{graphId}/preview/plantuml` | PlantUML 预览 |
| POST | `/definitions/{graphId}/preview/mermaid` | Mermaid 预览 |
| POST | `/definitions/{graphId}/publish` | 发布版本（发布前强制校验，含连线参数可达性） |
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
│   │   └── Designer/              # 设计器子组件（含 EdgeParamValidationPanel）
│   ├── utils/
│   │   └── edgeParamValidation.js # 连线参数可达性校验
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

## 画布视觉与交互

### 自定义节点与连线（LogicFlow v2）

| 文件 | 说明 |
|------|------|
| `Designer/DspNode.js` | 自定义 SVG 节点：`dsp-rect`（普通/MERGE/HITL）、`dsp-diamond`（ROUTER 六边形）、`dsp-circle`（START/END） |
| `Designer/DspEdge.js` | 自定义贝塞尔连线 `dsp-bezier`；普通边 / 条件边分色；校验失败边标红（`paramInvalid`） |

### 节点分类配色

| 分类 | 说明 | 画布配色 |
|------|------|----------|
| `NORMAL` | 普通业务节点 | 蓝色 |
| `ROUTER` | 路由 / 分支节点 | 橙色（六边形） |
| `MERGE` | 合并节点 | 绿色 |
| `HITL` | 人机协同节点 | 紫色 |
| `SUBGRAPH` | 子图节点 | 蓝色（primary） |
| `GENERIC_AGENT` | 通用 Agent 节点（注册式） | 红色（danger） |
| START / END | 保留起止节点 | 青色圆形 |

### 连线参数校验 UI

- **触发时机**：连线增删、节点变更、图加载后画布同步到 store 时自动校验
- **提示面板**：`EdgeParamValidationPanel`，悬浮于画布**左下角**（红底半透明磨砂，固定高度，内容可滚动）
- **失败连线标红**：通过 `lf.setProperties` 增量更新 `paramInvalid`，不触发全图重绘
- **切换 Graph**：`selectGraph` 清空并重新计算当前图的 `edgeParamIssues`

> `EdgeParamValidationPanel` 为设计器内部组件，随 `GraphDslDesigner` 挂载，**未**在 `index.js` 单独导出。

## 快问快答

### 如何选择脚本引擎？

设计器通过 `GET /nodes/engines` 拉取当前可用引擎，在**脚本节点编辑器**与**条件边属性面板**中选择：

| 引擎 | 适用场景 | 备注 |
|------|----------|------|
| Aviator（默认） | 单行表达式、轻量计算 | 无需额外依赖 |
| SpEL | 熟悉 Spring 表达式、Map 字面量 | core 内置；禁止 `T()` 任意类 |
| QLExpress | 多行 if/else 业务规则 | 需引入 `ace-graph-dsl-script-qlexpress` |
| Groovy | 集合分组等复杂脚本 | 需引入 `ace-graph-dsl-script-groovy` 且 `groovy-enabled=true` |

选中引擎后，脚本区行数按 `multiLine` 自动调整（约 3 / 14 行），并显示引擎 hint。更多样例见 [SCRIPT_NODE_EXAMPLES.md](../ace-graph-dsl-backend/docs/SCRIPT_NODE_EXAMPLES.md)。

### 连线参数校验如何工作？

设计器在**连线增删**或**画布同步到 store** 后，根据节点元数据的 `inputKeys` / `outputKeys` 沿图路径累积上游产出 key，判断目标节点入参是否可达：

- **前端**：`edgeParamIssues` 驱动左下角红色半透明提示面板；失败连线在画布上**标红**（`properties.paramInvalid`，增量 `setProperties`，不重绘全图）
- **后端**：`GraphValidator` 第 6 步调用 `EdgeParamReachabilityValidator`；**发布**与工具栏「校验」会拦截；**保存草稿**不经过此校验

**豁免规则**（避免误报）：

| 连线类型 | 是否校验 | 原因 |
|----------|----------|------|
| `__START__` → 业务节点 | 否 | 入参来自图调用初始 state |
| 普通节点 → `HITL` | 否 | 入参来自 interrupt / resume 注入 |
| 普通节点 → 普通节点 | 是 | 须由上游节点产出所需 key |

### 是否支持 Vue 2 + JS 项目集成？

**不支持直接在 Vue 2 项目中作为组件库集成。** 本库面向 **Vue 3** 生态设计与构建：

| 项 | 说明 |
|----|------|
| `peerDependencies` | 要求 `vue ^3.5.0` |
| 组件写法 | 全部使用 `<script setup>`（Vue 3 特性） |
| UI 库 | Element Plus（仅支持 Vue 3） |
| 状态管理 | Pinia 2.x |
| 构建产物 | ESM（`dist/index.js`），运行时依赖 Vue 3 API |

**Vue 3 + JS 项目**可直接按上文「快速开始」集成；**Vue 2 项目**若需使用设计器，可采用以下方式：

1. **独立 Vue 3 子应用**（推荐）：通过 iframe、微前端（如 qiankun）或独立路由页挂载设计器，主应用保持 Vue 2。
2. **宿主升级到 Vue 3**：与官方集成方式一致，长期维护成本最低。
3. **源码直引**（`@acelance/graph-dsl-ui/src`）：宿主仍须为 Vue 3 + Element Plus + Pinia，无法绕过 Vue 2 限制。

## 通用 Agent 节点

通用 Agent 节点采用与脚本节点一致的「先定义 → 入库 → 复用」范式，提供**双通道**：

| 通道 | 说明 | 拖入来源 |
|------|------|----------|
| 注册式（引用型） | 图中仅存 `nodeId`，元数据来自持久化的 `GenericAgentDefinition`，`origin=GENERIC_AGENT`，可跨图复用 | 节点面板 **AGENT** 标签 → 已入库的 Agent 列表 |
| 内联式 | 结构型节点拖入时携带完整 `agentSpec`，ad-hoc 编译执行 | 节点面板结构节点区域拖入（自动带 `agentSpec`） |

### 使用流程

1. 节点面板切到 **AGENT** 标签，点「新建通用 Agent」打开 `AgentNodeEditor` 模态框。
2. 填写 12 个字段（含 prompt / skill / mcp 资源引用、API Key 掩码处理），保存即入库（`POST /agents`）。
3. 在 AGENT 标签列表中点对应 Agent 拖入画布 → 走注册式通道（只读引用 + 「前往节点面板编辑」）。
4. 注册式 Agent 的 keys / 配置变更在 `GenericAgentDefinition` 一处完成，引用它的所有图共享更新。

> 权限：新建 / 删除 / 试跑受 `agent-node:create / delete / test` 菜单权限控制；只读用户仅可拖入引用。

## 子图（graph-in-graph）

子图节点（`SUBGRAPH`）支持两种形态，在属性面板的**内联 / 引用** radio 中切换：

| 形态 | 说明 | 下钻行为 |
|------|------|----------|
| 内联（inline） | 子图内容嵌在父图节点 `subgraph` 字段中 | 点「进入子图」→ 空白子图画布，可独立编辑，面包屑返回主图 |
| 引用（reference） | 节点 `subgraphRef` 指向目录中已有图（`graphId`） | 引用下拉框选择目标图（已排除当前图自身），引用型子图编译期从仓库加载最新版本 |

### 版本锁定（P2）

引用型子图支持锁定特定版本，避免被引用图更新后父图行为静默变化：

- 引用图选择器下方有「版本锁定」下拉，默认「最新（默认）」= 跟随最新版本（`subgraphRef` 存纯 `graphId`）
- 下拉选择具体版本后，`subgraphRef` 存为 `graphId@version`，编译期加载该锁定版本
- 版本列表在选图后懒加载（调 `listVersions`）；切换版本即时生效

### 使用流程

1. 节点面板结构节点区域拖入「子图」节点 → 画布选中该节点。
2. 右侧属性面板：**内联** 直接点「进入子图」；**引用** 先从下拉框选一张已有图。
3. 下钻后，画布上方出现浮动面包屑条：路径 **根图 / 子图 …** + 「返回上级」按钮 + 深度指示器 `X/3`。
4. 点面包屑或「返回上级」回到上层；进入受 `MAX_SUBGRAPH_DEPTH=3` 约束，达到上限前端 `ElMessage.warning` 拦截。

### 注意事项

- 引用 radio 切换为纯 UI 操作，不立即写入图数据；真正建立引用发生在下拉框选定具体图后，避免切换时丢失节点选中。
- 编译期（`GraphValidator` + `DynamicGraphBuilder`）检测跨图 `subgraphRef` 循环引用，并限制嵌套深度 ≤3 层；保存时校验会拦截违规图。

## 许可证

与上游 spring-ai-alibaba-graph 项目保持一致，使用前请查阅对应仓库的许可证说明。
