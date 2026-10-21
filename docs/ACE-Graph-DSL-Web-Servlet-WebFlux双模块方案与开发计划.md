# ACE Graph DSL：Web Servlet / WebFlux 双模块方案与开发计划

| 项 | 内容 |
|---|---|
| 状态 | **搁置**（完整双模块 + Facade） |
| 日期 | 2026-10-20 |
| 范围 | 产品 `ace-graph-dsl` 设计器 REST 适配层；业务 `lesso-ai-platform-agent-server` 镜像下线路径 |
| 关联 | 真 UI 定案（`ace.graph.dsl.web.enabled=false` + 业务 WebFlux 镜像）；回收箱 `GraphRecycleController` / `AceGraphDesignerRecycleController` |
| 轻量替代 | **优先走轻量路径**（已开工 `1.1.20`）：见 [`ACE-Graph-DSL-web-webflux轻量模块方案与开发计划.md`](./ACE-Graph-DSL-web-webflux轻量模块方案与开发计划.md)；大方案继续搁置 |

---

## 1. 背景与问题

### 1.1 现状

- 产品设计器 API 集中在 `ace-graph-dsl-spring-boot-starter` 的 `io.acelance.graph.dsl.web.*`，经 `AceGraphDslWebConfiguration` 在 **Servlet** 应用中自动注册：
  - `@ConditionalOnWebApplication(SERVLET)`
  - `@ConditionalOnProperty(ace.graph.dsl.web.enabled=true)`（默认 true）
- 业务宿主 `lesso-ai-platform-agent-server` 为 **WebFlux**，配置 `ace.graph.dsl.web.enabled=false`，产品 Controller **整包不加载**。
- 业务仓用手写 **WebFlux 镜像** 对齐同一契约（路径 / 入参 / 错误体），设计器才能联调。每新增产品 API（如回收箱），业务必须再镜像一次，否则前端出现 **404**（已发生：`POST .../definitions/{id}/recycle`）。

### 1.2 问题本质

| 层 | 是否缺失 |
|----|----------|
| 领域能力（repository / runtime / recycle） | **否**，已在 core / persistence |
| Servlet 设计器 HTTP | **否**，产品已有 |
| WebFlux 设计器 HTTP | **是**，产品未提供 → 业务被迫重复 Controller |

重复的不是业务逻辑，而是 **HTTP 适配壳**。长期应在产品侧按 Web 栈拆模块，宿主按栈选依赖。

### 1.3 目标 / 非目标

**目标**

1. 产品提供 **Servlet / WebFlux 二选一** 的设计器 Web 模块，契约与现有 UI（`ace-graph-dsl-ui` / `graph.js`）一致。
2. WebFlux 宿主引入产品模块后，**删除**业务侧 `AceGraphDesigner*` 镜像类。
3. 新增设计器 API 时，优先落在共享 Facade + 两侧薄 Controller，避免「只改 Servlet、忘了 WebFlux」。

**非目标（本方案不覆盖）**

- 业务专属执行入口（如 `/api/ace-graph/**`、Vertical 形态 chat/stream）——继续留在业务仓。
- 强制同一进程同时启用两套 Web（禁止，防路由冲突）。
- 前端协议变更；本方案以 **契约冻结、零 UI 改动** 为验收默认。

---

## 2. 盘点：产品 vs 业务镜像

### 2.1 产品 Servlet Controller（约 13 个 + Advice）

| 产品类 | 相对路径（再加 `ace.graph.dsl.web.base-path`，默认 `/api/graph`） | 约行数 | 复杂度 |
|--------|------------------------------------------------------------------|--------|--------|
| `GraphDefinitionController` | `/definitions/**` | ~180 | 中 |
| `GraphPublishController` | `/definitions/**` publish/rollback | ~40 | 低 |
| `GraphRecycleController` | `/definitions/**` recycle | ~200 | 中 |
| `GraphCatalogController` | `/catalog/**` | ~50 | 低 |
| `GraphPermissionController` | `/permissions/**` | ~30 | 低 |
| `ScriptNodeController` | `/nodes/**` | ~190 | 中 |
| `GenericAgentNodeController` | `/agents/**` | ~210 | 中 |
| `NodeRegistryController` | nodes/dispatchers 注册 | ~60 | 低 |
| `SaaCapabilityController` | capabilities/saa | ~65 | 低 |
| `StreamResponseKindController` | `/api/stream-response-kinds`（绝对路径） | ~40 | 低 |
| `NodeBizParamController` | `/api/node-biz-param-interpreters` | ~35 | 低 |
| `AgentResourceCatalogController` | `/api/agent-resources` | ~85 | 低～中 |
| `GraphExecutionController` | `/execution/**` | ~370 | **高**（SSE/断点） |
| `ApiExceptionAdvice` + `MenuPermissionGuard` | — | ~60 | 横切 |

