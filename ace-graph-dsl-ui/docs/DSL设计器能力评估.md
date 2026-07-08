# 工作流 DSL 设计器 — 能力评估与补充设计

> 版本：v1.0
> 日期：2026-07-08
> 范围：`ace-graph-dsl-ui` 前端设计器（画布 / 节点面板 / 属性面板 / 工具栏 / 版本管理）
> 评估依据：源码静态走查（`Toolbar.vue` / `NodePanel.vue` / `Canvas.vue` / `PropertyPanel.vue` / `ScriptNodeEditor.vue` / `VersionHistoryDrawer.vue` / `EdgeParamValidationPanel.vue` / `api/graph.js`）

---

## 一、结论

**核心闭环已具备**：节点拖拽建图 → 节点/边配置（含条件边）→ 校验 → 预览 → 版本管理 → 发布/回滚。作为一个"能用的 DSL 配置工具"是齐的。

但作为**生产级工作流设计器**，在「编辑体验」「调试闭环」「工程化」三块仍有明显缺口。其中 P0 三项（Undo/Redo、整图试运行、节点复制粘贴）直接决定它算不算一个"能日常使用的编辑器"。

---

## 二、已具备的能力

| # | 能力 | 状态 | 代码依据 |
|---|------|------|---------|
| 1 | 节点面板：搜索、分类（ALL/BUILTIN/SCRIPT）、拖拽新建 | ✓ | `src/components/Designer/NodePanel.vue` |
| 2 | 脚本节点 CRUD + 引用检测（`listReferringGraphs`） | ✓ | `NodePanel.vue` + `api/graph.js` |
| 3 | 画布：节点拖拽、连线、START/END 节点、边选中、删除 | ✓ | `src/components/Designer/Canvas.vue` |
| 4 | 节点配置（元数据 / 编译 / Keys / PlantUML / 校验） | ✓ | `src/components/Designer/PropertyPanel.vue` |
| 5 | 脚本节点编辑器：引擎下拉 + 脚本编辑 + 脚本级 test-run | ✓ | `src/components/Designer/ScriptNodeEditor.vue` |
| 6 | 条件边编辑：`conditionEngine` 下拉 + `condition` 表达式 + `mapping` 映射 + 普通边转条件边 | ✓ | `PropertyPanel.vue` + `Canvas.vue`（`applyConditionalEdgeEdit` / `convertEdgeToConditional`） |
| 7 | 边参数可达性校验（只读展示） | ✓ | `src/components/Designer/EdgeParamValidationPanel.vue` + `utils/edgeParamValidation.js` |
| 8 | 版本历史 / 版本对比（diff）/ 回滚 | ✓ | `src/components/Designer/VersionHistoryDrawer.vue` |
| 9 | 保存草稿 / 校验 / 预览（PlantUML、Mermaid）/ 发布 | ✓ | `src/components/Designer/Toolbar.vue` + `api/graph.js` |
| 10 | 菜单权限控制 | ✓ | `src/stores/permissions.js` |

---

## 三、需要补充的设计

### P0 — 体验硬伤（不补等于"半成品编辑器"） ✅ 已实现（v1.1）

| 项 | 状态 | 现状 | 缺口 | 影响 | 建议 |
|----|------|------|------|------|------|
| **画布内 Undo/Redo** | ✅ 已实现 | 工具栏只有"版本历史"（版本级回滚），非单步编辑撤销 | 未接 LogicFlow 自带 `undo/redo` API | 连错线、删错节点只能手动改回，无基本容错 | 工具栏加撤销/重做按钮，绑定 `lf.undo()` / `lf.redo()`，并维护 `canUndo/canRedo` 状态 |
| **整图试运行 / 调试闭环** | ✅ 已实现 | 后端已有 `testRunDraft`（`POST /nodes/test-run`），脚本节点有 `testRunScriptNode`，但**工具栏无入口** | 缺"试运行整图"按钮与执行轨迹展示 | 配完流程只能发布后到业务系统验证，设计器内看不到中间结果 | 新增后端 `POST /definitions/{graphId}/dry-run` + 工具栏"试运行"按钮 + `DryRunDrawer` 展示各节点轨迹 |
| **节点复制 / 粘贴 / 克隆（画布级）** | ✅ 已实现 | 搜索 `copy/paste` 仅命中节点面板拖拽逻辑，画布选中节点无法复制 | 缺画布级复制粘贴 | 相似分支节点只能重新拖、重新配，重复劳动 | 监听 `Ctrl+C/V`，克隆选中节点（含相对偏移），写入新节点 ID |

