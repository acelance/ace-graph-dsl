# ACE Graph DSL：`web-webflux` 轻量模块方案与开发计划

| 项 | 内容 |
|---|---|
| 状态 | **已发布 mesrelease 1.1.20**（待业务联调冒烟） |
| 日期 | 2026-10-20 |
| 定位 | 相对「Servlet/WebFlux 双模块 + Facade 大方案」的 **轻量替代** |
| 大方案文档 | [`ACE-Graph-DSL-Web-Servlet-WebFlux双模块方案与开发计划.md`](./ACE-Graph-DSL-Web-Servlet-WebFlux双模块方案与开发计划.md)（已搁置） |
| 动机 | 业务侧 exclusion Servlet 后手写 WebFlux 镜像；产品提供同等模块，业务只改依赖 |

---

## 1. 结论先看

| 问题 | 结论 |
|------|------|
| 实现难度是否比双模块大方案低很多？ | **是**。大约降到大方案的 **40%～55%** 量级 |
| 推荐工作量 | **约 6～10 人日**（大方案 14～20） |
| 是否「WebFlux 里硬塞 Servlet」？ | **否**。仍是 exclusion + **WebFlux 重写 Controller** |
| `web.enabled` 含义 | 见 **§2.4**（默认 true = 开产品设计器 HTTP，并按宿主类型选 MVC 或 web-webflux） |
| 与现状关系 | 把 `agent-server` 里已验证的 designer 镜像 **迁回产品仓**，业务删类、改依赖 |

---

## 2. 方案概述

### 2.1 现状（业务侧）— 切换前

```text
lesso-ai-platform-agent-server
  ├─ 依赖 ace-graph-dsl-spring-boot-starter
  │     exclusions: spring-boot-starter-web / tomcat
  ├─ ace.graph.dsl.web.enabled=false   （不加载产品 Servlet Controller）
  └─ 手写 WebFlux：AceGraphDesigner*Controller …
```

### 2.2 目标形态（已实现）

```text
新产品模块：ace-graph-dsl-web-webflux（1.1.20）
  ├─ 依赖 ace-graph-dsl-spring-boot-starter
  │     exclusions: spring-boot-starter-web / tomcat（封装进本模块 POM）
  ├─ 依赖 spring-boot-starter-webflux
  ├─ 内含设计器 WebFlux Controllers（io.acelance.graph.dsl.web.webflux）
  └─ AutoConfiguration：仅 REACTIVE + ace.graph.dsl.web.enabled=true 时注册

业务侧（已切换）：
  ├─ 依赖 ace-graph-dsl-web-webflux（主入口）+ 显式 ai/saa/agentscope
  ├─ 删除 designer 包镜像 Controller（保留 AceGraphExecutionAliasController）
  ├─ 删除 AceGraphAgentResourceCatalogController
  └─ 去掉显式 web.enabled=false（恢复默认 true）
```

### 2.3 相对大方案少做什么

| 大方案有、本方案不做（首期） | 说明 |
|------------------------------|------|
| 拆出 `ace-graph-dsl-web-servlet` | Servlet Controller **留在**现有 starter |
| 共用 Facade / web-support | 不抽；允许产品内 Servlet 与 WebFlux **两套 Controller 并存** |
| 削 starter 对 `starter-web` 的硬依赖 | 继续靠 exclusion（与现业务一致） |
| 完整 WebFlux `/execution` | 设计器试跑走现有路径；执行主路径仍业务 `/api/ace-graph` |
| 权限 RequestScope 的精致 WebFlux 移植 | 首期见 **§2.5**：Guard 偏弱（与现业务镜像一致）；不做 RequestScope 级移植 |

**代价：** 产品内仍有双份设计器 Controller（Servlet 一份 + WebFlux 一份），新增 API 要改两处——但比「每个宿主各镜像一份」好；比 Facade 大方案维护成本略高、落地快。

### 2.4 配置项 `ace.graph.dsl.web.enabled`（必读，避免歧义）