说明：部分类使用绝对 `/api/...`，与 `base-path` 前缀策略不一致，迁移时需 **保持现网路径不变**（契约优先于「纯相对路径」洁癖）。

### 2.2 业务已镜像（designer 包）

| 业务类 | 对应产品 |
|--------|----------|
| `AceGraphDesignerDefinitionController` | Definition + 部分 Publish |
| `AceGraphDesignerRecycleController` | Recycle |
| `AceGraphDesignerCatalogController` | Catalog |
| `AceGraphDesignerPermissionController` | Permission |
| `AceGraphDesignerScriptNodeController` | ScriptNode |
| `AceGraphDesignerAgentController` | GenericAgentNode |
| `AceGraphDesignerNodeRegistryController` | NodeRegistry |
| `AceGraphSaaCapabilityController` | SaaCapability |
| `AceGraphStreamResponseKindController` | StreamResponseKind |
| `AceGraphNodeBizParamController` | NodeBizParam |
| `AceGraphAgentResourceCatalogController` | AgentResourceCatalog（包路径略不同） |
| `AceGraphExecutionAliasController` | 部分 `/execution` 别名（不完整） |
| `AceGraphDesignerExceptionAdvice` | ApiExceptionAdvice 子集 |

**业务保留、不纳入本双模块下线范围：**

- `AceGraphExecutionController`（`/api/ace-graph/**`）——平台保留键、Vertical 形态、观测绑定等业务语义。
- 其它非设计器契约的平台 API。

### 2.3 缺口结论

- 设计器只读/写主路径：业务已基本镜像，但 **与产品双份维护**，新功能（回收箱）已证明易漏。
- 产品 `GraphExecutionController`（`/execution/**`）与业务 `AceGraphExecutionAliasController` **未完全对齐**（如 resume 注明未实现）——双模块落地时可作为 **P2**，或明确「设计器用业务 `/api/ace-graph`，产品 `/execution` 仅 Servlet demo」。

---

## 3. 推荐架构

### 3.1 模块拆分

```
ace-graph-dsl-web-api          （可选，P1+）契约 DTO / 错误体常量 / 权限 key 文档；无 Spring Web 依赖
ace-graph-dsl-web-support      （推荐）共用薄门面 Facade：校验、权限 require、编排、异常语义
ace-graph-dsl-web-servlet      现有 MVC Controller + WebMvcConfigurer（从 starter 迁出）
ace-graph-dsl-web-webflux      同路径同契约的 WebFlux Controller + WebFlux Advice
ace-graph-dsl-spring-boot-starter  运行时 + 自动配置；Web 改为 optional / 按条件引入上述模块
```

最小可交付（优化工作量）可合并为 **两模块**：

| 模块 | 职责 |
|------|------|
| `ace-graph-dsl-web-servlet` | 现有 `io.acelance.graph.dsl.web` 迁入；含 MVC 配置 |
| `ace-graph-dsl-web-webflux` | 新建 WebFlux Controller；初期可先 **薄封装业务已验证的镜像逻辑回迁**，再抽 Facade |

`web-support`（Facade）可在 **第二期** 从重复代码中抽出，避免第一期过度设计阻塞上线。

### 3.2 自动装配规则

| 条件 | 行为 |
|------|------|
| `WebApplicationType.SERVLET` + `ace.graph.dsl.web.enabled=true` | 启用 `web-servlet` |
| `WebApplicationType.REACTIVE` + `ace.graph.dsl.web.enabled=true` | 启用 `web-webflux` |
| `enabled=false` | 两边都不注册（兼容当前业务开关语义；迁移后业务改为 `true` + 依赖 webflux 模块） |
| 禁止 | 同一应用同时注册 servlet + webflux 设计器路由 |

