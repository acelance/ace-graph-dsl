# ACE Graph DSL：官方样例模块（从 `lesso-ai-platform-agent-server` 迁移）评估与开发计划

| 项 | 内容 |
|---|---|
| 状态 | **评估稿 / 待选型拍板** |
| 日期 | 2026-10-21 |
| 源模块 | `lesso-ai-project/lesso-ai-platform-agent-server`（平台可部署宿主，非 example） |
| 目标 | 在 `ace-graph-dsl` 仓落地**官方可用样例**，去掉 `lesso` 字眼（统一为 `ace`），可本地跑通设计器 + 图执行 |
| 关联 | 产品库已提供 `ace-graph-dsl-web-webflux`（1.1.20）；业务侧设计器 HTTP 已迁回产品 |

---

## 1. 结论先看

| 问题 | 结论 |
|------|------|
| **至少要复制多少个模块？** | 见下表三档；「真正能去品牌且可编译运行」最少 **12 个 Maven 模块**（11 库 + 1 宿主）；「官方薄样例」推荐只 **新建 1 个** 宿主样例（不搬 lesso 栈） |
| 源 agent-server 是否自包含？ | **否**。硬依赖整棵 `lesso-ai-*` 运行时（Nacos Prompt/Skill/Agent/MCP/Memory 等） |
| ace-graph-dsl 仓现状 | **无** Maven sample/example 模块；仅有 UI `demo/` 与旁路 `spring-ai-alibaba-demo` |
| 推荐路径（官方样例） | **方案 C：薄宿主样例**（1 模块 + 本地 Stub SPI），从 agent-server **借鉴**黄金图/执行入口，**不整仓复制** lesso |
| 若必须「复制并改名」对齐现网能力 | **方案 B：12 模块整栈迁移 + 去品牌**（工作量大，且样例会变成「第二个平台」） |

### 三档「最小可用」对照

| 档位 | Maven 模块数 | 是否去掉 lesso 依赖坐标 | 能力接近现网 agent-server | 推荐人日 | 适合场景 |
|------|-------------|-------------------------|---------------------------|----------|----------|
| **A. 只复制宿主** | **1**（+可选前端 1） | 否（仍依赖 `com.lesso:lesso-ai-*`） | 高（裁剪业务后） | 3～5 | 内部联调镜像；**不适合**开源/官方样例 |
| **B. 宿主 + lesso 运行时整栈改名** | **12**（+可选前端） | 是（`com.lesso`→`io.acelance`，`lesso-ai`→`ace-ai`） | 最高 | **18～28** | 产品自带「完整宿主参考实现」 |
| **C. 官方薄样例（推荐）** | **1** 新建（不复制 lesso） | 是（根本不引入 lesso） | 中（设计器+执行+黄金图；Nacos/垂直业务可选二期） | **5～8** | **官方 README 快速开始** |

> **一句话**：若目标是「官方样例 + 去掉 lesso」，**不要**把 agent-server 原样搬进产品仓；应 **1 个薄样例模块**。若目标是「产品仓内保留一份去品牌的完整宿主能力」，则至少复制 **12 个模块**。

---

## 2. 现状依赖盘点（源：`lesso-ai-platform-agent-server`）

### 2.1 直接依赖（内部）

| 坐标 | 用途 |
|------|------|
| `com.lesso:lesso-ai-spring-boot-starter` | Prompt/Skill/Agent/Core/Observability 聚合自动配置 |
| `com.lesso:lesso-ai-core` | SSE 帧、公共模型等 |
| `com.lesso:lesso-ai-agent-runtime` | Agent Spec 绑定 / ChatClient 运行时 |
| `com.lesso:lesso-ai-adapter-spring-ai-alibaba` | SAA / Skill Shell 等适配 |
| `com.lesso:lesso-ai-memory` | 记忆 Advisor / Store |
| `io.acelance:ace-graph-dsl-ai` | ChatModel / Catalog / Prompt SPI |
| `io.acelance:ace-graph-dsl-web-webflux` | 设计器 WebFlux API |
| `io.acelance:ace-graph-dsl-saa-agent` | SAA 高阶节点 |
| `io.acelance:ace-graph-dsl-agentscope-agent` | AgentScope 子 Agent |

另有：WebFlux、Nacos Config、OpenAI Starter、MySQL JDBC、`agentscope-core:1.0.9` 等。

### 2.2 传递展开后「编译运行必需」的 lesso 兄弟模块

