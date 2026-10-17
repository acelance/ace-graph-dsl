# ACE Graph DSL：图回收箱 — 开发设计与计划（初稿）

> 文档类型：开发设计 + 实施计划（初稿，口径已对齐，未开工）  
> 日期：2026-10-16  
> 范围：设计器正式图的删除、回收、只读预览、恢复、彻底清除  
> 状态：待按本初稿实现

## 1. 已定口径

| 项 | 定稿 |
|----|------|
| 删除范围 | 一次删除该 `graph_id` 的全部定义版本行和启用指针 |
| 快照 | 回收一行，JSON 带全部历史版本 + 删除前启用指针 |
| 区分键 | 列名 `recycle_version`，禁止与 DSL `version`（如 `1.0.22`）混用 |
| `recycle_version` 格式 | 服务器本地时间 `yyyy-MM-dd HH:mm:ss.SSS` + 4 位随机字符（`A-Z` `a-z` `0-9`） |
| 内置图 | 不删除、不进回收箱；接口直接拒绝 |
| 恢复冲突 | 正式列表已有同 `graph_id` 时拒绝，提示「请先删除正式列表中的同名图」；不自动覆盖 |
| 恢复后启用状态 | 按快照里的启用指针原样写回；当时已发布则恢复后仍是已发布 |
| 子图引用 | 删除前警告引用方，不阻断 |
| 连带资源 | 不删 Agent 节点、脚本节点（多图共享） |
| 预览 | 独立「回收预览」模式，不复用内置图 `readOnly` |
| 二次确认 | 删除：输入 `graphId` 后确认按钮才可用；清除：输入 `graphId` + `recycle_version` 末 4 位后确认按钮才可用。后端再校验，大小写敏感 |

## 2. 目标与非目标

### 2.1 目标

- 左栏正式图可删除；误删可从回收箱只读查看并恢复。
- 同一 `graph_id` 在回收箱可有多条，靠 `recycle_version` 区分，列表上展示该字段。
- 清除是回收行的物理删除，不可恢复。

### 2.2 非目标

- 不回收、不删除内置图。
- 不级联删除 `generic_agent` / `script_node` 等独立资源。
- 不提供「用回收图覆盖正式图」。
- 不在回收预览里保存、校验、发布、试运行、调试、导入或改拓扑。
- 不改 DSL 业务版本号规则。

## 3. 数据

现有表（前缀以运行配置为准）：

- 定义表：`graph_id` + DSL `version` + `content_json`（一图多行）
- 启用表：`graph_id` 主键 + 当前启用的 DSL `version`

新增回收表：

```text
graph_definition_recycle
  id                BIGINT PK
  graph_id          VARCHAR(128) NOT NULL
  recycle_version   VARCHAR(64)  NOT NULL
  display_name      VARCHAR(256)
  snapshot_json     MEDIUMTEXT   NOT NULL
  deleted_at        TIMESTAMP    NOT NULL
  UNIQUE (graph_id, recycle_version)
```

`snapshot_json` 结构：

```json
{
  "graphId": "bill-check",
  "displayName": "票据模型核查校验GRAPH",
  "enabledVersion": "1.0.22",
  "definitions": []
}
```

- `definitions`：删除前该 `graph_id` 的全部 `GraphDefinition`。
- `enabledVersion`：删除前启用表中的 DSL 版本；未发布则为 `null`。
- JDBC 与 SQLite 两套仓储都建表。`snapshot_json` 用 `MEDIUMTEXT`（或 SQLite `TEXT`），避免大图被 `TEXT` 64KB 截断。

## 4. 后端

落点：`GraphDefinitionRepository` 增删改回收方法；JDBC / SQLite 实现；`AceGraphDesignerDefinitionController` 暴露接口；删除与恢复时处理 `GraphRuntime` 内存缓存。

### 4.1 删除

`POST /api/graph/definitions/{graphId}/recycle`

请求体：`{ "confirmGraphId": "..." }`

顺序（同一事务）：

