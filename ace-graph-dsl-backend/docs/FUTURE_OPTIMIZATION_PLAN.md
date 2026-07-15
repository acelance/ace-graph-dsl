# Ace Graph DSL — 后续优化与功能规划建议

> 版本：v2.0  
> 日期：2026-07-14  
> 适用范围：`ace-graph-dsl-backend` / `ace-graph-dsl-ui` 作为第三方组件嵌入业务系统后的持续演进

---

## 1. 背景与当前基线

截至当前，库已具备以下能力：

| 能力 | 状态 |
|------|------|
| 动态图编译 / 版本管理 / 发布回滚 | ✅ |
| 节点注册中心 + 设计器 REST API | ✅ |
| 脚本节点端到端（CRUD / 校验 / 试跑） | ✅ |
| 连线参数可达性校验（前后端 + 画布标红） | ✅ |
| 自定义 SVG 节点 / 贝塞尔连线（LogicFlow v2） | ✅ |
| 节点权限 SPI（`GraphNodeAccessControl`，后端资源级） | ✅ |
| 菜单权限 SPI（`GraphMenuAccessControl`，前端功能级）+ REST + UI 接入 | ✅ |
| cs-reply 库化改造 demo（`m2-ace` / `web-m2-ace`） | ✅ |

本文在 [LIBRARY_EMBEDDING_ROADMAP.md](./LIBRARY_EMBEDDING_ROADMAP.md) 基础上，按**价值 / 成本**重新梳理后续优化项，并给出建议落地顺序。

