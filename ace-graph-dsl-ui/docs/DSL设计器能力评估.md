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

### P0 — 体验硬伤（不补等于"半成品编辑器"）

| 项 | 现状 | 缺口 | 影响 | 建议 |
|----|------|------|------|------|
| **画布内 Undo/Redo** | 工具栏只有"版本历史"（版本级回滚），非单步编辑撤销 | 未接 LogicFlow 自带 `undo/redo` API | 连错线、删错节点只能手动改回，无基本容错 | 工具栏加撤销/重做按钮，绑定 `lf.undo()` / `lf.redo()`，并维护 `canUndo/canRedo` 状态 |
| **整图试运行 / 调试闭环** | 后端已有 `testRunDraft`（`POST /nodes/test-run`），脚本节点有 `testRunScriptNode`，但**工具栏无入口** | 缺"试运行整图"按钮与执行轨迹展示 | 配完流程只能发布后到业务系统验证，设计器内看不到中间结果 | 工具栏加"试运行"，调用 `testRunDraft`，用 Drawer 展示各节点输入/输出轨迹 |
| **节点复制 / 粘贴 / 克隆（画布级）** | 搜索 `copy/paste` 仅命中节点面板拖拽逻辑，画布选中节点无法复制 | 缺画布级复制粘贴 | 相似分支节点只能重新拖、重新配，重复劳动 | 监听 `Ctrl+C/V`，克隆选中节点（含相对偏移），写入新节点 ID |

### P1 — 工程化缺口

| 项 | 现状 | 缺口 | 影响 | 建议 |
|----|------|------|------|------|
| **导入 / 导出 DSL JSON** | 保存即存后端，无本地文件导入导出 | 缺"导出 JSON / 从文件导入" | 无法离线备份、跨环境移植、Git 管理文本 | 工具栏加导入/导出，序列化 `buildDefinition()` 结果 ↔ 文件 |
| **整体拓扑校验** | 仅 `edgeParamValidation`（入参可达性） | 缺环路检测、START→END 可达性、孤立节点检测 | `validate` 通过但图实际跑不通（死循环/断头路） | 新增 `validateTopology()`：DFS 判环、BFS 判可达 END、标记孤立节点 |
| **从空白直接新建条件边** | 条件边只能"先拉普通边 → 选中 → 转条件边" | 缺拖拽即建条件边的快捷方式 | 建分支流程步骤偏多 | 连线时提供"建为条件边"选项，或拖拽即生成带默认 `mapping` 的条件边 |

### P2 — 高级能力（视场景）

| 项 | 说明 |
|----|------|
| **工具栏缩放 / 适配视图（fitView）/ 缩略图 minimap** | LogicFlow 滚轮缩放有，但缺工具栏按钮与缩略图导航；大图找节点困难 |
| **自动布局** | 无布局算法，节点需手动摆，复杂流程图易凌乱（可接 dagre/elkjs） |
| **节点分组 / 注释 / 子流程（subgraph）** | 缺容器节点，无法把一段子流程折叠成可复用节点 |
| **画布内节点搜索定位（高亮 + 居中）** | `NodePanel` 搜索只过滤列表，不能跳转到画布上对应节点 |

---

## 四、建议实施路线

| 阶段 | 内容 | 理由 |
|------|------|------|
| 第一步（P0） | 整图试运行 → Undo/Redo → 节点复制粘贴 | 三项改动相对独立、风险低、收益直观，直接决定"能否日常使用" |
| 第二步（P1） | 导入/导出 JSON → 整体拓扑校验 → 空白新建条件边 | "能否放心交给业务方用"的门槛 |
| 第三步（P2） | 缩放/缩略图 → 自动布局 → 子流程 → 搜索定位 | 体验增强，按实际 adoption 情况排期 |

---

## 变更记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-08 | 初版，基于源码走查梳理已具备能力与补充设计（P0/P1/P2） |