```text
lesso-ai-platform-agent-server
├── lesso-ai-spring-boot-starter
│   ├── lesso-ai-core
│   ├── lesso-ai-prompt
│   ├── lesso-ai-skill
│   ├── lesso-ai-agent
│   └── lesso-ai-observability
├── lesso-ai-agent-runtime
│   └── lesso-ai-mcp（+ core/agent/skill/prompt）
├── lesso-ai-adapter-spring-ai-alibaba
└── lesso-ai-memory
（版本管理）lesso-ai-bom
```

**必须纳入方案 B 的模块清单（12）**：

| # | 源 artifactId | 建议目标 artifactId |
|---|---------------|---------------------|
| 1 | `lesso-ai-bom` | `ace-ai-bom` |
| 2 | `lesso-ai-core` | `ace-ai-core` |
| 3 | `lesso-ai-prompt` | `ace-ai-prompt` |
| 4 | `lesso-ai-skill` | `ace-ai-skill` |
| 5 | `lesso-ai-agent` | `ace-ai-agent` |
| 6 | `lesso-ai-mcp` | `ace-ai-mcp` |
| 7 | `lesso-ai-observability` | `ace-ai-observability` |
| 8 | `lesso-ai-memory` | `ace-ai-memory` |
| 9 | `lesso-ai-agent-runtime` | `ace-ai-agent-runtime` |
| 10 | `lesso-ai-adapter-spring-ai-alibaba` | `ace-ai-adapter-spring-ai-alibaba` |
| 11 | `lesso-ai-spring-boot-starter` | `ace-ai-spring-boot-starter` |
| 12 | `lesso-ai-platform-agent-server` | `ace-graph-dsl-sample-agent-server`（样例宿主） |

**不必复制（agent-server 未硬依赖）**：

- `lesso-ai-rag` / `lesso-ai-tool`
- `lesso-ai-adapter-agentscope`（AgentScope 已走 ace 模块）
- `lesso-ai-agent-spring-boot-starter`
- `lesso-ai-nacos-middleware`
- `lesso-ai-nacos-integration-example` / `lesso-ai-servlet-integration-example`

**可选复制（联调体验）**：

- `lesso-ai-platform-agent-designer-web` → `ace-graph-dsl-sample-designer-web`（Vue 薄宿主，`file:` 引用 `@acelance/graph-dsl-ui`）

### 2.3 宿主内业务包（可裁剪 vs 必须留）

源包根：`com.lesso.ai.platform.*`（main Java ≈ 75 个文件，≈ 9.7k LOC）

| 包/能力 | 官方样例建议 | 说明 |
|---------|--------------|------|
| `acegraph.web`（Execution / RequestMapper / Alias） | **保留** | 执行主路径示范 |
| `acegraph` 配置 / Keys / Properties | **保留并改名** | |
| `acegraph.catalog` / `prompt` / `skill` / `mcp` / `refresh` | 方案 B 保留；方案 C 用 **Stub** | Nacos 资产桥 |
| `acegraph.memory` | 方案 B 保留；方案 C 用内存记忆或关写 | 对齐 Vertical 语义时需保留 SPI 接线示例 |
| `acegraph.bizparam` / SSE Adapter | 可选 | 平台 SSE 协议；官方样例可用产品默认流 |
| `acegraph.mmfiles` / `media` | **删除或 Stub** | 联塑网盘/媒体私有 |
| `agent.vertical` / `agent.bankfee` / `agent.unified` | **删除** | 业务垂直 Agent，非产品能力 |
| `config` / Nacos bootstrap | 方案 B 可选 profile；方案 C **local only** | |

---

## 3. 推荐目标模块结构

### 3.1 方案 C（推荐）：产品仓内「samples」单模块

```text
ace-graph-dsl/
├── ace-graph-dsl-backend/          # 现有产品库（不变）
├── ace-graph-dsl-ui/               # 现有前端（不变）
├── ace-graph-dsl-samples/          # 新增：样例聚合（packaging=pom，不 deploy 或 skip）
│   ├── pom.xml
│   └── ace-graph-dsl-sample-webflux-host/   # 唯一可运行官方样例
│       ├── pom.xml
│       ├── README.md                        # 官方 Quick Start
│       └── src/main/
│           ├── java/io/acelance/graph/dsl/sample/
│           │   ├── SampleWebFluxHostApplication.java
│           │   ├── config/                  # 最小 Auto 装配
│           │   ├── spi/                     # 本地 Catalog/Prompt/ChatModel Stub
│           │   └── web/                     # 可选：精简 Execution 示范（或只靠产品 API）
│           └── resources/
│               ├── application.yml          # 仅 ace.graph.dsl.* + openai
│               ├── application-local.yml
│               ├── ace-graphs/              # 从 server 迁入 smoke / 简化黄金图
│               └── schema/                  # 可选 SQLite/H2，默认产品 SQLite
└── docs/
    └── （本文档）
```