配置键建议保持兼容：

- `ace.graph.dsl.web.enabled`
- `ace.graph.dsl.web.base-path`（默认 `/api/graph`）
- CORS 等现有属性跟 servlet 配置一并迁走；webflux 侧用 `WebFluxConfigurer` / `CorsWebFilter` 对齐。

### 3.3 共用薄门面（可选，强烈建议 P1.5）

Controller 只做：

1. 解析 path / query / body  
2. 调 Facade  
3. 映射 HTTP 状态与响应体  

Facade 示例边界（非穷尽）：

- `GraphDefinitionFacade`：list / load / saveDraft / validate / preview / dryRun  
- `GraphRecycleFacade`：referrers / moveToRecycle / list / restore / purge  
- `ScriptNodeFacade` / `GenericAgentNodeFacade`  
- `MenuPermissionSupport.require(key)`（替代 package-private `MenuPermissionGuard`，两模块可共用）  

**WebFlux 注意：**

- 禁止把 Servlet `@RequestScope` 权限缓存原样搬到 WebFlux；用 `CachingGraphMenuAccessControl` 的等价物：过滤器写入 Reactor Context，或请求级缓存策略另定。
- dry-run / 阻塞调用：Facade 内可同步；Controller 层用 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 包装，避免阻塞 event loop。
- SSE：优先复用产品已有 `GraphStreamBridge` 等；与业务 `/api/ace-graph` 流式协议解耦。

### 3.4 依赖关系（示意）

```
core / persistence / ai
        ↑
  web-support (optional facade)
        ↑
   ┌────┴────┐
web-servlet  web-webflux
        ↑
spring-boot-starter（可选传递；或文档要求宿主显式依赖其一）
```

**额外收益：** 纯 WebFlux 宿主可不再传递引入 `spring-boot-starter-web`（当前 starter 硬依赖 servlet web，对 WebFlux 宿主不友好）。第一期若工作量紧，可仍由 starter optional 引入，第二期再削依赖。

---

## 4. 工作量评估

单位：**人日（1 人全职）**；含联调与最小单测，不含大规模回归人力外溢。

### 4.1 方案对比

| 方案 | 做法 | 人日 | 优点 | 风险 |
|------|------|------|------|------|
| **A. 最小双模块** | 迁出 servlet；webflux **从业务镜像回迁/对齐**；Facade 少抽或不抽 | **8～12** | 最快消灭业务镜像 | 双份 Controller 仍在产品内，后续易分叉 |
| **B. 推荐（本方案）** | A + 按域抽 Facade，两边变薄；starter 条件装配；agent-server 下线镜像 | **14～20** | 可维护、新增 API 成本低 | 首期略长 |
| **C. 过度平台化** | 再拆 web-api、契约测试矩阵、完整 `/execution` WebFlux 对齐业务 ace-graph | **25～35+** | 最完整 | ROI 低，易延期 |

**建议落点：B**；交付节奏上 **先交 A 的可运行切片，再在同一里程碑内收敛到 B**（见 §5）。

### 4.2 分项估算（方案 B）

| 工作包 | 内容 | 人日 |
|--------|------|------|
| W0 方案冻结 / 契约表 | 路径对照表、绝对路径清单、禁止双开规则 | 0.5～1 |
| W1 模块骨架 | 新建 `web-servlet` / `web-webflux`（+ 可选 `web-support`），父 POM、BOM、deploy | 1～1.5 |
| W2 Servlet 迁出 | 移动现有 web 包 + `AceGraphDslWebConfiguration`；starter 改依赖；回归 Servlet demo | 1.5～2 |
| W3 WebFlux 设计器主路径 | Definition/Publish/Recycle/Catalog/Permission/Script/Agent/Registry/Saa/StreamKind/BizParam/AgentResources + Advice | 4～6 |
| W4 Facade 抽取 | 从 W3 重复点抽 3～5 个核心 Facade + 权限 Support | 2～3 |
| W5 权限缓存 / CORS / base-path | WebFlux 侧对齐；冒烟 | 1～1.5 |
| W6 agent-server 切换 | 依赖 webflux 模块，`web.enabled=true`，删除 `AceGraphDesigner*`；修编译 | 1～2 |
| W7 契约验收 | 设计器 UI 冒烟：列表/保存/发布/删除回收/脚本节点；对比旧镜像行为 | 1.5～2 |
| W8 文档与 CHANGELOG | 宿主接入说明、迁移指南 | 0.5～1 |
| **合计** | | **约 14～20** |

