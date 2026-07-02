# Ace Graph DSL — 作为第三方通用组件的优化与演进规划

> 版本：v1.0
> 日期：2026-06-30
> 适用范围：`ace-graph-dsl-backend`(Spring Boot Starter) 与 `ace-graph-dsl-ui`(Vue 组件库) 作为通用依赖嵌入业务系统

---

## 1. 背景

`ace-graph-dsl-backend` / `ace-graph-dsl-ui` 由 cs-reply demo 提炼为通用库。本仓库新增的
`spring-ai-alibaba-demo-cs-reply-m2-ace`(后端) 与 `spring-ai-alibaba-demo-cs-reply-web-m2-ace`(前端)
演示了"真正以第三方依赖方式引用库"的形态:相比内联拷贝的 `-dsl` 版本,业务侧代码量大幅下降——
后端仅保留 16 个 thin node/dispatcher bean + 1 个业务 SSE Controller + golden DSL,前端仅保留
`App.vue` 一处 `<GraphDslManager>` 挂载。

本文给出库进一步"产品化、可嵌入"的优化方向与演进规划。

---

## 2. 已修复的阻断性缺口(本轮)

| 缺口 | 影响 | 处理 |
|------|------|------|
| `ScriptNodeService` 缺失 | 自动配置引用不存在的类,**整库无法编译** | 新增 `service/ScriptNodeService` |
| `InMemoryDynamicNodeDefinitionRepository` 缺失 | 同上 | 新增 `persistence/memory` 实现 |
| `AbstractJdbcDynamicNodeDefinitionRepository` 缺失 | Sqlite/Jdbc 动态节点仓库父类不存在,无法编译 | 新增 `persistence/support` 基类 |
| 脚本节点 REST Controller 缺失 | 前端脚本节点 CRUD/校验/试跑全部 404 | 新增 `web/ScriptNodeController`(`/api/graph/nodes/**`) |
| `AccessDeniedException` 缺失 | `ApiExceptionAdvice` 编译失败 | 已新增 + Advice 映射 403 |
| `ApiExceptionAdvice` 空指针 / `IllegalStateException` 未处理 | 运行期异常、500 | 已加固(409/null-safe) |
| `GraphValidator` `edges` 为 null | NPE | 已加固 |

---

## 3. 嵌入式痛点与改进项(优先级 P0-P2)

### 3.1 后端 Starter

| 项 | 优先级 | 现状 | 改进 |
|----|--------|------|------|
| REST 基础路径可配置 | P0 | 路径硬编码 `/api/graph` | 提供 `ace.graph.dsl.web.base-path` 前缀配置,避免与宿主路由冲突 |
| CORS / 鉴权透传 | P0 | 无内置 CORS;写操作仅 `GraphNodeAccessControl` 占位 | 可选 CORS 配置;`AccessDeniedException` 与宿主 Spring Security 打通示例 |
| 菜单/功能权限 | ~~P1~~ ✅已实现 | 已提供 `GraphMenuAccessControl` SPI（默认全放行）+ `/api/graph/permissions/menus` REST + 前端 `usePermissionStore` 自动接入 | 见 [菜单权限接入指南](./MENU_PERMISSION_INTEGRATION.md) |
| Web 层可关闭 | P1 | Controller 随自动配置常驻 | 增加 `ace.graph.dsl.web.enabled`,允许只用运行时不暴露设计器 API |
| `ObjectMapper` 冲突 | P1 | 自带 `aceGraphDslObjectMapper`(`@ConditionalOnMissingBean`) | 改为限定名注入,避免覆盖/被覆盖宿主全局 ObjectMapper |
| 多 saver 实现 | P1 | `compile.saver` 仅 `memory`(jdbc/redis 落回 memory) | 实现 JDBC/Redis checkpoint saver,与持久化后端对齐 |
| 通用图执行 API | P1 | 执行逻辑需业务自写(本 demo 的 SSE Controller) | 提供可选的通用 `invoke/stream` 执行端点 + SSE 适配器 |
| 多租户隔离 | P2 | `graphId` 全局唯一 | 引入 tenant 维度的命名空间与持久化隔离 |
| 脚本引擎扩展 | P2 | 仅 Aviator;`AviatorScriptEngine` 每次执行起新线程 | 共享 `ExecutorService`(配 `@PreDestroy`);开放更多引擎(Groovy/QLExpress) |
| 配置元数据 | P2 | 缺少 `spring-configuration-metadata` 完整描述 | 补全 IDE 配置提示 |

### 3.2 前端组件库

| 项 | 优先级 | 现状 | 改进 |
|----|--------|------|------|
| npm 发布形态 | P0 | `main` 指向 `src/index.js`(源码直引),消费端需 dedupe/optimizeDeps 特殊处理 | 提供 `vite build` 产物(dist ESM + 样式),发布到 npm/私服 |
| `./style` 导出失效 | P0 | `package.json` 声明 `./style -> ./src/style.css` 但文件不存在 | 补 `src/style.css` 或移除该导出 |
| 鉴权注入 | P1 | API 客户端无法注入 token/拦截器 | `createGraphApi` 支持传入 axios 实例 / headers / 拦截器;组件 prop 透传 |
| 主题与 i18n | P2 | 配色硬编码,文案中文 | 暴露主题 token(CSS 变量);文案 i18n |
| 版本管理 UI | P2 | 仅发布;无版本 diff/回滚界面 | 增加版本历史、diff、一键回滚 UI(后端已具备 rollback API) |

---

## 4. 功能演进路线(里程碑)

```mermaid
flowchart LR
    R1[R1 可嵌入基线] --> R2[R2 执行与鉴权]
    R2 --> R3[R3 可观测性]
    R3 --> R4[R4 多租户与规模化]
```

- R1 可嵌入基线:base-path/web.enabled/CORS 配置、前端 dist 构建与 npm 发布、style.css 修复。
- R2 执行与鉴权:通用 invoke/stream 执行端点;`GraphNodeAccessControl` 与 Spring Security 打通;前端鉴权注入。
- R3 可观测性:对齐 demo M3 的 Langfuse,接入 trace/span,设计器内展示执行轨迹。
- R4 多租户与规模化:tenant 隔离、JDBC/Redis saver、脚本引擎线程池与配额、版本 diff/回滚 UI。

---

## 5. 工程化建议

- 单元测试:`GraphValidator`、`DynamicGraphBuilder`、`ScriptNodeService` 的核心路径补测试。
- CI:库的 `mvn verify` 与前端 `npm run build` 纳入流水线。
- SemVer:库与组件分别按语义化版本发布,demo 通过版本号(而非 `file:`/`SNAPSHOT`)引用。

---

## 6. 相关文档

- [项目总览(前后端架构说明)](./PROJECT_OVERVIEW.md)
- [节点灵活性增强探索方案](./NODE_FLEXIBILITY_EXPLORATION.md)
- [后续优化与功能规划建议](./FUTURE_OPTIMIZATION_PLAN.md)（按优先级与落地顺序的详细规划）
- 改造示例:`spring-ai-alibaba-demo-cs-reply-m2-ace`(后端)、`spring-ai-alibaba-demo-cs-reply-web-m2-ace`(前端)