**依赖面（样例 POM）**：

- `ace-graph-dsl-web-webflux`（主入口）
- `ace-graph-dsl-saa-agent` / `ace-graph-dsl-agentscope-agent`（演示高阶能力，可 profile 可选）
- `spring-boot-starter-webflux`
- `spring-ai-starter-model-openai`
- **不**依赖任何 `com.lesso` / `lesso-ai-*`

**父 POM 挂载建议**：

- 在仓库根增加 `ace-graph-dsl-samples` 独立 reactor，**或** 挂到 `ace-graph-dsl-backend` 下并设 `maven.deploy.skip=true`（避免样例污染正式发布）。

### 3.2 方案 B：完整去品牌「样例 = 迷你平台」

```text
ace-graph-dsl/
├── ace-graph-dsl-backend/                 # 产品 Graph DSL（现有）
├── ace-graph-dsl-ui/
├── ace-ai/                                # 新增：从 lesso-ai 迁入并改名的运行时栈
│   ├── pom.xml                            # parent + modules
│   ├── ace-ai-bom/
│   ├── ace-ai-core/
│   ├── ace-ai-prompt/
│   ├── ace-ai-skill/
│   ├── ace-ai-agent/
│   ├── ace-ai-mcp/
│   ├── ace-ai-observability/
│   ├── ace-ai-memory/
│   ├── ace-ai-agent-runtime/
│   ├── ace-ai-adapter-spring-ai-alibaba/
│   └── ace-ai-spring-boot-starter/
└── ace-graph-dsl-samples/
    ├── ace-graph-dsl-sample-agent-server/ # 由 platform-agent-server 改名裁剪
    └── ace-graph-dsl-sample-designer-web/ # 可选
```

> 风险：样例体积与维护成本接近第二个产品；开源仓引入 Nacos/平台约定需额外治理。除非明确要「官方完整宿主参考」，否则不推荐作为第一期。

### 3.3 方案 A：只复制 1 个宿主（内部用）

```text
ace-graph-dsl-samples/
└── ace-graph-dsl-sample-agent-server/   # 拷贝后改包名/配置前缀
    └── pom 仍依赖 com.lesso:lesso-ai-*（私服）
```

字眼只能清掉**本模块**表面字符串，依赖与自动配置类名仍大量 `Lesso*`——**不满足「去掉 lesso 相关字眼」的官方要求**。

---

## 4. 字眼 / 坐标 / 配置替换清单

### 4.1 坐标与包名（方案 B / 宿主自身）

| 维度 | 源 | 目标 |
|------|----|------|
| Maven groupId | `com.lesso` | `io.acelance` |
| artifact 前缀 | `lesso-ai-*` | `ace-ai-*` |
| 样例宿主 artifact | `lesso-ai-platform-agent-server` | `ace-graph-dsl-sample-agent-server` |
| Java 包 | `com.lesso.ai.*` | `io.acelance.ai.*`（库）/ `io.acelance.graph.dsl.sample.*`（样例） |
| 类名前缀 | `Lesso*` | `Ace*`（注意避免与已有 `AceGraph*` 冲突；可用 `AceAi*`） |
| 主类 | `LessoAiPlatformAgentServerApplication` | `AceGraphDslSampleAgentServerApplication` |
| AutoConfiguration | `LessoAi*AutoConfiguration` | `AceAi*AutoConfiguration` |
| `spring.factories` / `AutoConfiguration.imports` | 全量改类名 | 同步改 |

### 4.2 配置键与部署标识

| 源 | 目标建议 |
|----|----------|
| `spring.application.name: lesso-ai-platform-agent-server` | `ace-graph-dsl-sample-agent-server` |
| `spring.webflux.base-path: /platform-agent` | `/ace-sample` 或去掉 base-path |
| 整棵 `lesso.ai.*` | `ace.ai.*`（方案 B）；方案 C **删除**，只用 `ace.graph.dsl.*` |
| `platform.agent.*` / `platform.ace-graph.*` | `ace.sample.*` / 并入 `ace.graph.dsl.*` |
| Nacos DataId / Group 含 lesso | `ace-graph-dsl-sample-*.yml` / 中性 group |
| 日志包 `com.lesso.ai` | `io.acelance.ai` / `io.acelance.graph.dsl.sample` |
| SSE 魔法串若含 `LESSO` | 改为中性或可配置（勿硬编码品牌） |
| Helm / Dockerfile / `SW_AGENT_NAME` | 同步改名或样例不携带 K8s |