本开关**只回答一件事**：要不要由**产品**注册「设计器 REST Controller」。  
它**不**回答「本进程是 Servlet 还是 WebFlux」——那由 Spring Boot 的 `WebApplicationType` 决定。  
落地后也**不会**出现「`true` = 强制开 Servlet」这种语义。

#### 一句话定义（落地后）

| 取值 | 含义 |
|------|------|
| **`true`（默认）** | **启用**产品设计器 HTTP：按当前宿主类型，注册**匹配该类型**的那一套产品 Controller。 |
| **`false`** | **关闭**产品设计器 HTTP：Servlet 套与 WebFlux 套**都不**注册。 |

「匹配该类型」的分流（两套互斥，不会同时注册）：

| 宿主进程类型 | `enabled=true` 时实际注册谁 | `enabled=true` 时**不会**注册谁 |
|--------------|-----------------------------|----------------------------------|
| **Servlet**（MVC） | starter 内现有 MVC Controllers（`AceGraphDslWebConfiguration` + `@ConditionalOnWebApplication(SERVLET)`） | `ace-graph-dsl-web-webflux` 内 Controllers |
| **WebFlux**（Reactive） | `ace-graph-dsl-web-webflux` 内 Controllers（`@ConditionalOnWebApplication(REACTIVE)`） | starter 内 MVC Controllers |

完整交叉表：

| | 宿主 = Servlet | 宿主 = WebFlux（如 agent-server） |
|--|----------------|-----------------------------------|
| **`enabled=true`（默认）** | 产品 **MVC** 设计器 API **开** | 产品 **WebFlux** 设计器 API **开** |
| | WebFlux 设计器 API **关** | MVC 设计器 API **关**（条件不满足，不是「硬塞 Servlet」） |
| **`enabled=false`** | 产品两侧设计器 API **全关** | 产品两侧设计器 API **全关** |

#### 和「今天业务侧配置」的对比（为何以前要写 false）

| | **今天**（无 web-webflux 模块） | **本方案落地后** |
|--|----------------------------------|------------------|
| 产品能提供的设计器 Web | **仅有 Servlet/MVC 一套** | Servlet 一套 + WebFlux 一套 |
| WebFlux 宿主若 `enabled=true` | 也**挂不上**有用的产品设计器 API（MVC 条件不满足 / 已 exclusion servlet） | **会**挂上产品 WebFlux 设计器 API |
| 业务常见写法 | 显式 `enabled=false`，再**手写** `AceGraphDesigner*` 镜像 | **不必再写 false**；依赖默认 `true`，删掉镜像类 |
| 业务何时仍写 `false` | — | 仅当宿主**故意不暴露**设计器 API 时 |

#### 实现时条件怎么写（给开发）

```text
MVC 自动配置（已有）:
  @ConditionalOnProperty(ace.graph.dsl.web.enabled=true)   // matchIfMissing=true → 默认 true
  @ConditionalOnWebApplication(SERVLET)

WebFlux 自动配置（新模块）:
  @ConditionalOnProperty(ace.graph.dsl.web.enabled=true)   // 同一开关、同一默认
  @ConditionalOnWebApplication(REACTIVE)
```

业务切换清单里写「改为 true」容易误解成改语义；准确说法是：

> **删掉显式 `ace.graph.dsl.web.enabled=false`，恢复默认 `true`（或显式写 true 与默认等价）。**  
> 效果：WebFlux 进程注册产品 `web-webflux` Controllers，而不是注册 MVC Controllers。

### 2.5 菜单权限 Guard「偏弱」是什么意思（必读）

这里的 Guard ≠ 登录鉴权（网关/Token），而是设计器 **菜单权限 key**（如 `graph:delete`、`graph:save`）在**写接口上的服务端二次校验**。

产品有两层：