### P1 — 工程化缺口 ✅ 已实现（v1.2）

| 项 | 状态 | 现状 | 缺口 | 影响 | 建议 |
|----|------|------|------|------|------|
| **导入 / 导出 DSL JSON** | ✅ 已实现 | 保存即存后端，无本地文件导入导出 | 缺"导出 JSON / 从文件导入" | 无法离线备份、跨环境移植、Git 管理文本 | 工具栏加导入/导出，序列化 `buildDefinition()` 结果 ↔ 文件 |
| **整体拓扑校验** | ✅ 已实现 | 仅 `edgeParamValidation`（入参可达性）；后端 `GraphValidator` 已有环路检测 | 缺 START→END 可达性、孤立节点检测 | `validate` 通过但图实际跑不通（死循环/断头路） | 前端 `validateTopology()`（DFS 判环 + BFS 判可达 END + 孤立节点）+ 后端 `GraphValidator` 增强（END 可达/不可达/孤立节点，保护发布） |
| **从空白直接新建条件边** | ✅ 已实现 | 条件边只能"先拉普通边 → 选中 → 转条件边" | 缺拖拽即建条件边的快捷方式 | 建分支流程步骤偏多 | 工具栏"条件边"开关：开启后新拉的连线直接转为条件边并打开编辑 |

### P2 — 高级能力（视场景） ✅ 已实现（v1.3）

| 项 | 状态 | 说明 |
|----|------|------|
| **工具栏缩放 / 适配视图（fitView）/ 缩略图 minimap** | ✅ 已实现 | LogicFlow 滚轮缩放有，但缺工具栏按钮与缩略图导航；大图找节点困难 |
| **自动布局** | ✅ 已实现 | 无布局算法，节点需手动摆，复杂流程图易凌乱（可接 dagre/elkjs） |
| **节点分组 / 注释 / 子流程（subgraph）** | ✅ 已实现（画布内分组/折叠） | 缺容器节点，无法把一段子流程折叠成可复用节点 |
| **画布内节点搜索定位（高亮 + 居中）** | ✅ 已实现 | `NodePanel` 搜索只过滤列表，不能跳转到画布上对应节点 |

---

## 四、建议实施路线

| 阶段 | 内容 | 理由 |
|------|------|------|
| 第一步（P0） | 整图试运行 → Undo/Redo → 节点复制粘贴 | **已完成（v1.1）** |
| 第二步（P1） | 导入/导出 JSON → 整体拓扑校验 → 空白新建条件边 | **已完成（v1.2）** |
| 第三步（P2） | 缩放/缩略图 → 自动布局 → 子流程 → 搜索定位 | **已完成（v1.3）** |

---

## 五、实现记录（P0，v1.1）

### 1. 画布内 Undo/Redo
- **`src/stores/graphEditor.js`**：新增 `canUndo` / `canRedo` 状态并导出，在 `resetGraphScopeState` / `resetEditor` 中重置。
- **`src/components/Designer/Canvas.vue`**：
  - 监听 `history:change` 事件，写入 `editor.canUndo / canRedo`；
  - 新增 `undo()` / `redo()` 并 `defineExpose` 暴露；
  - `renderFromDefinition` 末尾 `lf.clearHistory()`，避免加载图时整图进入撤销栈。
- **`src/components/Designer/Toolbar.vue`**：新增撤销/重做图标按钮，`disabled` 绑定 `editor.canUndo/canRedo`，`emit('undo'/'redo')`。
- **`src/components/GraphDslDesigner.vue`**：监听 `@undo/@redo` → `canvasRef.value.undo()/redo()`。

### 2. 整图试运行 / 调试闭环
- **后端 `GraphDefinitionController.java`**：新增 `POST /definitions/{graphId}/dry-run`，注入 `DynamicGraphBuilder`，把草稿 `build()` 编译为 `CompiledGraph` 后 `stream()` 收集各节点 `NodeOutput`（仅执行一次，避免重复副作用），返回 `{ trace, finalState }`。新增 `DryRunRequest` record。
- **`src/api/graph.js`**：新增 `dryRunGraph(graphId, definition, inputs)`。
- **`src/components/Designer/DryRunDrawer.vue`**（新建）：输入初始 State(JSON) → 调 `dryRunGraph` → 时间线展示各节点输出轨迹 + 最终状态，错误友好提示。
- **`src/components/Designer/Toolbar.vue`** + **`GraphDslDesigner.vue`**：新增"试运行"按钮（权限复用 `GRAPH_VALIDATE`），打开 `DryRunDrawer`。