### 4.3 必须保留的中性标识（不要误替换）

- `ace.graph.dsl.*`、`io.acelance.graph.dsl.*`
- 资源路径 `classpath:ace-graphs/*.json`、`schema/ace_graph_dsl_*.sql`
- 产品模块名 `ace-graph-dsl-*`

### 4.4 替换执行建议（避免漏网）

1. 先改 POM / 目录 / 包路径，再批量类名。
2. 全仓检索（大小写不敏感）：`lesso`、`Lesso`、`LESSO`、`com.lesso`、`platform-agent`、`platform.agent`。
3. 测试类、Nacos sample yml、README、charts、`.gitlab-ci` 一并扫。
4. 样例 README 明确声明：**不包含联塑业务垂直 Agent / mm-files**。

---

## 5. 工作量评估

### 5.1 方案 C（推荐）— 约 **5～8 人日**

| 阶段 | 内容 | 人日 |
|------|------|------|
| P0 脚手架 | samples 父 POM + sample-webflux-host + 挂仓/skip deploy | 0.5 |
| P1 SPI Stub | 本地 ChatModelFactory、空/静态 Catalog、文件 Prompt、可选内存记忆 | 1.5～2 |
| P2 配置与黄金图 | `application-local.yml`；迁入/简化 `smoke-*.json`；SQLite 默认 | 1 |
| P3 执行路径 | 确认产品 `/api/graph/**` 试跑；必要时迁入精简 Execution 示例 | 1～1.5 |
| P4 文档与冒烟 | README Quick Start；设计器联调说明；CI 可选 compile-only | 1 |
| P5 缓冲 | OpenAI/兼容网关、agentscope 版本钉死、排障 | 0.5～1 |

### 5.2 方案 B — 约 **18～28 人日**

| 阶段 | 内容 | 人日 |
|------|------|------|
| P0 模块拷贝与 reactor | 12 模块目录 + 父 POM + BOM 改坐标 | 2～3 |
| P1 全局去品牌 | 包名/类名/配置前缀/imports 批量替换 + 编译绿灯 | 5～7 |
| P2 宿主裁剪 | 删 bankfee/vertical/mm-files；收敛 SSE/记忆示例 | 2～3 |
| P3 与产品库版本对齐 | ace-graph-dsl 1.1.20+；agentscope 1.0.9 钉死 | 1～2 |
| P4 local profile | 无 Nacos 可跑 / 有 Nacos 可选 | 2～3 |
| P5 设计器 Web（可选） | 改名 + 代理路径 | 1～2 |
| P6 文档/冒烟/回归 | README、关键路径用例、记忆落盘口径说明 | 2～3 |
| 缓冲 | 私服/开源依赖治理、漏网 lesso 字符串 | 2～3 |

### 5.3 方案 A — 约 **3～5 人日**（不满足官方去品牌）

拷贝宿主、改表面名称、裁剪垂直业务、文档说明仍依赖私服 lesso-ai。

---

## 6. 开发计划（建议按方案 C 落地；方案 B 作备选里程碑）

### 里程碑 M1：选型确认（0.5 人日）

- [ ] 确认官方样例走 **C** 还是完整宿主参考走 **B**
- [ ] 确认样例是否需要：Nacos、MySQL、Langfuse、Designer Web、SAA/AgentScope
- [ ] 确认样例是否 `deploy` 到公开仓库（建议 **skip**）

### 里程碑 M2：脚手架（方案 C）

- [ ] 新建 `ace-graph-dsl-samples` + `ace-graph-dsl-sample-webflux-host`
- [ ] POM 依赖产品 `web-webflux` + OpenAI；`maven.deploy.skip=true`
- [ ] 主类与 `application-local.yml`（仅 `ace.graph.dsl.*`）
- [ ] 日志：启动、SPI 装配、图加载、执行请求关键点打 INFO

### 里程碑 M3：最小可运行能力

