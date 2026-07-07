# Changelog

All notable changes to the Ace Graph DSL project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] — 2026-07-07

### Added

- **连线参数可达性校验（后端）**：`EdgeParamReachabilityValidator` 接入 `GraphValidator` 第 7 项；`publish` / `validate` 触发；`draft` 不校验。豁免 `__START__` 出边与目标为 `HITL` 的入边。
- **连线参数校验（前端）**：`edgeParamValidation.js` + `edgeParamIssues`；左下角 `EdgeParamValidationPanel` 悬浮提示；失败连线 `paramInvalid` 标红（增量 `setProperties`）。
- **自定义画布元素**：`DspNode.js`（分类配色 SVG 节点、ROUTER 六边形）、`DspEdge.js`（贝塞尔连线）。

### Changed

- **HITL 节点配色**：画布与节点面板标签由红色系改为紫色，与校验失败红色连线区分。
- **文档**：UI / 后端 README、`PROJECT_OVERVIEW.md`、`FUTURE_OPTIMIZATION_PLAN.md`（v1.9）、`BUILTIN_GRAPH_GUIDE.md` 补充校验与画布说明；UI 快问快答增加 Vue 2 集成说明。

## [1.0.2] — 2026-07-03

### Added

- **多实例图懒加载**：`GraphRuntime.get()` 新增 DB 版本检查 + TTL 机制（`ace.graph.dsl.runtime.cache-ttl-seconds`），多实例部署时自动感知发布/回滚变更。
- **多实例脚本节点同步**：`DynamicGraphBuilder.ensureScriptNodesLoaded()` 编译前从 DB 重新加载所有 `script:*` 节点，解决其他实例创建/修改节点后本地注册中心过时问题。
- **脚本节点管理 UI**：`NodePanel` 新增编辑/删除按钮；`ScriptNodeEditor` 支持编辑模式（预填 + 更新 API）；删除前自动调用引用检查 API。
- **孤儿节点检测**：`GET /api/graph/nodes/orphans` + `GET /api/graph/nodes/references?nodeId=`。
- **生产部署问题文档**：`docs/PRODUCTION_DEPLOYMENT_ISSUES.md`，含四类问题分析、多实例节点传递流程、Lambda 闭包引用链分析。

### Fixed

- **MySQL JDBC DDL 兼容性**：`JdbcGraphDefinitionRepository` (`AUTO_INCREMENT` / `TEXT` / `DEFAULT CURRENT_TIMESTAMP`)；`JdbcDynamicNodeDefinitionRepository` (`VARCHAR` PK / `DEFAULT CURRENT_TIMESTAMP`)。
- **启动顺序**：`GraphRuntime` 加 `@DependsOn("dynamicNodeBootstrapLoader")` 确保脚本节点先注册。
- **SQLite DataSource 冲突**：`type=jdbc` 时不再创建 SQLite DataSource Bean。
- **Aviator 默认示例脚本**：移除无效的 `string.trim()` 调用，改为 `seq.map` + 纯 `state` 变量。

## [1.0.1] — 2026-07-03

### Added

- **Security 集成文档**：`docs/SECURITY_INTEGRATION.md`，包含 Spring Security `GraphMenuAccessControl` 映射示例、Authority 命名约定（`ACE_*`）、CORS 协调配置、未登录只读设计器 FAQ。（4.4）
- **前端 `.d.ts` 类型声明**：`types/index.d.ts`，覆盖全部 30+ 导出（`GraphApi`、i18n、Stores、Utils、Vue 组件），`package.json` `types` + `exports.types` 已配置。（3.2）

### Changed

- **i18n 收尾**：`ScriptNodeEditor.vue` 全表单 label/placeholder/hint 替换为 `t()` 调用，补全 10 个双语 i18n keys。（6.4）
- **进度表校正**：`FUTURE_OPTIMIZATION_PLAN.md` v1.7 → 4.4/6.4/8.2 标为 ✅，8.3 标为 🔶，3.2 `.d.ts` 标记已实施。待办项清单从 8 项精简到 5 项。
- **Demo README 同步**：`m2-ace/README.md` `1.0.0-SNAPSHOT` → `1.0.0`；`web-m2-ace/README.md` `file:` 依赖描述 → `1.0.0` 版本坐标。

## [1.0.0] — 2026-07-01

### Added

#### 嵌入可用性（P0）
- REST 基础路径可配置：`ace.graph.dsl.web.base-path`（默认 `/api/graph`），通过 `AceGraphDslWebConfiguration.addPathPrefix` 注入。
- `ace.graph.dsl.web.enabled` 开关（默认 `true`）：关闭后 Controller 层不注册，仅保留运行时。
- 前端 dist 构建：`vite build`（lib 模式）产出 `dist/index.js`（ESM）+ `dist/style.css`，peer 依赖全部 external。
- `style.css` 聚合 Element Plus / LogicFlow 基础样式 + 容器兜底。
- `createGraphApi(options)` 支持 `instance`（复用宿主 axios）、`headers`、`requestInterceptor`/`responseInterceptor`、`apiPrefix`；新增 `configureGraphApi()` / `getGraphApi()`。