### 3. 节点复制 / 粘贴（画布级）
- **`src/components/Designer/Canvas.vue`**：
  - 注册 `ctrl+c / meta+c`（复制选中业务节点到剪贴板）、`ctrl+v / meta+v`（粘贴并偏移 50px、选中新节点）；
  - 新增 `copySelection()` / `pasteSelection()`，排除 START/END 保留节点。

### 验证
- 前端：`vite build` 通过（43 模块，built in 2.17s）。
- 后端：`clean compile -pl ace-graph-dsl-spring-boot-starter -am` **BUILD SUCCESS**，`GraphDefinitionController.java`（含 dry-run 端点）已实际重新编译通过（`NodeOutput.state()` 对照 `DefaultGraphExecutionEventAdapter` 修正无误）。编译命令使用 JDK `D:\JDK\jdk-21.0.6` + Maven `D:\Apache\apache-maven-3.9.9`。

---

## 六、实现记录（P1，v1.2）

### 1. 导入 / 导出 DSL JSON
- **`src/stores/graphEditor.js`**：复用 `buildDefinition()`（导出源）与 `applyDefinition()`（导入落库）。
- **`src/components/GraphDslDesigner.vue`**：
  - 导出 `onExportDsl()`：序列化 `buildDefinition()` 为 JSON，`Blob` 下载为 `{graphId}_{version}.json`；
  - 导入 `onImportDsl()` → 隐藏 `<input type=file>` → `onImportFileChange()` 读取并 `JSON.parse`，基础结构校验后 `applyDefinition` + `paintCanvas` 重绘画布，提示成功/失败。
- **`src/components/Designer/Toolbar.vue`**：新增"导入"（权限 `GRAPH_SAVE`，非只读）、"导出"（权限 `GRAPH_VIEW`）按钮，`emit('importDsl'/'exportDsl')`。

### 2. 整体拓扑校验（环 / END 可达 / 不可达 / 孤立节点）
- **`src/utils/topologyValidation.js`**（新建）：纯结构校验 `validateTopology(def)`，BFS 判 START→END 可达、DFS 判环（END 视为终止）、标记不可达与孤立节点，返回 `{ ok, issues }`。
- **`src/stores/graphEditor.js`**：新增 `topologyIssues` 状态与 `validateTopologyNow()` 动作（调用工具并写入 `topologyIssues`）。
- **`src/components/Designer/TopologyValidationPanel.vue`**（新建）：按 `editor.topologyIssues` 展示错误/警告（红/橙配色，含类型标签），无问题时由工具栏提示"拓扑结构正常"。
- **`src/components/Designer/Toolbar.vue`**：新增"拓扑校验"按钮（权限 `GRAPH_VALIDATE`），`emit('topology')` → `GraphDslDesigner` 调 `editor.validateTopologyNow()`。
- **后端 `GraphValidator.java`**：在 `validate()` 中新增 `validateTopology(def, errors)`，补充 **END 不可达 / 不可达节点 / 孤立节点** 校验（环路检测原本已有）。因 `publish` 走 `runtime.validate`，该增强同时保护发布不被"跑不通的图"通过。

### 3. 从空白直接新建条件边
- **`src/stores/graphEditor.js`**：新增 `conditionalDrawMode` 状态（绘制模式开关）。
- **`src/components/Designer/Canvas.vue`**：新增 `edge:add` 监听——当 `conditionalDrawMode` 为真且非渲染期（`!suppressSync`）且新边为普通边时，调用 `convertEdgeToConditional(data.id)` 直接转条件边并打开编辑；复用既有条件边编辑 UI（`PropertyPanel.vue`）。
- **`src/components/Designer/Toolbar.vue`**：新增"条件边"开关按钮（非只读），高亮表示开启。

### 验证
- 前端：`vite build` 通过（46 模块，built in 1.85s）。
- 后端：`clean compile -pl ace-graph-dsl-spring-boot-starter -am` **BUILD SUCCESS**，`GraphValidator.java`（拓扑增强）已实际重新编译通过；现有 `GraphValidatorTest` 的测试图均连到 END，不受影响。编译命令同上。