| 层 | 谁做 | 作用 |
|----|------|------|
| **前端显隐** | UI 调 `GET .../permissions/menus`，按 `grantedKeys` 藏按钮 | 没权限的人**看不见**删除/保存等 |
| **服务端 Guard** | Controller 入口 `MenuPermissionGuard.require(resolver, "graph:delete", ...)` | 即使绕过前端直接调 API，无权限也 **403** |

#### 现状对比

| | 产品 Servlet Controllers | 业务 WebFlux 镜像（`AceGraphDesigner*` 等） / 现 web-webflux |
|--|--------------------------|-----------------------------------------------|
| 是否调用 `MenuPermissionGuard` | **是**（保存/发布/删除/脚本…多处） | **否**（首期偏弱） |
| 无权限用户直接调 API | 通常被拒 | **往往仍能调通**（只要网关放行） |
| 前端按钮显隐 | 有（menus 接口仍可用） | 有（同样靠 menus） |

文档说「首期跟现业务镜像一样**偏弱**」=：

> 迁入 `web-webflux` 的 Controllers **首期可以不接** `MenuPermissionGuard`（或等价 `resolver.isGranted`），行为与今天 agent-server 镜像一致：  
> **只靠前端藏按钮 + 网关登录**，服务端不对菜单 key 做强制拦截。

#### 为什么首期故意偏弱

1. 与现网业务行为一致，迁移风险小（不会突然多出一堆 403）。  
2. 产品 `MenuPermissionGuard` 是 package-private，且 Servlet 侧还绑了 `@RequestScope` 缓存；WebFlux 原样搬成本高（大方案才细做）。  
3. 当前默认 `PermissiveGraphMenuAccessControl` 时，即使加 Guard 也几乎全放行；真正接外部权限体系时再加强即可。

#### 首期 / 后续

| 阶段 | Guard |
|------|--------|
| **首期（本轻量方案）** | **偏弱（已定）**：**不做**服务端菜单 Guard；**必须保留** `permissions/menus` 供前端显隐；避免迁移后行为变严导致「反而用不了」 |
| **后续（可选）** | 对齐 Servlet：写操作加 `require(graph:xxx)`；WebFlux 用非 RequestScope 的权限解析 |

**不要理解成：** 不要登录、不要 `permissions/menus`、或删掉前端权限控制。

---

## 3. 难度与工作量对比

### 3.1 为何更低

| 维度 | 大方案 | 本轻量方案 |
|------|--------|------------|
| 模块搬迁 | Servlet 整包迁出 + 改 starter 装配 | **不迁** Servlet |
| 代码来源 | 从零对齐两套 + 抽 Facade | **业务镜像已存在**，主要是搬家 + 改包名/自动配置 |
| 技术坑 | RequestScope、削依赖、路径前缀重构… | 与现镜像同级；坑已踩过大半 |
| 业务切换 | 依赖 + 删类 + 行为 diff | 同左，但产品侧准备量少 |
| 预估人日 | 14～20 | **6～10** |

### 3.2 分项估算（人日）

| 工作包 | 内容 | 人日 |
|--------|------|------|
| P0 立项冻结 | 迁入清单、不迁清单、`web.enabled` 语义、版本号 | 0.5 |
| P1 模块骨架 | 新建 `ace-graph-dsl-web-webflux` POM、exclusion、AutoConfiguration、spring.factories/imports | 1 |
| P2 迁入 Controllers | 从 `agent-server/.../designer` 迁入并对齐产品包名；Advice | 2～3 |
| P3 契约冒烟 | 列表/保存/发布/回收箱/脚本节点；与迁前对比 | 1～1.5 |
| P4 业务切换 | agent-server 改依赖、删镜像类、配置、联调 | 1～1.5 |
| P5 文档与发布 | CHANGELOG、宿主接入说明、deploy mesrelease | 0.5～1 |
| **合计** | | **约 6～10** |

单人约 **1.5～2 周日历**（含联调缓冲）。

---

## 4. 业务侧受影响接口