**可砍项（压到 ~10 人日）：** W4 延后到下一版本；W3 中 `/execution` 完整 WebFlux 不做；AgentResources 若业务定制多可暂留业务 Controller。

### 4.3 持续成本（上线后）

| 事项 | 无双模块（现状） | 有双模块 + Facade |
|------|------------------|-------------------|
| 新增设计器 API | 产品 1 + 业务镜像 1 | 产品 Facade 1 + 两薄 Controller（模板化，&lt;0.5） |
| 漏镜像导致 404 | **高** | **低** |
| WebFlux 宿主接入 | 复制粘贴 | 加依赖 + 开开关 |

---

## 5. 开发计划（落地节奏）

### 5.1 原则

1. **契约冻结**：路径、JSON 字段、错误 `status/error` 形状与现 UI 一致；先对照表后写码。  
2. **切片可回滚**：每片可独立 deploy；agent-server 切换单独提交。  
3. **先产品、后删镜像**：产品 webflux 在私服可用 → 业务切换 → 再删类。  
4. **执行入口分家**：`/api/ace-graph` 不进本计划强制范围。

### 5.2 里程碑

#### M0 — 立项与对照（0.5～1d）

- [ ] 输出《设计器 API 契约对照表》（产品 Servlet vs 业务镜像 vs UI `graph.js`）  
- [ ] 确认绝对路径类（`/api/agent-resources` 等）保持不变  
- [ ] 确认 `/execution`：本周期 **不做完整 WebFlux** 或仅保留别名说明  
- [ ] 版本号策略（建议 `1.2.0` 起，含模块拆分）

#### M1 — 模块骨架 + Servlet 迁出（2～3d）

- [ ] 新建 `ace-graph-dsl-web-servlet`，迁入现有 Controller / Advice / Guard / Web 配置  
- [ ] starter：去掉硬编码 ComponentScan web；改为依赖 servlet 模块 + 条件装配  
- [ ] Servlet 示例 / 既有 IT 绿  
- [ ] deploy mesrelease（中间版本可先 `1.1.20-web-wip` 或直接 `1.2.0-SNAPSHOT` 若私服允许）

#### M2 — WebFlux 设计器主路径（4～6d）**【可运行切片 A】**

优先级（与 UI 强相关靠前）：

1. Permission / Catalog / Definition / Publish  
2. Recycle（已有业务实现可回迁）  
3. ScriptNode / GenericAgentNode / NodeRegistry  
4. SaaCapability / StreamResponseKind / NodeBizParam / AgentResources  
5. ExceptionAdvice（含 `IllegalArgument`→400、`AccessDenied`→403）

- [ ] `@ConditionalOnWebApplication(REACTIVE)` + `web.enabled`  
- [ ] 与业务镜像行为 diff（或 WireMock/MockMvc WebTestClient 契约测）  
- [ ] deploy；用临时依赖在 agent-server **旁路验证**（可不删镜像）

#### M3 — Facade 收敛（2～3d）**【收敛到 B】**

- [ ] 抽出 Recycle / Definition / Script / Agent / PermissionSupport  
- [ ] Servlet 与 WebFlux Controller 改调 Facade，删除重复编排  
- [ ] 权限：WebFlux 不用 `@RequestScope`；文档写明 SPI 行为  

#### M4 — 业务切换与删镜像（1～2d）

- [ ] `lesso-ai-platform-agent-server` 显式依赖 `ace-graph-dsl-web-webflux`  
- [ ] `ace.graph.dsl.web.enabled=true`（或仅 reactive 自动开）  
- [ ] 删除 `com.lesso.ai.platform.acegraph.web.designer` 下镜像 Controller（保留 Execution / 平台定制）  
- [ ] 设计器宿主联调：删图进回收箱、恢复、脚本节点等回归  

