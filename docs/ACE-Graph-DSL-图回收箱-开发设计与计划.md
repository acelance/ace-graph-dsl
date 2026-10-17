# ACE Graph DSL：图回收箱 — 开发设计与计划（初稿）

> 文档类型：开发设计 + 实施计划（初稿，口径已对齐）  
> 日期：2026-10-16（2026-10-17 收口：子图预检、清除请求体、删除与恢复的事务边界；2026-10-18 开工落实）  
> 范围：设计器正式图的删除、回收、只读预览、恢复、彻底清除  
> 状态：已按本初稿实现（待手工回归）

## 1. 已定口径

| 项 | 定稿 |
|----|------|
| 删除范围 | 一次删除该 `graph_id` 的全部定义版本行和启用指针 |
| 快照 | 回收一行，JSON 带全部历史版本 + 删除前启用指针 |
| 区分键 | 列名 `recycle_version`，禁止与 DSL `version`（如 `1.0.22`）混用 |
| `recycle_version` 格式 | 服务器本地时间 `yyyy-MM-dd HH:mm:ss.SSS` + 4 位随机字符（`A-Z` `a-z` `0-9`） |
| 内置图 | 不删除、不进回收箱；删除与恢复接口直接拒绝 |
| 恢复冲突 | 正式定义表或启用表已有同 `graph_id` 时拒绝，提示「请先删除正式列表中的同名图」；检查放在恢复事务内；不自动覆盖 |
| 恢复后启用状态 | 按快照里的启用指针原样写回；当时已发布则恢复后仍是已发布。未发布则不写启用表，并卸掉本进程缓存 |
| 子图引用 | 打开删除确认框时只读预检；扫其他正式图的全部定义版本，按 `graphId` 去重；不阻断。删除事务内不再扫描 |
| 连带资源 | 不删 Agent 节点、脚本节点（多图共享） |
| 预览 | 独立「回收预览」模式，不复用内置图 `readOnly` |
| 二次确认 | 删除：输入 `graphId` 后确认按钮才可用。清除：用户只输入 `graphId` 与 `recycle_version` 末 4 位；前端从当前选中行带上 `recycleVersion` 全串。后端三者都校验，大小写敏感 |
| 运行时缓存 | 删除与恢复的数据库事务提交成功后，再对本进程 `refresh` 或卸载。失败不回滚，接口仍成功，打错误日志 |

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

### 4.1 子图引用预检

`GET /api/graph/definitions/{graphId}/subgraph-referrers`

权限：`graph:delete`。只读，不写回收表，不删正式图。

响应：`{ "graphIds": ["order-flow", "pay-flow"] }`

扫描范围：除自身外，其他正式图的全部定义版本（含未发布草稿和历史版本）。用 `NodeRef.graphIdOf` 去掉 `subgraphRef` 的 `@version` 后，与路径 `graphId` 比较；引用方按 `graphId` 去重。

打开删除确认框时调用，框内展示该列表，用户仍可删除。预检与真正删除之间新增的引用，确认框里看不到。警告不阻断，不为它把 JSON 扫描放进写事务。

### 4.2 删除

`POST /api/graph/definitions/{graphId}/recycle`

请求体：`{ "confirmGraphId": "..." }`

数据库事务内：

1. `confirmGraphId` 与路径 `graphId` 不一致 → 拒绝。
2. 内置图 → 拒绝，不写回收表。
3. 正式库无该 `graph_id` → 拒绝。
4. 生成 `recycle_version`；唯一键冲突则换 4 位随机串重试。
5. 插入回收行（全量快照）。
6. 删除该 `graph_id` 的全部定义行和启用行。

事务内不扫描子图引用。响应只带 `recycleVersion`。

提交成功后，本进程从 `GraphRuntime` 卸掉 `enabledGraphs` / `versionCache` / `lastCheckTimes`。卸载失败不回滚数据库，接口仍返回成功，打错误日志。库已经以回收为准，回滚会把图又变回正式图。

运行时兜底：`GraphRuntime.get` 发现启用行已不存在时，卸掉本进程上述三份缓存，不再返回该图。显式卸载若漏掉，TTL 到期后的下一次 `get` 仍能卸掉。多实例只保证本进程立即失效；其他实例在 TTL 内仍可能命中旧缓存，本方案不额外做广播。