约定：下列路径均相对于应用 context-path（如 `/platform-agent`）之后；设计器宿主经 Vite 代理到上游。

### 4.1 已迁入产品模块 → 业务删除镜像后由产品提供（契约不变）

| 业务原实现（已删） | 前缀 / 方法 | 产品类（`io.acelance.graph.dsl.web.webflux`） |
|------------|-------------|------|
| `AceGraphDesignerDefinitionController` | `GET/POST /api/graph/definitions/**` | `GraphDefinitionController` |
| `AceGraphDesignerRecycleController` | `.../definitions/**` recycle 相关 | `GraphRecycleController` |
| `AceGraphDesignerCatalogController` | `GET /api/graph/catalog/graph-ids`、`/summaries` | `GraphCatalogController` |
| `AceGraphDesignerPermissionController` | `GET /api/graph/permissions/menus` | `GraphPermissionController` |
| `AceGraphDesignerScriptNodeController` | `/api/graph/nodes/**` | `ScriptNodeController` |
| `AceGraphDesignerAgentController` | `/api/graph/agents/**` | `GenericAgentNodeController` |
| `AceGraphDesignerNodeRegistryController` | `GET /api/graph/nodes`、`/dispatchers` | `NodeRegistryController` |
| `AceGraphSaaCapabilityController` | `GET /api/graph/capabilities/saa` | `SaaCapabilityController` |
| `AceGraphStreamResponseKindController` | `GET /api/stream-response-kinds` | `StreamResponseKindController` |
| `AceGraphNodeBizParamController` | `GET /api/node-biz-param-interpreters` | `NodeBizParamController` |
| `AceGraphAgentResourceCatalogController` | `GET /api/agent-resources/{prompts\|models\|tools\|mcp\|skills}` | `AgentResourceCatalogController`（含 `otherBizParams`） |
| `AceGraphDesignerExceptionAdvice` | designer 包异常映射 | `DesignerWebFluxExceptionAdvice` |

**对前端 / 调用方：** URL 与 JSON **不应变化**；影响的是「谁提供实现」（业务类 → 产品模块）。

### 4.2 默认不迁、业务继续自有（不受本方案强制影响）

| 业务类 | 路径 | 原因 |
|--------|------|------|
| `AceGraphExecutionController` | `/api/ace-graph/**` stream/invoke/state/chat | 平台保留键、Vertical 形态、观测；非纯设计器契约 |
| `AceGraphExecutionAliasController` | `/execution/**` | 设计器「调试流式」别名；**已定不迁** |
| 配套 Mapper / SSE Adapter / Request DTO | — | 跟执行入口走 |

### 4.3 灰色项（均已定）

| 项 | 路径 | 结论 |
|----|------|------|
| `AceGraphExecutionAliasController` | `/execution/**` | **不迁**；暂留业务 |
| `AceGraphAgentResourceCatalogController` | `/api/agent-resources/**` | **迁入**；含 `otherBizParams`（optional） |

---

## 5. 决策收口（全部已定）

| # | 议题 | 结论 |
|---|------|------|
| 1 | 真 UI 定案 | **产品 `web-webflux` + 业务删除设计器镜像**；`/execution` 与 `/api/ace-graph` 仍留业务 |
| 2 | `ace.graph.dsl.web.enabled` | **默认 `true`**；语义见 §2.4；业务去掉显式 `false` |
| 3 | AgentResources | **迁入**，保留 `otherBizParams`（optional） |
| 4 | `/execution` 别名 | **不迁** |
| 5 | 菜单权限 Guard | **首期偏弱**：不接 `MenuPermissionGuard`；保留 menus |
| 6 | 版本号 | **`1.1.20`** |
| 7 | 业务 POM | **A/B 均支持**；推荐 A（`web-webflux` 主入口）；agent-server 已按 A |
| 8 | 与大方案关系 | 本方案独立交付；大方案继续搁置 |

---

## 6. 模块与自动配置要点（实现约束）