- [ ] Stub：`ChatModelFactory`（读 `spring.ai.openai.*`）
- [ ] Stub：`AgentResourceCatalog`（静态列表或 classpath 资源）
- [ ] Stub：`PromptContentResolver`（classpath markdown / 内联）
- [ ] 迁入 `smoke-generic-agent.json`、`smoke-prompt-agent.json`（去掉业务敏感内容）
- [ ] 验证：设计器打开、保存草稿、发布、试跑流式输出

### 里程碑 M4：文档与产品 README 挂钩

- [ ] 样例模块 README：环境变量、端口、一键启动
- [ ] 仓库根 README「快速开始」增加指向 samples 的链接
- [ ] 明确与 `lesso-ai-platform-agent-server` 的关系：后者是**业务平台宿主**，本模块是**官方最小宿主**

### 里程碑 M5（可选，方案 B 或 C 二期）

- [ ] 可选 profile：`nacos`（演示资产热更新，配置键用 `ace.ai.*`）
- [ ] 可选：记忆落盘示例（实现产品 SPI，**勿**硬编码业务 extras 键；遵循仓内记忆落盘约定）
- [ ] 可选：`sample-designer-web` 薄前端

---

## 7. 明确不做（第一期）

1. 不把 Vertical「图尾再落盘」策略搬进样例（若演示记忆，按 ace-graph **每节点 memoryWrites 即时生效**）。
2. 不复制银行利费 / 垂直 Agent / mm-files / 联塑私有 Nacos group。
3. 不把 `lesso-ai-rag/tool/middleware` 打进官方样例。
4. 不在样例中保留 `Lesso*` 类名或 `lesso.ai` 配置前缀（方案 C/B）。
5. 不将样例默认 `deploy` 为对外正式组件（避免与 `ace-graph-dsl-spring-boot-starter` 混淆）。

---

## 8. 验收标准

| 编号 | 标准 |
|------|------|
| A1 | 新克隆仓库后，仅构建 `ace-graph-dsl-backend` + sample，**无** `com.lesso` 依赖即可启动（方案 C） |
| A2 | 源码/配置/文档检索 `lesso`（忽略历史 docs 归档）无新增命中 |
| A3 | local profile：设计器 API 可访问；至少 1 张黄金图可执行并流式返回 |
| A4 | README 可按步骤 15 分钟内跑通（有可用的模型 Key） |
| A5 | 关键路径日志完整（启动、图编译、执行开始/结束、错误栈） |

若走方案 B，额外：

| 编号 | 标准 |
|------|------|
| B1 | 12 模块 reactor `mvn -pl ... -am package` 通过 |
| B2 | 坐标均为 `io.acelance:ace-ai-*` / sample 宿主 |
| B3 | 与产品 `ace-graph-dsl` 同版本基线可联调 |

---

## 9. 决策记录（待填）

| 决策项 | 选项 | 结论 | 日期 |
|--------|------|------|------|
| 官方样例形态 | A / B / C | _待定_ | |
| 是否带 Designer Web | 是 / 否 | _待定_ | |
| 是否带 Nacos profile | 是 / 否 | _待定_ | |
| samples 是否加入 GitHub Packages 发布 | skip / 发布 | 建议 skip | |

---

## 附录 A：源模块规模速览（便于估时）

| 模块 | Java 文件（约） | LOC（约） |
|------|-----------------|-----------|
| lesso-ai-platform-agent-server | 85（含 test） | 9.7k |
| lesso-ai-core | 82 | 9.8k |
| lesso-ai-agent-runtime | 82 | 7.4k |
| lesso-ai-agent | 62 | 6.9k |
| lesso-ai-memory | 37 | 5.0k |
| lesso-ai-skill | 33 | 4.5k |
| lesso-ai-mcp | 53 | 4.2k |
| lesso-ai-prompt | 12 | 1.6k |
| lesso-ai-adapter-spring-ai-alibaba | 10 | 1.2k |
| lesso-ai-observability | 10 | 1.0k |
| lesso-ai-spring-boot-starter | 0（仅聚合） | 0 |
| **方案 B 合计（库+宿主）** | **~466** | **~51k** |
| **方案 C 目标（新建）** | **预估 15～30** | **预估 1～2k** |

## 附录 B：与旁路 demo 的关系

工作区 `spring-ai-alibaba-demo/**/…-ace-graph-dsl` 等可作为「更薄」的历史参考，但版本常落后于产品 `1.1.20`，**不宜**直接升格为官方样例；应以本计划在 `ace-graph-dsl` 仓内新建并对齐当前产品版本。