> **实施进度（截至 v2.0）**：阶段一～三（P0–P2）主项已落地，含 **多脚本引擎（7.2）**（Aviator / SpEL / QLExpress / Groovy，见 [MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md) v2.2）。阶段四（P3）单元测试已扩面、配置元数据与 CI 已入库；SemVer 见 `CHANGELOG.md`。剩余 **多租户（6.2）** 及 7.2 非阻塞收尾（集成测试 / 切换确认框等）。详见 [§2.1 实施进度总表](#21-实施进度总表) 与 [§11 变更记录](#11-变更记录)。

### 1.1 权限模型边界（避免混淆）

| 抽象 | 控制对象 | 主要作用域 | 后端强校验 |
|------|----------|------------|------------|
| `GraphNodeAccessControl` | 业务节点 / Dispatcher / 脚本节点资源 | 后端 API 返回集合 + 写操作 | ✅ |
| `GraphMenuAccessControl` | 设计器功能按钮（新建 / 保存 / 发布等） | 前端显隐 + 可选后端兜底 | ✅ 写操作兜底已接入（见 §4.1） |

两套 SPI **独立实现、互不引用**；脚本节点「增删」可同时被两层覆盖，建议宿主接入时保持映射一致。

---

## 2. 优先级总览

| 优先级 | 主题 | 目标 |
|--------|------|------|
| **P0** | 嵌入可用性 | 降低第三方接入成本，消除已知踩坑 |
| **P1** | 权限闭环 + 运行时 | 安全纵深 + HITL / 执行能力补齐 |
| **P2** | 可观测性与治理 | 生产级运维、多租户、版本治理 UI |
| **P3** | 工程化 | 测试、CI、SemVer、配置元数据 |

### 2.1 实施进度总表

> 状态说明：**✅ 已实施** · **🔶 部分实施** · **⏳ 待办**

| 编号 | 项 | 优先级 | 状态 | 备注 |
|------|-----|--------|------|------|
| 3.1 | REST 基础路径可配置 | P0 | ✅ | `ace.graph.dsl.web.base-path` / `enabled` |
| 3.2 | 前端 dist 构建 + npm 私服 | P0 | 🔶 | 构建链路 OK；`.d.ts` 已生成 + demo 已切版本坐标；私服 publish 待办 |
| 3.3 | 补全 `style.css` 导出 | P0 | ✅ | `dist/style.css` + 源码导出 |
| 3.4 | 前端 axios / 鉴权可注入 | P0 | ✅ | `createGraphApi` / `configureGraphApi` |
| 4.1 | 写操作菜单权限兜底 | P1 | ✅ | `MenuPermissionGuard` |
| 4.2 | 权限解析缓存 | P1 | ✅ | `CachingGraphMenuAccessControl` |
| 4.3 | 操作审计日志 | P1 | ✅ | `GraphAuditLogger` SPI |
| 4.4 | CORS 与 Spring Security 示例 | P1 | ✅ | CORS 已内置；`SECURITY_INTEGRATION.md` 已创建 |
| 5.1 | JDBC / Redis checkpoint saver | P1 | ✅ | SPI + Redis 内置；JDBC 由宿主扩展 |
| 5.2 | 通用图执行 / SSE 端点 | P1 | ✅ | 默认关闭，可选开启 |
| 5.3 | ObjectMapper 注入隔离 | P1 | ✅ | 按名 `aceGraphDslObjectMapper` |
| 6.1 | Langfuse / OpenTelemetry trace | P2 | ✅ | SPI + `Slf4jGraphExecutionListener` |
| 6.2 | 多租户隔离 | P2 | ⏳ | 无 `tenantId`；后端未消费 `X-Tenant-Id` |
| 6.3 | 版本 diff / 回滚 UI | P2 | ✅ | `VersionHistoryDrawer` + JSON/结构 diff |
| 6.4 | 主题 token 与 i18n | P2 | ✅ | `tokens.css` + i18n 框架 + `ScriptNodeEditor` 已外置 |
| 7.1 | 脚本引擎线程池 | P2 | ✅ | 共享线程池 + 可配置 |
| 7.2 | 多脚本引擎 | P2 | ✅ | 四引擎 + optional 模块 + 前端 multiLine/条件边；见 MULTI_SCRIPT_ENGINE_PLAN v2.2 |
| 7.3 | 脚本版条件边 Dispatcher | P2 | ✅ | `condition` + `ScriptEdgeActionFactory` |
| 8.1 | 单元 / 集成测试 | P3 | 🔶 | 多引擎 L1–L3 已绿；MockMvc/同图 e2e 待补，见 TEST_PLAN |
| 8.2 | CI 流水线 | P3 | ✅ | `.github/workflows/ci.yml` + `publish.yml` |
| 8.3 | SemVer 发布 | P3 | 🔶 | 版本 1.0.0；demo 已切版本坐标；`CHANGELOG.md` 已创建；私服发布待运维 |
| 8.4 | 配置元数据 | P3 | ✅ | `spring-configuration-metadata` |
| 9.1 | 连线参数可达性校验 | P2 | ✅ | `EdgeParamReachabilityValidator` + 前端 `edgeParamValidation.js` |
| 9.2 | 画布自定义节点 / 连线 | P2 | ✅ | `DspNode.js` / `DspEdge.js`；失败边标红 |
| 9.3 | 校验提示面板 + HITL 配色 | P2 | ✅ | 左下角悬浮面板；HITL 紫色 |

---

## 3. P0 — 嵌入可用性（优先落地）

直接影响其他系统能否**顺利、低成本**接入库。

### 3.1 REST 基础路径可配置 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~Controller 路径硬编码 `/api/graph`~~ → Controller 改用相对路径，前缀统一注入 |
| **改进** | ✅ `ace.graph.dsl.web.base-path`（默认 `/api/graph`），通过 `AceGraphDslWebConfiguration` 的 `PathMatchConfigurer.addPathPrefix` 注入 |
| **附带** | ✅ `ace.graph.dsl.web.enabled`（默认 true）：关闭后整个 Controller 层不注册，仅保留运行时 |
| **价值** | 避免与宿主既有路由冲突；支持「内嵌运行时、外置设计器」部署形态 |

### 3.2 前端发布 dist 构建 + npm 私服 🔶 部分实施（构建链路）

| 项 | 说明 |
|----|------|
| **现状** | ~~`main` 指向 `src/index.js`~~ → `main`/`module`/`exports` 指向 `./dist/index.js` |
| **问题** | 消费端需 `resolve.dedupe`、`optimizeDeps.exclude`、`resolve.alias` 等适配（cs-reply-web-m2-ace 曾遇 `axios` 解析失败） |
| **改进** | ✅ `vite build`（lib 模式）产出 `dist/index.js`（ESM）+ `dist/style.css`，peer 依赖（vue/pinia/axios/element-plus/logicflow）全部 external |
| **保留** | `./src` 子路径导出仍可源码直引；npm 私服发布为运维动作（非代码） |
| **待办** | 正式 publish 到私服（类型声明 `types/index.d.ts` 已生成，`package.json` `types` 字段已配置） |
| **价值** | 宿主 `npm install` 即可用，无需 Vite 特殊配置 |

### 3.3 补全 `style.css` 导出 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~`"./style": "./src/style.css"` 文件不存在~~ |
| **改进** | ✅ 新增 `src/style.css`（聚合 Element Plus / LogicFlow 基础样式 + 容器兜底）；`./style` 指向构建产物 `dist/style.css`，并保留 `./src/style.css` 源码导出 |
| **价值** | 文档与 API 契约一致，避免按文档引样式 404 |

### 3.4 前端 axios / 鉴权可注入 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~`createGraphApi` 内部固定 `axios.create({ baseURL })`~~ |
| **改进** | ✅ `createGraphApi(options)` 支持 `instance`（复用宿主 axios）、`headers`、`requestInterceptor`/`responseInterceptor`、`apiPrefix`（对齐后端 base-path）；新增 `configureGraphApi()` 可重配默认实例，命名导出改为委托默认实例 |
| **兼容** | `createGraphApi('/base')` 字符串入参向后兼容 |
| **价值** | Token、traceId、租户头等可透传，对接真实业务网关与鉴权 |

---

## 4. P1 — 权限与安全闭环

延续已实现的节点权限 + 菜单权限，补齐「前端显隐 + 后端拦截」完整链路。

### 4.1 写操作接入菜单权限兜底 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~写操作 Controller 尚未统一调用 `isGranted(key)`~~ |
| **改进** | ✅ 新增 `MenuPermissionGuard.require(...)` 工具；`GraphPublishController`（发布/回滚）、`GraphDefinitionController`（保存/校验/预览）、`ScriptNodeController`（增删改/试跑/校验）均接入服务端兜底，未授权抛 `AccessDeniedException` → 403 |
| **价值** | 防止绕过前端直调 API；与 `GraphNodeAccessControl` 形成双层纵深 |

**建议映射：**

| 操作 | 菜单 key |
|------|----------|
| 保存草稿 | `graph:save` |
| 校验 | `graph:validate` |
| 预览 | `graph:preview` |
| 发布 | `graph:publish` |
| 回滚 | `graph:rollback` |

### 4.2 权限解析缓存 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~每次请求重新解析菜单 / 节点权限~~ |
| **改进** | ✅ `CachingGraphMenuAccessControl`（`@RequestScope` + `@Primary`）装饰真实 SPI，同一请求内 `isMenuGranted` / `grantedMenus` / `currentPrincipal` 仅委托一次；开关 `ace.graph.dsl.access-control.cache-enabled`（默认 true），通过自引用排除自动解析委托对象 |
| **价值** | 降低对接外部 RPC 权限框架时的重复调用与延迟 |

### 4.3 操作审计日志 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~发布等接口有 `operator` 字段，无集中审计落库~~ |
| **改进** | ✅ 新增 `GraphAuditLogger` SPI + `GraphAuditEvent`（action/resourceType/resourceId/version/operator/success/detail/timestamp）；默认实现 `Slf4jGraphAuditLogger`；`GraphRuntime`（发布/回滚，含失败）与 `ScriptNodeService`（增/改/删）接入，记录降级不影响主流程 |
| **扩展** | 宿主实现 `GraphAuditLogger` 即可落库 / 推送统一审计中心（`@ConditionalOnMissingBean`） |
| **价值** | 生产环境合规与问题追溯 |

### 4.4 CORS 与 Spring Security 打通示例 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~无内置 CORS~~；`AccessDeniedException` 已映射 403 |
| **改进** | ✅ 可选内置 CORS：`ace.graph.dsl.web.cors.*`（默认关闭），仅作用于设计器 base-path 路径，支持 `allowed-origins`（origin patterns）/`methods`/`headers`/`exposed-headers`/`allow-credentials`/`max-age` |
| **改进** | ✅ `SECURITY_INTEGRATION.md` 已创建：Spring Security `GraphMenuAccessControl` 映射示例、Authority 命名约定（`ACE_*`）、CORS 协调配置、未登录只读设计器、自定义菜单项等 FAQ |
| **价值** | 降低前后端分离部署时的联调成本 |

---

## 5. P1 — 运行时能力补齐

### 5.1 JDBC / Redis checkpoint saver ✅ 已实施（SPI + Redis 内置）

| 项 | 说明 |
|----|------|
| **现状** | ~~`compile.saver` 仅 `memory`；jdbc / redis 静默落回 memory~~ |
| **改进** | ✅ 新增 `CheckpointSaverProvider` SPI + `CheckpointSaverRegistry`，`DynamicGraphBuilder` 改为按类型解析；内置 `memory`；starter 在 Redisson 存在时自动注册 `redis`（`RedisSaver.builder()`）；未注册类型按 `ace.graph.dsl.checkpoint.fallback-to-memory` 告警回退或抛错（不再静默） |
| **扩展** | jdbc/mysql/postgresql/file 等：宿主注册 `CheckpointSaverProvider` Bean 即可（库 1.1.2.2 已含对应 Saver builder） |
| **价值** | HITL 场景重启不丢 checkpoint；多实例部署可共享状态；类型错误显式可见 |

### 5.2 通用图执行 / SSE 端点（可选）✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~图执行逻辑需业务自写（如 cs-reply 的 `CsReplyM2AceController`）~~ |
| **改进** | ✅ 可选 starter 端点 `GraphExecutionController`：`POST {base}/execution/{graphId}/invoke`（同步）、`/stream`（SSE）、`/resume`（HITL 续跑，SSE）；事件结构由 `GraphExecutionEventAdapter` SPI 决定，默认实现 `DefaultGraphExecutionEventAdapter` 输出 `{type,node,data/chunk}`。受 `ace.graph.dsl.web.execution.enabled`（默认 `false`）控制 |
| **价值** | 纯编排类业务可零代码跑通；复杂业务仍可自写 Controller 扩展 |

### 5.3 ObjectMapper 注入隔离 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~`aceGraphDslObjectMapper` 使用按类型 `@ConditionalOnMissingBean`，可能与宿主全局配置冲突~~ |
| **改进** | ✅ Bean 以限定名 `aceGraphDslObjectMapper`（`AceGraphDslBeans.OBJECT_MAPPER`）注册（`@ConditionalOnMissingBean(name=...)`），库内部持久化/引导组件统一以 `@Qualifier` 显式注入 |
| **价值** | 与宿主全局 `ObjectMapper` 互不覆盖，隔离 JSON 序列化行为 |

---

## 6. P2 — 可观测性与治理

### 6.1 Langfuse / OpenTelemetry trace ✅ 已实施（SPI + 默认实现）

| 项 | 说明 |
|----|------|
| **参考** | demo M3 的 Langfuse 接入 |
| **改进** | ✅ 新增 `GraphExecutionListener` SPI（onStart/before/after/onError/onComplete，节点级），经 `GraphLifecycleListenerBridge` 桥接到 graph-core `CompileConfig.withLifecycleListener`；宿主实现并声明为 `@Component` 即接入 Langfuse/OTel。内置 `Slf4jGraphExecutionListener`（节点耗时日志，logger `ace.graph.dsl.trace`），由 `ace.graph.dsl.observability.enabled`（默认 false）开关 |
| **价值** | 生产问题定位、性能分析；具体 trace 后端（Langfuse/OTel）由宿主以 SPI 实现注入 |

### 6.2 多租户隔离 ⏳ 待办

| 项 | 说明 |
|----|------|
| **现状** | `graphId` 全局唯一；前端 `createGraphApi` 可透传 `X-Tenant-Id`，后端未消费 |
| **改进** | 引入 `tenantId` 维度：持久化命名空间、API 前缀、权限隔离 |
| **价值** | SaaS / 多业务线共用一套 ace-graph-dsl 实例 |

### 6.3 版本 diff / 回滚 UI ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~后端已有 `rollback` API；前端无版本历史与 diff~~ → 前端已完整落地 |
| **改进** | ✅ `VersionHistoryDrawer`（已发布 / 草稿历史 Tab、结构对比、JSON 行级 diff、一键回滚）；`VersionDiffPanel` / `JsonLineDiffView`；`Toolbar`「版本历史」入口；对接 `listVersions` / `getVersion` / `getEnabled` / `rollback` |
| **价值** | 降低运维与排错成本 |

### 6.4 主题 token 与 i18n ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~配色与文案硬编码中文~~ → 全组件文案已外置 |
| **改进** | ✅ `tokens.css`（`--agd-color-*`、`--agd-panel-width-*` 等）；`configureGraphDslI18n` + `zh-CN` / `en-US`；Toolbar / Manager / Canvas / Version / ScriptNodeEditor 全系列已用 `t()`。**待办**：`configureGraphDslTheme` 等主题 API |
| **价值** | 嵌入宿主 UI 时风格与语言可统一 |

---

## 7. P2 — 脚本节点增强

### 7.1 脚本引擎线程池 ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~`AviatorScriptEngine.execute` 每次 `new Thread`~~ |
| **改进** | ✅ 共享守护线程池（`SynchronousQueue` + `AbortPolicy`，保留超时语义，池满抛繁忙）；实现 `AutoCloseable`，由 Spring 推断销毁方法优雅关闭；池大小 `ace.graph.dsl.script.execution-pool-size`（<=0 按 CPU 核数，下限 2） |
| **价值** | 高并发下降低线程创建开销 |

### 7.2 多脚本引擎 ✅ 已实施

| 项 | 说明 |
|----|------|
| **参考** | [MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md) v2.2 |
| **现状** | Aviator / SpEL（加固）在 core；QLExpress / Groovy 为 optional 子模块；`ScriptEngineDescriptor` + `/nodes/engines`；前端脚本节点与条件边可选引擎；Groovy 默认关闭 |
| **价值** | 按场景选引擎，复杂规则可读可维护，安全边界不降低 |
| **残余** | 多引擎条件边单测扩面、集成测试、切换引擎确认框、运维 Groovy 开启手册（见方案 §2.3） |

### 7.3 脚本版条件边 Dispatcher ✅ 已实施

| 项 | 说明 |
|----|------|
| **现状** | ~~脚本仅支持节点；条件边 Dispatcher 须 Java 实现~~ |
| **改进** | ✅ `GraphEdge` 新增 `condition`（路由表达式）+ `conditionEngine`（默认 aviator）字段；条件边可仅填 `condition`（脚本路由，返回 mapping 的 key）替代 Java `dispatcher`。新增 `ScriptEdgeActionFactory` 编译表达式为 `EdgeAction`；`DynamicGraphBuilder`/`GraphValidator` 支持脚本路由分支（dispatcher 与 condition 同时存在时 dispatcher 优先） |
| **价值** | 路由规则也可在 DSL 中动态配置，减少为分支逻辑写 Java 并发版 |

---

## 8. P3 — 工程化

| 项 | 状态 | 说明 |
|----|------|------|
| **单元 / 集成测试** | 🔶 部分实施 | ✅ L1–L3：引擎单测、`ScriptNodeServiceTest`、条件边多引擎、`ApplicationContextRunner` 开关测已通（见 [MULTI_SCRIPT_ENGINE_TEST_PLAN.md](./MULTI_SCRIPT_ENGINE_TEST_PLAN.md)）。**待办**：MockMvc Controller、四引擎同图 e2e |
| **CI 流水线** | ✅ 已实施 | `.github/workflows/ci.yml`（backend `mvn -B verify` JDK 17 + frontend `npm ci && npm run build` Node 20）+ `publish.yml`（tag 触发 `mvn deploy` + `npm publish`）已入库 |
| **SemVer 发布** | 🔶 部分实施 | 版本 1.0.0；demo 已切版本坐标（`package.json` `1.0.0` / `pom.xml` `1.0.0`）；`CHANGELOG.md` 已创建；正式发布到私服待运维执行 |
| **配置元数据** | ✅ 已实施 | `spring-configuration-metadata.json` 自动生成，覆盖 `ace.graph.dsl.observability.*`、`ace.graph.dsl.web.execution.*` 等 |

---

## 9. 建议落地顺序

```mermaid
flowchart LR
    A["阶段一 P0<br/>嵌入基线"] --> B["阶段二 P1<br/>权限闭环 + 运行时"]
    B --> C["阶段三 P2<br/>可观测 + 治理"]
    C --> D["阶段四 P3<br/>工程化"]
```

### 阶段一（P0）— 嵌入基线

1. REST `base-path` + `web.enabled`
2. 前端 dist 构建与 npm 发布
3. `style.css` 修复
4. `createGraphApi` axios 可注入

**预期收益**：第三方接入从「需读源码调 Vite」变为「引依赖即用」。

### 阶段二（P1）— 权限闭环 + 运行时

5. 写操作接入 `GraphMenuPermissionResolver.isGranted()` ✅
6. JDBC / Redis saver ✅（SPI + Redis 内置；JDBC 由宿主 provider 扩展）
7. 权限请求级缓存 + 审计日志 ✅
8. （可选）通用 invoke/stream 端点 ✅

**预期收益**：生产可上线的安全与 HITL 能力。

### 阶段三（P2）— 可观测与治理

9. Langfuse / OTel trace ✅（SPI + 默认实现；具体后端宿主注入）
10. 多租户 ⏳（架构级，待规划）
11. 版本 diff / 回滚 UI ✅（`VersionHistoryDrawer` + 结构/JSON diff + 回滚）
12. 主题 token / i18n 🔶（框架已落地，文案覆盖待收尾）
13. 脚本引擎线程池 ✅ + 脚本 Dispatcher ✅

**预期收益**：规模化运维与动态编排能力增强。

### 阶段四（P3）— 工程化

14. 单元测试 🔶（6 类单测）+ CI ⏳ + SemVer ⏳ + 配置元数据 ✅

---

## 10. 近期推荐起步包（低成本 / 高价值）

若资源有限，建议优先做以下三项（可并行）：

| # | 项 | 预估工作量 | 理由 | 状态 |
|---|-----|------------|------|------|
| 1 | `ace.graph.dsl.web.base-path` | 小 | 路由冲突是嵌入最常见问题 | ✅ 已实施 |
| 2 | `createGraphApi` 支持注入 axios | 小 | 鉴权透传是业务接入刚需 | ✅ 已实施 |
| 3 | 发布 / 回滚等写操作菜单权限兜底 | 小 | 闭环已有 SPI，补 Controller 调用即可 | ✅ 已实施 |

---

## 11. 变更记录

> **v2.1**（2026-07-15）· 多脚本引擎测试联调：新增 `MULTI_SCRIPT_ENGINE_TEST_PLAN.md`；补齐 `ScriptNodeServiceTest`、条件边多引擎、SpEL 超时、starter `ApplicationContextRunner`；L1–L3 `mvn test` 通过。

> **v2.0**（2026-07-14）· 多脚本引擎 Phase A/B/C 功能落地并完成文档同步：SpEL 加固、`ace-graph-dsl-script-qlexpress` / `ace-graph-dsl-script-groovy` optional 模块、Descriptor API、前端 multiLine 与条件边引擎选择；§7.2 标 ✅；样例与 CHANGELOG `[1.1.0]` 已更新。

> **v1.9**（2026-07-07）· 连线参数可达性校验全链路：后端 `EdgeParamReachabilityValidator` 接入 `GraphValidator`（发布/校验触发，草稿豁免）；前端实时校验 + 左下角悬浮提示 + 失败连线标红；START / HITL 入边豁免；HITL 节点配色改为紫色；UI README 快问快答补充 Vue 2 集成说明。

> **v1.8**（2026-07-03）· 实施四类生产部署优化：多实例图懒加载（`GraphRuntime.get()` DB 版本检查 + TTL）；多实例脚本节点同步（`ensureScriptNodesLoaded()` 编译前从 DB 重新加载）；脚本节点管理 UI（NodePanel 编辑/删除 + ScriptNodeEditor 编辑模式 + 引用检查）；孤儿节点检测 API（`GET /nodes/orphans` + `GET /nodes/references`）；MySQL JDBC DDL 兼容性修复。详见 [PRODUCTION_DEPLOYMENT_ISSUES.md](./PRODUCTION_DEPLOYMENT_ISSUES.md)。
>
> **v1.7**（2026-07-03）· 收尾多项未完成项：4.4 Security 集成文档（`SECURITY_INTEGRATION.md`）已创建；6.4 `ScriptNodeEditor` 全表单文案外置 → 6.4 完全落地标 ✅；8.2 CI 流水线已在仓库确认为已入库 → 标 ✅；8.3 SemVer 后端/前端/demo 均已完成去 SNAPSHOT + 去 `file:` → 标 🔶（待私服发布）；`CHANGELOG.md` 已创建；demo README 版本描述同步。待办项清单移除 6.4 / 4.4 / CI，SemVer 降级为部分实施。
>
> **v1.6**（2026-07-01）· 对照代码库刷新进度表：6.3 版本 diff UI 标为已实施；6.4 / 3.2 / 4.4 / P3 测试标为部分实施；CI 更正为待办（workflow 未入库）；新增 [§2.1 实施进度总表](#21-实施进度总表)。
>
> **v1.5** · 在 v1.4 基础上新增「脚本条件边 Dispatcher（7.3）+ 可观测 trace SPI（6.1）+ 单元测试起步（P3）」
>
> **v1.4** · 在 v1.3 基础上新增「通用图执行 / SSE 端点（invoke/stream/resume + 事件适配 SPI）」
>
> **v1.3** · 在 v1.2 基础上新增「Checkpoint Saver SPI + Redis 内置 saver」
>
> **v1.2** · 在 v1.1 基础上新增「审计日志 + 请求级权限缓存 + CORS + 脚本引擎线程池」
>
> **v1.1** · 覆盖阶段一（P0）全部 + 阶段二（P1）权限/隔离部分

### v1.6 增量（版本治理 UI + 进度表校正）

#### 1）版本 diff / 回滚 UI（6.3）

| 项 | 关键变更 |
|----|----------|
| 版本历史抽屉 | `VersionHistoryDrawer.vue`：已发布 / 草稿历史 Tab、版本列表、加载基线、回滚 |
| Diff 展示 | `VersionDiffPanel.vue` + `JsonLineDiffView.vue`；`graphDiff.js`（结构对比）+ `jsonLineDiff.js`（JSON 行级 diff） |
| 入口 | `Toolbar.vue`「版本历史」按钮；抽屉宽度避让左侧目录，防止与工作流列表点击穿透 |

#### 2）进度表校正（文档）

| 项 | 校正说明 |
|----|----------|
| 6.3 | 由「待实施」更正为 **✅ 已实施** |
| 6.4 / 3.2 / 4.4 / P3 测试 | 标为 **🔶 部分实施** |
| P3 CI | 由「已实施」更正为 **⏳ 待办**（`.github/workflows/` 目录尚未创建） |
| P3 单测 | 更新为 6 个测试类（含 `DraftSaveValidatorTest` 等） |

### v1.5 及更早版本（摘要）

#### 后端（`ace-graph-dsl-spring-boot-starter`）

| 项 | 关键变更 |
|----|----------|
| Web 层可配置 | 新增 `AceGraphDslProperties.Web`（`enabled` / `base-path`）；新增 `AceGraphDslWebConfiguration`（独立 auto-config，`@ConditionalOnWebApplication` + `@ConditionalOnProperty(ace.graph.dsl.web.enabled)`，`addPathPrefix` 注入前缀）；全部 Controller 改为相对路径；注册进 `AutoConfiguration.imports` |
| ObjectMapper 隔离 | 新增 `AceGraphDslBeans.OBJECT_MAPPER` 常量；Bean 按名条件注册；持久化/引导注入点加 `@Qualifier` |
| 写操作菜单兜底 | 新增 `MenuPermissionGuard`；发布/回滚/保存/校验/预览/脚本增删改/试跑接入 `isGranted` 校验 |
| 配置元数据 | `spring-configuration-metadata.json` 自动生成 `ace.graph.dsl.web.*` 含描述与默认值 |

**新增配置示例：**

```yaml
ace:
  graph:
    dsl:
      web:
        enabled: true            # 设为 false 仅保留运行时、不暴露设计器 API
        base-path: /api/graph    # 自定义前缀以避免与宿主路由冲突，如 /platform/graph-dsl
```

### 前端（`ace-graph-dsl-ui`）

| 项 | 关键变更 |
|----|----------|
| axios 可注入 | `createGraphApi(options)` 支持 `instance`/`headers`/`requestInterceptor`/`responseInterceptor`/`apiPrefix`；新增 `configureGraphApi()` / `getGraphApi()`；命名导出委托默认实例（字符串入参向后兼容） |
| dist 构建 | `vite.config.js` lib 模式（ESM + 抽取 `style.css`，peer 依赖 external）；`package.json` `main`/`exports`/`files` 指向 `dist`，保留 `./src` 源码导出 |
| style.css | 新增 `src/style.css`（聚合 Element Plus / LogicFlow 样式 + 容器兜底） |

**宿主接入示例（鉴权透传）：**

```js
import { configureGraphApi } from '@acelance/graph-dsl-ui'
import '@acelance/graph-dsl-ui/style'

configureGraphApi({
  instance: hostAxios,                 // 复用宿主 axios（含拦截器/鉴权）
  apiPrefix: '/platform/graph-dsl',    // 对齐后端 ace.graph.dsl.web.base-path
  headers: { 'X-Tenant-Id': tenantId }
})
```

### v1.2 增量（后端）

| 项 | 关键变更 |
|----|----------|
| 审计日志 | 新增 `io.acelance.graph.dsl.audit` 包：`GraphAuditLogger` SPI + `GraphAuditEvent` + `GraphAuditActions` + 默认 `Slf4jGraphAuditLogger`（logger 名 `ace.graph.dsl.audit`）；`GraphRuntime` 发布/回滚、`ScriptNodeService` 增改删接入 |
| 请求级权限缓存 | `CachingGraphMenuAccessControl`（`@RequestScope` + `@Primary`）；开关 `ace.graph.dsl.access-control.cache-enabled`（默认 true） |
| CORS | `ace.graph.dsl.web.cors.*`（默认关闭），仅作用于设计器 base-path，`allowedOriginPatterns` 支持带凭证场景 |
| 脚本引擎线程池 | `AviatorScriptEngine` 改用共享守护线程池（保留超时语义）+ `AutoCloseable` 优雅关闭；`ace.graph.dsl.script.execution-pool-size` |

**新增配置示例（v1.2）：**

```yaml
ace:
  graph:
    dsl:
      access-control:
        cache-enabled: true          # 请求级菜单权限缓存
      script:
        execution-pool-size: 0       # <=0 按 CPU 核数（下限 2）
      web:
        cors:
          enabled: false             # 默认关闭，按需开启
          allowed-origins: ["https://host.example.com"]
          allow-credentials: true
```

**自定义审计落库示例：**

```java
@Component
public class JdbcGraphAuditLogger implements GraphAuditLogger {
    @Override public void record(GraphAuditEvent e) {
        // INSERT INTO graph_audit(action, resource_type, resource_id, version, operator, success, detail, ts) ...
    }
}
```

### v1.3 增量（后端）

| 项 | 关键变更 |
|----|----------|
| Checkpoint SPI | 新增 `io.acelance.graph.dsl.checkpoint` 包：`CheckpointSaverProvider` SPI + `CheckpointSaverRegistry`（按 `compile.saver` 类型解析，未注册时按配置告警回退/抛错）+ 内置 `MemoryCheckpointSaverProvider`；`DynamicGraphBuilder` 由硬编码 memory 改为注册表解析 |
| Redis 内置 saver | starter `RedisCheckpointSaverProvider`（`RedisSaver.builder().redisson(...)`），`@ConditionalOnClass(RedissonClient)` + `@ConditionalOnBean` + `ace.graph.dsl.checkpoint.redis-enabled`；redisson 作为 starter 的 `optional` 依赖，不传递给消费端 |
| 配置 | `ace.graph.dsl.checkpoint.fallback-to-memory`（默认 true）、`ace.graph.dsl.checkpoint.redis-enabled`（默认 true） |

**checkpoint 配置示例：**

```yaml
ace:
  graph:
    dsl:
      checkpoint:
        fallback-to-memory: true   # 未注册类型时告警回退（false 则抛错）
        redis-enabled: true        # 需 classpath 有 Redisson 且容器中有 RedissonClient
```

DSL 中按图选择 saver：`compile.saver = memory | redis | <宿主注册的类型>`。

**自定义（如 JDBC）checkpoint saver 扩展示例：**

```java
@Component
public class JdbcCheckpointSaverProvider implements CheckpointSaverProvider {
    private final DataSource dataSource;
    public JdbcCheckpointSaverProvider(DataSource dataSource) { this.dataSource = dataSource; }
    @Override public String type() { return "jdbc"; }
    @Override public BaseCheckpointSaver create() {
        // 复用库内置 PostgresSaver/MysqlSaver builder，或自定义实现
        return MysqlSaver.builder()./* ...dataSource/连接... */build();
    }
}
```

### v1.4 增量（通用图执行 / SSE 端点）

| 项 | 关键变更 |
|----|----------|
| 执行事件 SPI | core 新增 `io.acelance.graph.dsl.execution` 包：`GraphExecutionEventAdapter` SPI + `DefaultGraphExecutionEventAdapter`（普通节点 / LLM chunk / HITL 中断三类负载） |
| 通用端点 | starter `GraphExecutionController`（web 包）：`POST {base}/execution/{graphId}/invoke`（同步返回最终状态）、`/stream`（SSE）、`/resume`（HITL 续跑，SSE）；SSE 经 `SseEmitter` 订阅 `Flux<NodeOutput>` 推送 |
| 开关与注册 | `@ConditionalOnProperty(ace.graph.dsl.web.execution.enabled, 默认 false)`；自动注册 `DefaultGraphExecutionEventAdapter`（`@ConditionalOnMissingBean`）；ObjectMapper 用 `@Qualifier(OBJECT_MAPPER)` 隔离 |

**执行端点配置示例：**

```yaml
ace:
  graph:
    dsl:
      web:
        enabled: true
        execution:
          enabled: true   # 默认 false：开启后暴露通用 invoke/stream/resume
```

**调用示例：**

```bash
# 同步执行
curl -XPOST {base}/execution/cs-reply-m2/invoke \
  -H 'Content-Type: application/json' \
  -d '{"inputs":{"query":"退货"},"threadId":"t-1"}'

# 流式（SSE）
curl -N -XPOST {base}/execution/cs-reply-m2/stream \
  -H 'Content-Type: application/json' -d '{"inputs":{"query":"退货"}}'

# HITL 续跑（写回反馈后从断点继续）
curl -N -XPOST {base}/execution/cs-reply-m2/resume \
  -H 'Content-Type: application/json' -d '{"threadId":"t-1","updates":{"feed_back":true}}'
```

宿主可通过实现 `GraphExecutionEventAdapter` 自定义事件结构 / 字段脱敏；复杂业务仍可自写 Controller。

### v1.5 增量（脚本条件边 Dispatcher + 可观测 + 工程化）

#### 1）脚本条件边 Dispatcher（7.3）

| 项 | 关键变更 |
|----|----------|
| 模型 | `GraphEdge` 新增 `condition`（脚本路由表达式）+ `conditionEngine`（默认 aviator）；新增 `isScriptRouting()` / `resolvedConditionEngine()` |
| 编译 | core `ScriptEdgeActionFactory`（`@Component`）将表达式编译为 graph-core `EdgeAction`（apply 返回 mapping 的 key）；`DynamicGraphBuilder.applyEdge` 脚本路由分支 |
| 校验 | `GraphValidator` 区分脚本路由 / Java dispatcher：脚本路由校验引擎可用性 + 表达式语法（跳过 possibleTargets 覆盖性，运行时才定）；两者并存时 dispatcher 优先 |

**脚本条件边 DSL 示例：**

```json
{
  "from": "intake",
  "type": "conditional",
  "condition": "state.score > 60 ? 'pass' : 'reject'",
  "conditionEngine": "aviator",
  "mapping": { "pass": "approve_node", "reject": "reject_node" }
}
```

> 表达式以图状态为白名单上下文（变量 `state`），返回值（转字符串）即 `mapping` 的 key。仍可用 `dispatcher` 走 Java 路由。

#### 2）执行可观测 trace（6.1）

| 项 | 关键变更 |
|----|----------|
| SPI | core `io.acelance.graph.dsl.observability`：`GraphExecutionListener`（onStart/before/after/onError/onComplete，默认空实现）；`GraphLifecycleListenerBridge` 桥接到 graph-core `GraphLifecycleListener` |
| 接入 | `DynamicGraphBuilder` 注入 `List<GraphExecutionListener>`，非空时 `CompileConfig.withLifecycleListener` 注册桥；单监听器异常不影响图执行 |
| 默认实现 | `Slf4jGraphExecutionListener`（节点耗时日志，logger `ace.graph.dsl.trace`），`@ConditionalOnProperty(ace.graph.dsl.observability.enabled, 默认 false)` |

**接入 Langfuse / OTel（宿主侧示例）：**

```java
@Component
public class OtelGraphExecutionListener implements GraphExecutionListener {
    @Override public void before(String nodeId, Map<String,Object> state, RunnableConfig cfg, Long t) { /* start span */ }
    @Override public void after(String nodeId, Map<String,Object> state, RunnableConfig cfg, Long t) { /* end span */ }
    @Override public void onError(String nodeId, Map<String,Object> state, Throwable e, RunnableConfig cfg) { /* record */ }
}
```

#### 3）工程化（P3 测试；CI 计划未入库）

| 项 | 关键变更 |
|----|----------|
| 单元测试 | core 新增 `GraphValidatorTest`、`ScriptEdgeActionFactoryTest`，修正既有 `AviatorScriptEngineTest`；core pom 用 surefire 3.2.5 + `skipTests=false` |
| CI（计划） | `.github/workflows/ace-graph-dsl-ci.yml`——backend `mvn -B verify`（JDK 17）+ frontend `npm ci && npm run build`（Node 20）；**截至 v1.6 尚未入库** |

### 待办项（需大改 / 纯前端收尾 / 宿主决策）

| 项 | 状态 | 说明 |
|----|------|------|
| 6.2 多租户隔离 | ⏳ | 架构级跨切面改造，需独立设计评审 |
| 3.2 npm 私服 / `.d.ts` | 🔶 | 构建 OK；publish 与类型声明待办 |
| 7.2 多脚本引擎 | ⏳ | 需沙箱依赖与安全评估；SPI 可扩展 |
| P3 SemVer 发布（私服） | 🔶 | 版本 1.0.0 已定版；`CHANGELOG.md` 已创建；正式 `mvn deploy` / `npm publish` 待运维 |
| P3 集成测试 | 🔶 | `DynamicGraphBuilder` / `ScriptNodeService` / 权限待补 |

---

## 12. 相关文档

| 文档 | 说明 |
|------|------|
| [LIBRARY_EMBEDDING_ROADMAP.md](./LIBRARY_EMBEDDING_ROADMAP.md) | 嵌入痛点与里程碑（与本文互补） |
| [MENU_PERMISSION_INTEGRATION.md](./MENU_PERMISSION_INTEGRATION.md) | 菜单权限 SPI 与接入示例 |
| [SECURITY_INTEGRATION.md](./SECURITY_INTEGRATION.md) | Spring Security 集成指南 |
| [NODE_FLEXIBILITY_EXPLORATION.md](./NODE_FLEXIBILITY_EXPLORATION.md) | 脚本节点与 Dispatcher 扩展设计 |
| [MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md) | 多脚本引擎价值与改进方案（§7.2） |
| [SCRIPT_NODE_EXAMPLES.md](./SCRIPT_NODE_EXAMPLES.md) | 脚本节点使用样例 |
| [PROJECT_OVERVIEW.md](./PROJECT_OVERVIEW.md) | 架构总览 |
| [REMAINING_ITEMS_PLAN.md](./REMAINING_ITEMS_PLAN.md) | 剩余规划项说明与落地方案 |
| [PRODUCTION_DEPLOYMENT_ISSUES.md](./PRODUCTION_DEPLOYMENT_ISSUES.md) | 生产部署问题分析与解决方案（四类问题） |