```text
ace-graph-dsl-web-webflux/pom.xml
  - parent: ace-graph-dsl-backend
  - depends: ace-graph-dsl-spring-boot-starter
      exclusions: spring-boot-starter-web, spring-boot-starter-tomcat
  - depends: spring-boot-starter-webflux
  - AutoConfiguration.imports → AceGraphDslWebFluxAutoConfiguration

AutoConfiguration（语义与条件以 §2.4 为准）
  - @ConditionalOnProperty(ace.graph.dsl.web.enabled=true)  // matchIfMissing=true，默认 true
  - @ConditionalOnWebApplication(REACTIVE)
  - @ComponentScan 本模块 webflux controller 包
```

注意：

1. **没有「硬塞 Servlet」**：WebFlux 进程上 MVC 的 `AceGraphDslWebConfiguration` 因 `SERVLET` 条件不满足而**不会**生效；`enabled=true` 只打开本模块的 WebFlux Controllers。详见 §2.4。  
2. 路径保持与现业务镜像一致（含绝对 `/api/...`）。  
3. 包名：`io.acelance.graph.dsl.web.webflux`，与 Servlet 的 `io.acelance.graph.dsl.web` 并列。

---

## 7. 开发计划（进度）

### M0 — 冻结（0.5d）

- [x] 确认 §4 迁入清单（AgentResources / `/execution` 别名 **均已定**）  
- [x] 版本号 **已定 `1.1.20`**；`web.enabled` 以 §2.4 为准（已定默认 true）  
- [x] 更新本文件「决策记录」小节  

### M1 — 模块骨架（1d）

- [x] 父 POM 增加 module `ace-graph-dsl-web-webflux`  
- [x] exclusion + AutoConfiguration 空壳可启动  
- [x] 本地 `mvn install`（`-Dmaven.test.skip=true`）  

### M2 — 迁入实现（2～3d）

- [x] ExceptionAdvice、Permission、Catalog  
- [x] Definition + Recycle  
- [x] ScriptNode、GenericAgent、NodeRegistry  
- [x] SaaCapability、StreamResponseKind、NodeBizParam  
- [x] AgentResources（含 `otherBizParams`；无 Guard）  
- [x] 注册日志统一带 `[ace-graph-dsl-web-webflux]`  

### M3 — 产品发布（0.5～1d）

- [ ] 单测或最小 WebTestClient（可选）  
- [x] CHANGELOG（`1.1.20`）  
- [x] `mvn deploy` → mesrelease（`1.1.20` 全模块含 `web-webflux`）  

### M4 — 业务切换（1～1.5d）

- [x] `pom.xml`：主依赖 `ace-graph-dsl-web-webflux`；去掉 starter 重复 exclusion  
- [x] 配置：删除显式 `ace.graph.dsl.web.enabled=false`（application.yml / local / nacos-sample）  
- [x] 删除 `.../acegraph/web/designer/*` 镜像及 `AceGraphAgentResourceCatalogController`（保留 `AceGraphExecutionAliasController`）  
- [ ] 设计器冒烟：保存、发布、**删除进回收箱**、权限 menus 含 `graph:delete`（待联调）  

### M5 — 收尾（0.5d）

- [x] 宿主接入说明（见 **§11**）  
- [x] 大方案文档加一行：轻量路径优先；大方案继续搁置  

---

## 8. 风险（相对大方案更少，但仍有）

| 风险 | 对策 |
|------|------|
| 迁入时改包名漏改路径 | 对照 §4 表 + UI 冒烟清单 |
| `web.enabled=true` 误文档写成会开 Servlet | 写明仅 REACTIVE 扫 webflux 包；SERVLET 条件照旧 |
| AgentResources 与产品参数不一致 | 已定对齐产品：含 `otherBizParams`；实现时对照 `AgentResourceCatalogController` |
| 双份 Controller 长期分叉 | 接受首期；CHANGELOG 约定「设计器 API 变更须同时改 web + webflux」；将来再上 Facade |
| Nacos 线上仍写 `web.enabled=false` | 发布后检查 Nacos 配置，删掉或改为 true，否则产品 Controllers 不注册 |

