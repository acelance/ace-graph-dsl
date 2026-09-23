# Changelog

All notable changes to the Ace Graph DSL project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] — 2026-09-23

### Fixed

- **forceSkills 静默跳过**：有效白名单改为 `skillKeys ∪ forceSkills`；口令/点选的 skill 即使设计器未勾选也会进 L1、预激活 L2，并可 `load_skill`。

### Changed

- **流式+工具 maxRounds**：默认 **30**，配置项 `ace.graph.dsl.llm.stream-tool-max-rounds`。

## [1.0.9] — 2026-09-23

### Changed

- **有工具真流式**：streaming+tools 走 `stream().chatResponse()` 手动多轮（Spring AI 1.1.x `ToolCallAdvisor.adviseStream` 未实现）；同步路径仍用 ToolCallAdvisor。新增 `StreamingToolCallMergingManager`、`TokenChunkObserver` / `ObservingGraphStreamBridge`。终答后 Echo ChatModel 触发记忆 Advisor 落盘。

## [1.0.8] — 2026-09-22

### Changed

- **记忆 USER 展示正文 SPI**：框架 `StreamingLlmTemplate` 不再穷举 state key；新增 `MemoryDisplayUserTextResolver` / `KeyListMemoryDisplayUserTextResolver`，由业务侧 Bean 决定兜底 key。未注册 SPI 时不写 `display_content`。
- **多节点即时落盘**：出口节点 Ordered Advisor `writeUser=false`；非出口 `READ_WRITE` 写 USER（经 SPI）+ ASSISTANT（可挂 `extras.thinking`）。

## [1.0.7] — 2026-09-22

### Fixed

- **构图保留键**：`DynamicGraphBuilder` 自动为 `conversationId` / `agentCode` / `runId` / `forceSkills` / `deepThinking` / `modelOverrides` 补 `REPLACE` KeyStrategy，避免多节点合并丢会话键导致记忆 Advisor 跳过。
- **本地 Template 兜底**：`GenericAgentNode` 本地装配 `StreamingLlmTemplate` 时注入 `ChatClientAdvisorProvider` 与 `PromptContentResolver`，避免无 Bean 路径下对话记忆整段失效。

### Added

- **设计器 `memoryMode`**：属性面板 / Agent 编辑器可配置 `NONE` | `READ_ONLY` | `READ_WRITE`（节点 LLM 完成即按 Advisor 落盘，非图尾统一 persist）。

## [1.0.6]

### Changed

- **多模态分流（§8.2 / §8.3）**：`MediaRefResolver.ResolveResult` 增加 `materialNotes`。图片/音视频进 `medias`（`UserMessage.media`）；xlsx/pdf/docx 等进材料注记并追加到 user 文本，供工具/Skill 读 `url=`；不安全 URL 仍进 `skippedNotes`。`DefaultMediaRefResolver` / `StreamingLlmTemplate` 已接线。图配置无需改拓扑（`mediaInputKey` 仍指向 state 引用列表）。
- **启动顺序**：`GraphRuntime` 同时 `@DependsOn` 脚本节点与 GenericAgent 节点 bootstrap，避免引用型 GENERIC_AGENT 在注册中心未就绪时编译失败。
- **文档（设计期资源目录，浏览参数未改代码）**：定案注册式 Agent 与图内联 spec 的分工，以及 Catalog 浏览参数 `agentCode`（框架只传递）和 `bizKey`（不透明字符串，业务自解析）。见 [designer-resource-catalog-browse.md](docs/designer-resource-catalog-browse.md)。
- **设计器**：节点面板「编辑通用 Agent」打开时请求 `GET /api/agent-resources/mcp` 与 `/skills`，不带 `graphId`。MCP 有数据时用三级树写回 `mcpKeys` / `mcpToolWhitelist`；Skill 有数据时多选且关闭 `allow-create`。目录为空与加载失败分开提示。属性面板的 Skill 多选同样关闭手填入选。
- **设计器 MCP 树**：server 节点显示为资源编码(显示名称)，例如 `tianyancha(天眼查)`；工具节点只显示资源编码。写入 `mcpKeys` 的仍是资源编码。
- **设计器 Skill**：多选来自注册中心的全部 skill，不再被进程 `lesso.ai.skills` 热刷新白名单裁成一两条。选项显示为资源编码(显示名称)，写入 `skillKeys` 的仍是资源编码。
- **设计器 Skill UI**：与 MCP 相同的勾选树（扁平），不再用下拉。
- **设计器发布**：内容相对基线无变更时，不再用预占的下一版号（如 1.0.3）去发布；回落到已存在的基线版本（如 1.0.2），避免「版本不存在」。

## [1.2.0] — 2026-08-08

### Added

- **通用 Agent 节点（双通道范式）**：对齐「脚本节点范式」——先定义 → 入库 → 复用。
  - 独立实体 `GenericAgentDefinition`（JSON in `content_json`），不复用脚本节点表。
  - 注册式（引用型）：图中仅存 `nodeId`，`origin=GENERIC_AGENT`，元数据来自持久化定义，可跨图复用。
  - 内联式：结构型节点拖入时携带完整 `agentSpec`，ad-hoc 编译执行。
  - 后端：`GenericAgentNodeService`（镜像 `ScriptNodeService`，含草稿图、引用/孤儿查询、审计）、`GenericAgentController`（`/agents`）、`GenericAgentNodeBootstrapLoader` 启动加载入库、`McpServerRegistry` SPI（prompt/skill/mcp 按 key 解析）。
  - 前端：`AgentNodeEditor.vue`（12 字段模态框，含掩码 API Key 处理、校验、试跑）、`NodePanel.vue` AGENT 标签 + 新建/编辑/删除分流、`Canvas.vue` 双通道拖入、`PropertyPanel.vue` 内联 vs 注册式区分 UX、`stores/agentEditorBus.js` 跨组件总线、`permissions.js` 菜单权限。