#### 权限与安全闭环（P1）
- 写操作菜单权限兜底：`MenuPermissionGuard.require(...)` 工具；`GraphPublishController`、`GraphDefinitionController`、`ScriptNodeController` 接入 `isGranted` 校验，未授权抛 `AccessDeniedException` → 403。
- 请求级权限缓存：`CachingGraphMenuAccessControl`（`@RequestScope` + `@Primary`）；开关 `ace.graph.dsl.access-control.cache-enabled`（默认 `true`）。
- 审计日志：`GraphAuditLogger` SPI + `GraphAuditEvent` + `Slf4jGraphAuditLogger`；`GraphRuntime`（发布/回滚）与 `ScriptNodeService`（增/改/删）接入，记录降级不影响主流程。
- CORS 内置可选：`ace.graph.dsl.web.cors.*`（默认关闭），仅作用于设计器 base-path。

#### 运行时能力（P1）
- Checkpoint Saver SPI：`CheckpointSaverProvider` + `CheckpointSaverRegistry`，按类型解析。内置 `memory`；Redisson 存在时自动注册 `redis`；未注册类型按配置告警回退或抛错。`DynamicGraphBuilder` 改为注册表解析。
- 通用图执行 / SSE 端点：`GraphExecutionController`（`POST {base}/execution/{graphId}/invoke`、`/stream`、`/resume`），受 `ace.graph.dsl.web.execution.enabled` 控制（默认 `false`）。事件结构由 `GraphExecutionEventAdapter` SPI 决定。
- ObjectMapper 注入隔离：`aceGraphDslObjectMapper` 按名注册 + `@Qualifier` 注入，与宿主全局 `ObjectMapper` 互不覆盖。

#### 可观测性与治理（P2）
- 执行可观测 trace：`GraphExecutionListener` SPI + `GraphLifecycleListenerBridge`；内置 `Slf4jGraphExecutionListener`（logger `ace.graph.dsl.trace`），开关 `ace.graph.dsl.observability.enabled`（默认 `false`）。
- 版本 diff / 回滚 UI：`VersionHistoryDrawer`（已发布 / 草稿历史 Tab、结构对比、JSON 行级 diff、一键回滚）；`VersionDiffPanel` / `JsonLineDiffView`；`Toolbar`「版本历史」入口。
- 主题 token 与 i18n：`tokens.css`（`--agd-color-*`、`--agd-panel-width-*`）；`configureGraphDslI18n` + `zh-CN` / `en-US`；Toolbar / Manager / Canvas / Version 系列已外置。ScriptNodeEditor 部分待收尾。

#### 脚本节点增强（P2）
- 脚本引擎线程池：共享守护线程池（`SynchronousQueue` + `AbortPolicy`），池大小 `ace.graph.dsl.script.execution-pool-size`（`<=0` 按 CPU 核数，下限 2），`AutoCloseable` 优雅关闭。
- 脚本条件边 Dispatcher：`GraphEdge` 新增 `condition`（路由表达式）+ `conditionEngine`；`ScriptEdgeActionFactory` 编译表达式为 `EdgeAction`；`DynamicGraphBuilder` / `GraphValidator` 支持脚本路由分支。

#### 工程化（P3）
- CI 流水线：`.github/workflows/ci.yml`（backend `mvn -B verify` JDK 17 + frontend `npm ci && npm run build` Node 20）。
- 发布流水线：`.github/workflows/publish.yml`（tag 触发 `mvn deploy` + `npm publish` 到 GitHub Packages）。
- 配置元数据：`spring-configuration-metadata.json` 自动生成，覆盖 `ace.graph.dsl.*`。
- 单元测试：6 个测试类（`GraphValidatorTest`、`ScriptEdgeActionFactoryTest`、`AviatorScriptEngineTest`、`GraphEdgeJsonTest`、`GraphDefinitionContentComparatorTest`、`DraftSaveValidatorTest`）。

### Known Limitations

- 多租户隔离（§6.2）：当前仅支持「一实例一租户」部署。
- 多脚本引擎（§7.2）：仅内置 Aviator 引擎，SPI 可扩展。
- 集成测试：`DynamicGraphBuilder` / `ScriptNodeService` / 权限解析待补。

---

## Prior Versions

The project began with inline copies of DSL framework code in the `-dsl` demo modules.
Version `1.0.0` is the first release that packages the framework as a reusable library
(`ace-graph-dsl-backend` Maven artifact + `@acelance/graph-dsl-ui` npm package).

Detailed design history and phased rollout records can be found in:
- [FUTURE_OPTIMIZATION_PLAN.md](./ace-graph-dsl-backend/docs/FUTURE_OPTIMIZATION_PLAN.md) §11 变更记录
- [LIBRARY_EMBEDDING_ROADMAP.md](./ace-graph-dsl-backend/docs/LIBRARY_EMBEDDING_ROADMAP.md)