---

## 9. 验收标准

1. 新产品模块可独立 deploy；WebFlux 宿主只加该依赖即可暴露设计器 API。  
2. `agent-server` 无 `AceGraphDesigner*` 镜像类（Execution 除外）。  
3. 设计器 UI：列表 / 保存 / 发布 / 删除回收箱 **非 404**，行为与切换前一致。  
4. 进程中 **无** Servlet 设计器 Controller 注册；无 Tomcat 被迫启动。  
5. 文档含：业务接入前后 POM/配置对比（§11）。

---

## 10. 决策记录

| 日期 | 议题 | 结论 | 人 |
|------|------|------|-----|
| 2026-10-20 | AgentResources 迁入？ | **迁入** `web-webflux`；含 **`otherBizParams`（optional）**；业务删除 `AceGraphAgentResourceCatalogController` | 用户确认 |
| 2026-10-20 | `web.enabled` 默认与语义 | **默认 `true`**。完整定义见 **§2.4** | 用户确认 |
| 2026-10-20 | 版本号 | **先发 `1.1.20`** | 用户确认 |
| 2026-10-20 | 菜单权限 Guard | **首期偏弱**（§2.5） | 用户确认 |
| 2026-10-20 | `/execution` 别名 | **不迁** | 用户确认 |
| 2026-10-20 | 真 UI 定案调整 | **产品 web-webflux + 业务删设计器镜像** | 用户确认 |
| 2026-10-20 | 业务 POM 写法 | **A/B 均支持**；推荐 A | 用户确认 |
| 2026-10-20 | 是否排期开工 | **开工并完成代码落实**（M0～M2、M4 代码侧；M3 deploy / M4 冒烟待做） | 用户确认 |

---

## 11. 宿主接入说明（WebFlux）

### 推荐写法（方案 A）

```xml
<dependency>
  <groupId>io.acelance</groupId>
  <artifactId>ace-graph-dsl-web-webflux</artifactId>
  <version>1.1.20</version>
</dependency>
<!-- 按需显式：ai / saa-agent / agentscope-agent -->
```

不必再手写 exclusion Servlet；不必再配 `ace.graph.dsl.web.enabled=false`。

### 配置

- **不要**写 `ace.graph.dsl.web.enabled=false`（除非故意关闭设计器 API）。  
- 默认 `true` 时，WebFlux 进程只注册本模块 Controllers。  
- 若 Nacos 仍残留 `web.enabled=false`，发布后务必改掉。

### 业务侧应删除 / 保留

| 删除 | 保留 |
|------|------|
| `AceGraphDesigner*`、`AceGraphSaaCapability*`、`AceGraphStream*`、`AceGraphNodeBizParam*`、`AceGraphAgentResourceCatalogController`、ExceptionAdvice | `AceGraphExecutionController`、`AceGraphExecutionAliasController`、Mapper/SSE Adapter |

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-20 | 初稿：轻量 web-webflux 相对大方案的评估、接口影响面、未决项、开发计划 |
| 2026-10-20 | 决策：AgentResources **迁入**，保留 `otherBizParams`（optional），与产品侧一致 |
| 2026-10-20 | 决策：`web.enabled` **默认 true**；正文增补 **§2.4** |
| 2026-10-20 | 决策：版本号先发 **`1.1.20`** |
| 2026-10-20 | 正文增补 **§2.5**：Guard「偏弱」说明 |
| 2026-10-20 | 用户确认：Guard 首期不接；`/execution` 暂留业务；POM A/B；真 UI = 本方案 |
| 2026-10-20 | **代码落实**：新建模块、迁入 Controllers、升版 1.1.20、agent-server 切依赖并删镜像；文档勾选 M0～M2/M4 代码项；增 §11 接入说明 |