- **子图循环引用检测**：`GraphValidator` 与 `DynamicGraphBuilder` 在编译期检测跨图 `subgraphRef` 环（A→B→A），避免 `StackOverflowError`。
- **子图嵌套深度限制（≤3 层）**：`GraphValidator.validate()` 与 `DynamicGraphBuilder.doBuild()` 均加入 `MAX_SUBGRAPH_DEPTH = 3` 双重防护（根图 depth=0，子图递增，>3 报错）。
- **子图引用版本锁定（P2）**：`subgraphRef` 支持 `graphId@version` 格式锁定特定版本；纯 `graphId` 仍取最新（向后兼容）。后端 `NodeRef.graphIdOf()` / `versionOf()` 解析，`DynamicGraphBuilder.resolveSubgraph()` 按 version 选 `loadVersion` / `loadLatest`，循环引用检测基于剥离版本的 graphId；`GraphValidator` 校验 `@` 后版本非空。前端引用选择器下方新增版本锁定下拉（选图后懒加载版本列表，默认「最新」）。
- **子图状态隔离验证（P3）**：新增 `SubgraphStateIsolationTest`（4 场景集成测试，直接使用 spring-ai-alibaba-graph 原生 API）。验证结论：子图与父图**状态共享**（非隔离）；APPEND 跨父子图边界会产生**数据重复**（父图原始数据被子图继承后二次追加）；REPLACE 覆盖与新 key 传播行为正常。详见 [子图状态隔离验证报告](../SUBGRAPH_STATE_ISOLATION_VERIFICATION.md)。

### Changed

- **子图引用选择器 UX**：内联/引用 radio 切换改为纯 UI（`subgraphModeOverride`），不再触发 store mutation 导致选中丢失；引用下拉框预加载 `graphIds`、排除当前图自身、显示 `displayName (graphId)`。
- **子图面包屑可见性**：浮动面包屑条移至 `GraphDslDesigner.vue` 画布上方，含返回上级按钮、路径、`X/3` 深度指示器，提升下钻后可返回主图的可见性。
- **节点面板按钮归位**：新建脚本节点 / 新建通用 Agent 按钮分别移入对应 tab（脚本 tab / AGENT tab），不再每个 tab 都出现；结构节点区域移除空白的 `GENERIC_AGENT` 结构型节点。
- **文档**：新增 [结构节点评估与方案](../STRUCTURAL_NODE_ASSESSMENT.md)；更新前端 README「通用 Agent 节点」「子图」用法、设计器能力评估 v1.4、后端 README 节点类别与 GraphValidator 校验项。

### Removed

- **结构型 AGENT 节点面板入口**：从节点面板结构节点区域移除不可配置的「代码岛」AGENT 节点（拖入后无法配置、运行时 `toAction()` 抛异常）。后端 `ScriptAgentNode` / `RegisteredAgentNode` / `AgentConfig` 及构建器分支全部保留，已有图数据不受影响。

### Known Limitations

- 子图 APPEND 跨边界数据重复：父子图共享 key 用 APPEND 时，父图原始数据被子图继承后二次追加（spring-ai-alibaba-graph `SubCompiledGraphNodeAction` 固有行为）。建议共享 key 用 REPLACE，详见 [验证报告](../SUBGRAPH_STATE_ISOLATION_VERIFICATION.md)。

## [1.1.0] — 2026-07-14

### Added

- **多脚本引擎**：`ScriptEngineDescriptor`、`AbstractTimeoutScriptEngine`；SpEL 超时隔离与安全加固（禁 `T()` / BeanResolver）。
- **可选模块**：`ace-graph-dsl-script-qlexpress`、`ace-graph-dsl-script-groovy`（starter `optional` + `@ConditionalOnClass` / 开关）。
- **API**：`GET /nodes/engines` 返回 `multiLine` / `maxScriptLines` / `hintKey`。
- **前端**：脚本节点与条件边按引擎切换多行编辑区与 hint（i18n）；Groovy 默认不出现在引擎列表。

### Changed

- **文档**：`MULTI_SCRIPT_ENGINE_PLAN` v2.2、`FUTURE_OPTIMIZATION_PLAN` §7.2 ✅、`SCRIPT_NODE_EXAMPLES` 四引擎样例、`PROJECT_OVERVIEW` 模块说明、UI README「如何选择脚本引擎」。
- **测试（2026-07-15）**：新增 [MULTI_SCRIPT_ENGINE_TEST_PLAN.md](ace-graph-dsl-backend/docs/MULTI_SCRIPT_ENGINE_TEST_PLAN.md)；补齐 `ScriptNodeServiceTest`、条件边多引擎、SpEL 超时、starter 条件装配测；L1–L3 `mvn test` 通过。

### Security

- Groovy 默认 `groovy-enabled=false`；沙箱 `SecureASTCustomizer` + 导入白名单；QLExpress RiskControl；各引擎执行超时共用配置。

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