#### M5 — 收尾（1～2d）

- [ ] CHANGELOG / 宿主接入文档（Servlet 依赖 vs WebFlux 依赖示例）  
- [ ] 评估削 starter 对 `spring-boot-starter-web` 的硬依赖（可列为 M5.1）  
- [ ] `/execution` WebFlux 完整度：单独立项或明确「仅 Servlet / 业务自建」

### 5.3 建议排期（示意）

```
Week 1:  M0 + M1 + M2 前半（Permission/Definition/Recycle）
Week 2:  M2 后半 + M3
Week 3:  M4 + M5 + 缓冲
```

单人约 **3 周日历**；双人可压缩到约 **1.5～2 周**（一人模块/Facade，一人 WebFlux Controller + 业务切换）。

---

## 6. 业务侧迁移步骤（切换清单）

1. 升级 `ace-graph-dsl-*` 至含 webflux 模块的版本。  
2. POM 增加：

```xml
<dependency>
  <groupId>io.acelance</groupId>
  <artifactId>ace-graph-dsl-web-webflux</artifactId>
  <version>${ace-graph-dsl.version}</version>
</dependency>
```

3. 配置：`ace.graph.dsl.web.enabled=true`（确认不会误启 servlet）。  
4. 删除 designer 镜像类；保留 `/api/ace-graph` 等业务 Controller。  
5. 冒烟：设计器列表 → 保存 → 发布 → **删除进回收箱** → 回收箱列表/恢复。  
6. 观察日志：应出现产品 WebFlux 自动配置注册日志，而非业务「注册 AceGraphDesigner*」日志。

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 路径/字段细微不一致 | UI 偶发失败 | M0 对照表 + WebTestClient 契约测；以 UI 与现镜像为准 |
| 双开 servlet+webflux | 路由冲突 | 条件互斥；启动 fail-fast 检测 |
| RequestScope 权限缓存 | WebFlux 行为错误 | 不迁 RequestScope；M3 单独设计 |
| starter 仍拉 servlet-web | WebFlux 宿主类路径污染 | M5 削依赖；短期 optional |
| `/execution` 期望过高 | 范围膨胀 | 明确非本周期；业务继续用 `/api/ace-graph` |
| 私服多模块发布 | 漏发模块 | reactor 一次 deploy；BOM 管理版本 |

---

## 8. 验收标准

1. **Servlet 宿主**：仅依赖 `web-servlet`（或 starter 默认），设计器 API 行为与迁出前一致。  
2. **WebFlux 宿主（agent-server）**：仅依赖 `web-webflux`，`web.enabled=true`，**无** `AceGraphDesigner*` 镜像类。  
3. 设计器 UI：保存/发布/权限菜单含 `graph:delete`、**删除进回收箱成功**（非 404）、回收列表可见。  
4. 新增一个演示性只读 API 时，只需改 Facade + 两个薄 Controller（或代码生成说明），业务仓 **零改动**。  
5. 文档：两种宿主的依赖与配置示例可复制运行。

---

## 9. 决策建议（给立项）

| 问题 | 建议 |
|------|------|
| 要不要做？ | **要做**。现状镜像成本已由回收箱 404 证实；产品双模块是正解。 |
| 选 A 还是 B？ | **B（推荐拆法）**；交付上允许先 M2（A）可运行，再 M3 收敛 Facade。 |
| 业务是否继续堆镜像？ | **仅过渡**。回收箱等紧急洞可先镜像（已做）；双模块上线后删除。 |
| 是否动 `/api/ace-graph`？ | **否**，保持业务所有。 |

---

## 10. 参考路径（仓库内）

- 产品 Web 配置：`ace-graph-dsl-spring-boot-starter/.../AceGraphDslWebConfiguration.java`  
- 产品回收箱：`.../web/GraphRecycleController.java`  
- 业务镜像包：`lesso-ai-platform-agent-server/.../acegraph/web/designer/`  
- 真 UI 定案摘录：`lesso-ai-platform-agent-server/docs/ace-graph-dsl-nacos-integration-assessment.md` §14.1.1  

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-20 | 初稿：评估 + 推荐拆法 + 工作量 + M0～M5 计划 |