### 4.3 列表与单条

- `GET /api/graph/definitions/recycle`：回收列表（`graphId`、`displayName`、`recycleVersion`、`deletedAt`）。
- `GET /api/graph/definitions/recycle/{graphId}?recycleVersion=`：返回快照，供预览。`recycleVersion` 含空格与冒号，放查询参数，不放路径。

### 4.4 恢复

`POST /api/graph/definitions/recycle/{graphId}/restore`

请求体：`{ "recycleVersion": "..." }`

事务开始前拒绝：

1. 回收行不存在。
2. `enabledVersion` 非空，但快照的 `definitions` 里没有这个 DSL 版本。
3. 该 `graphId` 是内置图。正常路径进不了回收箱，这里挡住脏数据写进正式库。

同一数据库事务内：

1. 正式定义表或启用表已有该 `graph_id` → 拒绝，文案固定为「请先删除正式列表中的同名图」。放在事务内，避免两次恢复同时通过检查。
2. 按快照插入全部定义行。
3. `enabledVersion` 非空则写启用表；为空则不写。
4. 删除这一条回收行。

提交成功后：

- 写了启用指针：本进程 `refresh` 该图。
- 没写启用指针：本进程卸掉 `enabledGraphs` / `versionCache` / `lastCheckTimes`。删除时若卸载失败，TTL 内缓存还在；恢复成未发布却不卸，本进程会继续跑删除前的已发布图。
- `refresh` 或卸载失败：不回滚数据库，接口仍返回成功，打错误日志。

### 4.5 清除

`POST /api/graph/definitions/recycle/{graphId}/purge`

请求体三个字段都必填：

```json
{
  "recycleVersion": "2026-10-16 23:28:01.123Ab3x",
  "confirmGraphId": "bill-check",
  "confirmSuffix": "Ab3x"
}
```

- `recycleVersion` 与路径 `graphId` 一起定位回收行。同一 `graphId` 有多条时靠它选中目标。
- `confirmGraphId` 必须与路径 `graphId` 完全一致。
- `confirmSuffix` 必须等于这条 `recycleVersion` 的末 4 位。
- 任一不符即拒绝，不删。比较区分大小写。

用户只输入 `graphId` 和末 4 位。`recycleVersion` 全串由前端从当前选中行带上，不用用户再敲时间戳。

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
- 正式图项增加删除。打开确认框时调用子图引用预检，框内展示 `graphIds`。输入与当前 `graphId` 完全一致后，「确认删除」才可点。
- 回收项提供「恢复」「清除」。清除框只让用户输入 `graphId` 与末 4 位，两者都对上按钮才可点。提交时前端从当前选中行带上 `recycleVersion` 全串。

## 6. 权限

新增菜单码，缺省不放开给只读账号：

| 码 | 用途 |
|----|------|
| `graph:delete` | 删除进回收箱；子图引用预检 |
| `graph:restore` | 从回收箱恢复 |
| `graph:purge` | 彻底清除 |

查看回收箱沿用 `graph:view`。宿主权限表需同步这三项，否则按钮不出现。

## 7. 实施顺序

1. 回收表 + 仓储（JDBC、SQLite）与快照读写单测。
2. 删除 / 列表 / 恢复 / 清除接口，含口令校验、内置图拒绝、正式图冲突拒绝。删除与恢复的事务只包数据库；提交成功后再 `refresh` 或 evict，失败不回滚。恢复未发布时卸本进程缓存。`get` 在启用行缺失时卸本进程缓存。
3. 子图引用预检接口。确认框打开时调用，不放进删除事务。
4. 左栏回收抽屉 + 回收预览模式（工具栏隐藏、画布不可编辑）。
5. 删除与清除的二次确认框。清除提交带选中行的 `recycleVersion` 全串。
6. 设计器手工回归：删正式图、确认框能看到引用方、回收箱只读打开、同名冲突拒绝恢复、恢复后启用状态与删除前一致、清除后列表消失、内置图无删除入口、Agent/脚本节点仍在。

## 8. 工作量

约 5～6 人日。不含多实例缓存广播，不含级联删除节点资源。