---

## 七、实现记录（P2，v1.3）

### 1. 缩放 / 缩略图
- **`src/components/Designer/Toolbar.vue`**：新增工具栏按钮组（权限 `GRAPH_VIEW`）——放大（`lf.zoom(false)`）、缩小（`lf.zoom(true)`）、适应画布（`lf.fitView()`）、重置缩放（`lf.resetZoom()`）、缩略图开关（`lf.extension.miniMap.show()/hide()`）。
- **`src/stores/graphEditor.js`**：新增 `minimapVisible` 状态（默认 true），缩略图开关同步该状态。
- **`src/components/Designer/Canvas.vue`**：`initLf` 末尾默认 `lf.extension.miniMap.show()`；新增 `zoomIn/zoomOut/fitView/resetZoom/toggleMinimap` 并 `defineExpose` 暴露。

### 2. 自动布局
- **`src/components/Designer/Canvas.vue`**：`autoLayout()` 采用自研「最长路径分层」算法（拓扑排序 + 同层按 y 排序），按层分配 x、层内分配 y；**保留所有节点**（含分组容器——内置 `AutoLayout` 插件会丢弃无连线的容器节点，故未采用）；分组容器在布局后重新贴合成员包围盒；最后 `lf.fitView()`。
- **`src/components/Designer/Toolbar.vue`**：新增「自动布局」按钮（权限 `GRAPH_VALIDATE`），`emit('autoLayout')` → `GraphDslDesigner` → `canvas.autoLayout()`。

### 3. 子流程分组（容器 / 折叠 / 解组）
- **`src/components/Designer/DspNode.js`**：新增 `DspGroupNode`（虚线圆角矩形 + 标题栏，`kind:'GROUP'`，无连接锚点，`zIndex:-1` 置于底层）。
- **`src/stores/graphEditor.js`**：新增 `groups` 状态（每项 `{ id, label, memberIds, collapsed }`）；`setFromLfData` 按 `kind==='GROUP'` 过滤，避免分组容器被误存为 DSL 业务节点。
- **`src/components/Designer/Canvas.vue`**：
  - `createGroup()`：选中 ≥2 个业务节点 → 计算包围盒 → 创建分组容器并给成员打 `groupId`；
  - `toggleGroupCollapse()`：折叠时隐藏成员（`model.visible=false`）并收缩容器，展开时恢复并重算包围盒；
  - `ungroup()`：解组并恢复成员；
  - `node:drag` 监听：拖拽分组容器时按增量同步移动成员（`model.move(dx,dy)`）；
  - `deleteSelectedElements()` 增强：删除分组容器时自动解组成员，删除成员时从分组移除。
- **`src/components/Designer/GroupPanel.vue`**（新建）：左下角悬浮面板，列出分组，支持折叠/展开/解组。
- **`src/components/Designer/Toolbar.vue`**：新增「子流程」成组按钮（非只读），`emit('createGroup')`。

### 4. 画布内节点搜索定位
- **`src/components/Designer/NodeSearch.vue`**（新建）：右上角搜索框，按节点名称/ID 模糊匹配；点击结果调用 `canvas.focusNode(lfId)` → `lf.focusOn({id})` 居中 + 选中高亮。
- **`src/components/Designer/Canvas.vue`**：新增 `getSearchableNodes()`（返回业务节点列表）与 `focusNode(lfId)`，均 `defineExpose` 暴露。

### 验证
- 前端：`vite build` 通过（50 模块，built in 1.49s）。
- 后端：P2 为纯前端体验增强，无后端改动（P1 的 `GraphValidator` 拓扑增强仍生效）。
- 已知限制：分组为画布内可视化能力，**不持久化到 DSL**（导入/重载图后分组丢失，成员节点保留）。

---

## 变更记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-08 | 初版，基于源码走查梳理已具备能力与补充设计（P0/P1/P2） |
| v1.1 | 2026-07-08 | 实现 P0 三项：Undo/Redo、整图试运行（dry-run 端点 + Drawer）、节点复制粘贴 |
| v1.2 | 2026-07-08 | 实现 P1 三项：导入/导出 JSON、整体拓扑校验（前端工具 + 后端 GraphValidator 增强）、空白新建条件边 |
| v1.3 | 2026-07-08 | 实现 P2 四项：缩放/缩略图工具栏、自动布局（自研分层）、子流程分组（容器/折叠/解组）、画布内节点搜索定位 |