1. `confirmGraphId` 与路径 `graphId` 不一致 → 拒绝。
2. 内置图 → 拒绝，不写回收表。
3. 正式库无该 `graph_id` → 拒绝。
4. 扫描其他正式图的子图引用，得到警告列表（不中断）。
5. 生成 `recycle_version`；唯一键冲突则换 4 位随机串重试。
6. 插入回收行（全量快照）。
7. 删除该 `graph_id` 的全部定义行和启用行。
8. 从 `GraphRuntime` 卸掉 `enabledGraphs` / `versionCache` / `lastCheckTimes`。

响应带 `recycleVersion` 与 `warnings`（引用本图的其他 `graphId` 列表）。前端在确认框展示警告，用户仍可删除。

运行时注意：今天 `GraphRuntime.isStale` 在启用行已不存在时不会卸缓存，已编译图会一直留在本进程。删除必须显式 evict。多实例只保证本进程立即失效；其他实例在 TTL 内仍可能命中旧缓存，本方案不额外做广播。

### 4.2 列表与单条

- `GET /api/graph/definitions/recycle`：回收列表（`graphId`、`displayName`、`recycleVersion`、`deletedAt`）。
- `GET /api/graph/definitions/recycle/{graphId}?recycleVersion=`：返回快照，供预览。`recycleVersion` 含空格与冒号，放查询参数，不放路径。

### 4.3 恢复

`POST /api/graph/definitions/recycle/{graphId}/restore`

请求体：`{ "recycleVersion": "..." }`

1. 回收行不存在 → 拒绝。
2. 正式定义表已有该 `graph_id` → 拒绝，文案固定为「请先删除正式列表中的同名图」。
3. 按快照写回全部定义行；`enabledVersion` 非空则写启用表。
4. 删除这一条回收行。
5. 若已写启用指针，本进程 `refresh` 该图。

### 4.4 清除

`POST /api/graph/definitions/recycle/{graphId}/purge`

请求体：`{ "confirmGraphId": "...", "confirmSuffix": "...." }`

`confirmGraphId` 等于路径 `graphId`，且 `confirmSuffix` 等于该 `recycleVersion` 的末 4 位（区分大小写）才物理删除该行。请求体同时带 `recycleVersion` 全串，避免同 `graphId` 多条时删错。后缀不一致或全串对不上 → 拒绝，不删。

## 5. 前端

落点：`GraphDslManager.vue`、设计器工具栏、`graphEditor` store。

- 左栏目录底部增加「图回收箱」。未点击时抽屉不出现。
- 抽屉样式与现目录列表一致。每项展示显示名、`graphId`、`recycle_version`。选中项只渲染画布。
- 回收预览模式：
  - 快照灌入画布，不请求正式图最新版本。
  - 底栏整组不渲染（保存草稿、校验、UML 预览、发布、试运行、调试流式、拓扑校验、条件边、框选、子流程、提取子流程、历史版本、导入）。
  - 节点与边不可拖、不可改、不可删。
  - 左侧属性只读，无保存。
- 内置图仍走现有 `readOnly`，与回收预览分开。
- 正式图项增加删除。确认框输入与当前 `graphId` 完全一致后，「确认删除」才可点。框内展示子图引用警告。
- 回收项提供「恢复」「清除」。清除框须输入 `graphId` 与末 4 位，两者都对上按钮才可点。

## 6. 权限

新增菜单码，缺省不放开给只读账号：

| 码 | 用途 |
|----|------|
| `graph:delete` | 删除进回收箱 |
| `graph:restore` | 从回收箱恢复 |
| `graph:purge` | 彻底清除 |

查看回收箱沿用 `graph:view`。宿主权限表需同步这三项，否则按钮不出现。

## 7. 实施顺序

1. 回收表 + 仓储（JDBC、SQLite）与快照读写单测。
2. 删除 / 列表 / 恢复 / 清除接口，含口令校验、内置图拒绝、正式图冲突拒绝、运行时 evict。
3. 子图引用扫描，只作为警告返回。
4. 左栏回收抽屉 + 回收预览模式（工具栏隐藏、画布不可编辑）。
5. 删除与清除的二次确认框。
6. 设计器手工回归：删正式图、回收箱只读打开、同名冲突拒绝恢复、恢复后启用状态与删除前一致、清除后列表消失、内置图无删除入口、Agent/脚本节点仍在。

## 8. 工作量

约 5～6 人日。不含多实例缓存广播，不含级联删除节点资源。
