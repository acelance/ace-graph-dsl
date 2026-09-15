# 流式 LLM 节点模板 — 开发计划

> 依据：`docs/streaming-llm-node-template-design.md`（权威定案）  
> 目标：按阶段把方案落到 **backend**（`ace-graph-dsl-backend`）与 **UI**（`ace-graph-dsl-ui`），并给出 **业务侧 SPI** 排期  
> 原则：每阶段可独立验收；**后端 / UI / 业务**分列跟踪；框架欠账与业务扩展点分开编号，避免混在「完成」里

---

## 1. 总览

| 阶段 | 归属 | 主题 | 方案主锚点 | 状态 | 依赖 |
|---|---|---|---|---|---|
| **P0.1～P2.2** | 框架 | 已完成快照 | — | ✅ | — |
| **P2.3** | 框架/产品 | AgentCard（可选） | §1.3 | ⏭ 未拍板 | 产品拍板 |
| **P3.1** | 框架 | LocalToolResolver 接通 | §4.3 / §5 / §10 | ✅ | P2.2 |
| **P3.2** | 框架 | ChatClient 多轮 tool-calling | §1 / §6.4 / §10 | ✅ | P3.1 |
| **P3.3** | 框架 | MCP 三级树 + Catalog 子节点 | §7.1.1 / §7.2 | ✅ | P1.1 |
| **P3.4** | 框架 | ToolConflictPolicy | §5.2 | ✅ | P3.1 |
| **P3.5** | 框架 | LlmResolvers + PromptContentResolver | §4.5.2 | ✅ | P3.1～P3.2 |
| **P3.6** | 框架 | ResourceKeyValidator **接线** | §7.4 | ✅ | P1.1 |
| **P3.7** | 框架 | 删旧 AgentTool / McpToolProvider / legacy | D3 / §4.5.5 | ✅ | P3.1～P3.5 |
| **P3.8** | 框架 | 对话记忆钩子（conversationId + MemoryMode + AdvisorProvider） | 设计 §4.2.2 | ✅ | P3.2；评估文档 §12 |
| **P3.9** | 框架 | （可选）LlmCallLifecycleListener | 设计 §4.6 | ⏭ 按需 | 非 Langfuse 专用；评估 §13.4 |
| **Biz.0** | 业务 | 图执行入口（WebFlux Catalog/Execution + 保留键/观测绑上下文） | 评估 §11 / §12 / §13 | ✅ 主路径 | 冒烟图 `ace-smoke-generic-agent` |
| **Biz.1** | 业务 | Catalog 列表 +（可选）Key 校验实现 | §7.2 / §7.4 | ✅ 主路径（Validator 可选未做） | P1.1；含 MCP children |
| **Biz.2** | 业务 | 真实模型挂载（ModelMount + ChatModelFactory + ObservationRegistry） | §4.4 / §4.5 / §4.6 | ✅ 主路径（热刷新见 Biz.R） | Card→specRef→Spec；Fernet |
| **Biz.3** | 业务 | 真实 MCP Resolver（list_tools）+ session 生命周期 | §4.3 / §5 | ✅ 主路径（热刷新见 Biz.R） | openToolSession；runId 登记/关闭 |
| **Biz.4** | 业务 | Skill 仓内容 + forceSkills | §6 | ✅ 主路径（口令解析可增强） | L1/L2 联调过；inputs 写 forceSkills |
| **Biz.5** | 业务 | Media HEAD/魔数 Resolver | §8.2 | 未开始 | P2.1 已可扩展 |
| **Biz.6** | 业务 | 流式 Formatter + 菜单权限接入 | §9 / §7.5 | 未开始 | P1.3 |
| **Biz.7** | 业务 | 大结果 URL / 投递约定落地 | §8.3 | 未开始 | 业务节点自行 |
| **Biz.8** | 业务 | 对话记忆 Provider + 入口 BusinessContext | 设计 §4.2.2；评估 §12 | ✅ 主路径 | P3.8 ✅ |
| **Biz.9** | 业务 | Langfuse 观测接入（入口绑上下文 + ObservationRegistry + 复用业务 Filter） | 设计 §4.6；评估 §13 | ✅ 入口已做；**现有 Langfuse 控制台**已核对 Generation（产品不做 Langfuse 页面） | 随 Biz.2；不依赖产品 Langfuse SPI |
| **Biz.R** | 业务/SDK | MCP/模型热刷新接线（subscribe / Config listener → invalidate） | 评估 §4.4 / §10 | ✅ 主路径 | MCP RefreshSupport + ModelConfigWatch + RefreshBridge |

图例：`✅` 已完成 · `🟡` 部分完成 · `⏭` 延期 · 空格/未开始 · 框架=`P3.*` · 业务=`Biz.*`

---

## 2. 总排期一张图（框架 + 业务）

```text
【框架下一刀 · 迭代 A】     P3.1 LocalTool ──► P3.2 ChatClient 多轮
【框架 · 迭代 B】           P3.3 MCP 三级树 · P3.4 ConflictPolicy
【框架 · 迭代 C】           P3.5 Resolvers 收口 · P3.6 Validator 接线 · P3.7 删旧层（✅）
【框架 · 迭代 D】           P3.8 对话记忆钩子（AdvisorProvider）；P3.9 按需

【业务进度 · 2026-09-15 午】
  ✅ 已出口：  … · ①～④ 验收批 · **真 UI WebFlux 镜像第一刀（代码）**
  下一步：    部署 agent-server 后用 lesso-ai-platform-agent-designer-web 联调真 UI；可选 JDBC / 脚本写 API
  可选/可缓： Biz.5～7 · Validator · P3.9
  对齐文档：  lesso-ai-platform-agent-server/docs/ace-graph-dsl-nacos-integration-assessment.md §14.1.1
  UI 联调：    lesso-ai-project/lesso-ai-platform-agent-designer-web（业务薄宿主）
```

| 泳道 | 迭代/批次 | 包含 | 约人日 | 出口 | 进度 |
|---|---|---|---|---|---|
| 框架 | **下一刀 A** | P3.1 + P3.2 | 3～4.5 | 本地/MCP/Skill 工具能多轮调用 | ✅ |
| 框架 | B | P3.3 + P3.4 | 2.5～3.5 | 三级勾选 + 冲突策略 | ✅ |
| 框架 | C | P3.5 + P3.6 + P3.7 | 3～4 | 与方案骨架对齐、清债 | ✅ |
| 框架 | **D** | P3.8（+ 可选 P3.9） | 1.5～2.5 | 业务可挂 Spring AI Memory Advisor | ✅ P3.8 |
| 业务 | **底座** | Biz.0 图执行入口 + 冒烟图 | 1～1.5 | WebFlux stream/invoke 可跑 | ✅ |
| 业务 | **联调批 1** | Biz.2 + Biz.1（含 MCP children）+ Biz.9 入口 | 2～4 | Catalog/真模型；OTLP 前提 | ✅ 主路径 |
| 业务 | **联调批 2** | Biz.3 + Biz.4 | 3～6 | 真 MCP + Skill 渐进披露 | ✅ 主路径 |
| 业务 | **热刷新** | Biz.R | 1～2 | MCP/模型变更无需重启 | ✅ 主路径 + **MCP 冒烟 ✅** |
| 业务 | **记忆批** | Biz.8 | 1～2 | 对话级记忆读写闭环 | ✅ 主路径 + remote 两轮 ✅ |
| 业务 | **验收批** | 设计器 Catalog · 记忆两轮 · Biz.R 冒烟 · Langfuse 控制台核对 | 1～2 | 对照评估 §14.1 | ✅ ①～④已过 |
| 业务 | **增强批** | Biz.5 + Biz.6 + Biz.7 | 2～5 | 多模态补全、协议渲染、大结果 | 未开始 |

**框架 P0～P3.8 已完成；业务主路径 + 验收批（①～④）已出口；真 UI WebFlux 镜像第一刀已编码（待部署 E2E）；增强批可缓（详业务评估 §14）。**

---

## 3. 框架下一刀（优先砍什么）

> 对应此前「若只排框架下一刀」的四刀；与 P3 编号一一对应，**开工顺序固定如下**。

| 刀序 | 阶段 | 一句话 | 为何先做 | 人日 |
|---|---|---|---|---|
| **第 1 刀** | **P3.1** | LocalToolResolver 接通 | UI 已勾、运行期空转，改动面小、立刻可见 | 1～1.5 |
| **第 2 刀** | **P3.2** | ChatClient 多轮 | 没有多轮，Skill/MCP/本地工具都是「挂了调不到」 | 2～3 |
| **第 3 刀** | **P3.3** | MCP 三级树 | 编排体验；不阻塞运行期，可与第 1～2 刀并行 | 2～2.5 |
| **第 4 刀** | **P3.4～P3.7** | 冲突策略 → Resolvers 收口 → Validator 接线 → 删旧层 | 对齐方案与清债；放在工具链路通之后 | 3.5～5 |

**本周/下一批唯一开工建议：P3.1 → 紧接 P3.2。**  
P3.3 若有前端人力可并行；无则排在 A 出口后。

---

## 4. 框架欠账明细（P3.1～P3.7）

### 4.0 依赖关系

```text
P3.1 LocalTool 接通 ──► P3.2 ChatClient 多轮 ──► P3.5 LlmResolvers 收口 ──► P3.7 删旧层
         │                      │
         └──────► P3.4 ToolConflictPolicy（可贴 P3.2 尾）
P3.3 MCP 三级树（∥ P3.1/P3.2）
P3.6 ResourceKeyValidator 接线（任意空隙，建议 C）
```

### P3.1 — LocalToolResolver 接通 ✅

**方案**：§4.3 / §5 / §10 · **UI** 已有勾选 ✅

| 项 | 落点 | 状态 |
|---|---|---|
| `LocalToolResolver` / `EmptyLocalToolResolver` / `LocalToolCallbacks` | ai/tool | ✅ |
| 自动配置默认空 Bean | AceGraphDslAiAutoConfiguration | ✅ |
| Node：`enableLocalTools`+`localToolKeys` → 与 MCP 合并；miss + resource_miss | GenericAgentNode | ✅ |
| 单测 | LocalToolResolverTest | ✅ |

### P3.2 — ChatClient 多轮 tool-calling ✅

**方案**：§1 / §6.4 / §10 · **依赖** P3.1

| 项 | 落点 | 状态 |
|---|---|---|
| `ChatClient.builder` + `ToolCallAdvisor` + toolCallbacks | StreamingLlmTemplate | ✅ |
| 多轮 tool_call → 执行 → 回灌 → 终稿 | ai | ✅ |
| 流式无工具真流式；有工具先 call 再切片推送 | Template | ✅ |
| 单测假模型多轮 | ChatClientToolCallingTemplateTest | ✅ |

### P3.3 — MCP 三级树 + Catalog 子节点 ✅

**方案**：§7.1.1 / §7.2 / D3

| 项 | 落点 | 状态 |
|---|---|---|
| `ResourceItem` + `children` / `mcpServer`/`tool` 工厂 | core | ✅ |
| Catalog API 透传 children（无 Bean → 空列表） | Controller | ✅ |
| UI `el-tree` 三级勾选 → `mcpKeys` + `mcpToolWhitelist` | PropertyPanel | ✅ |
| 文本白名单降为高级回落；无 Catalog 仍可手填 | ui | ✅ |

**与业务**：Catalog 返回工具子节点 → **Biz.1**。

### P3.4 — ToolConflictPolicy ✅

**方案**：§5.2（`LOCAL_FIRST`/`MCP_FIRST`/`FAIL`）· **人日** 0.5～1 · **依赖** P3.1

| 项 | 落点 | 状态 |
|---|---|---|
| 枚举 + `ToolDeduper`；BUILTIN 永留 | ai | ✅ |
| 默认 `LOCAL_FIRST`；`LlmCallRequest.conflictPolicy` | ai | ✅ |

### P3.5 — LlmResolvers + PromptContentResolver ✅

**方案**：§4.5.2 / §4.3 · **人日** 1.5～2 · **依赖** P3.1、P3.2

| 项 | 落点 | 状态 |
|---|---|---|
| `PromptContentResolver` + 旧 PromptRepository 适配 | core | ✅ |
| `LlmResolvers` record；Template 单点注入 | ai + 自动配置 | ✅ |
| Node 变薄：只传内联 prompt；keys 进 Resolvers | GenericAgentNode | ✅ |

### P3.6 — ResourceKeyValidator 接线（框架）✅

**方案**：§7.4 / C1 · **人日** 0.5  
**说明**：只做「有 Bean 才校验」；**实现 Bean 归 Biz.1**。

| 项 | 落点 | 状态 |
|---|---|---|
| `ResourceKeyValidationGate`；保存入口探测 Validator | core + Controller/Service | ✅ |
| 无 Bean 跳过；MISSING/ERROR fail fast | web | ✅ |

### P3.7 — 删旧层 ✅

**方案**：D3 / §4.5.5 · **人日** 1～1.5 · **依赖** P3.1～P3.5

| 项 | 落点 | 状态 |
|---|---|---|
| 删 `AgentTool` / `McpToolProvider` / `AgentToolCallbacks` / `RegistryBacked*` | ai | ✅ |
| 停用 `executeLegacy` / 旧 ChatClientFactory 分支；默认 `EmptyMcpToolResolver` | Node + 自动配置 | ✅ |
| i18n + `STREAMING_OUTPUT.md` / fanout README | ui/docs | ✅ |

### P3.8 — 对话记忆钩子（ChatClient Advisor）· ✅

**方案**：设计 §4.2.2 · 评估 `ace-graph-dsl-nacos-integration-assessment.md` §12（定案 B）· **依赖** P3.2 · **人日** 1.5～2.5

| 项 | 落点 | 状态 |
|---|---|---|
| 保留键 `ace.graph.dsl.conversationId` + `LlmRequestContext.conversationId` | core | ✅ |
| 节点 `MemoryMode`（NONE / READ_ONLY / READ_WRITE）→ Spec / `LlmCallRequest` | core + ai | ✅ |
| `ChatClientAdvisorProvider` + Bundle/Request | ai | ✅ |
| `StreamingLlmTemplate` sync/stream 统一 `prepareSpec` 挂载业务 Advisor + `ChatMemory.CONVERSATION_ID` | Template | ✅ |
| `LlmResolvers` / 自动配置可选注入 Provider（null=空操作） | starter/ai | ✅ |
| 单测：假 Advisor 验证透传与 mode=NONE 跳过 | ai/test | ✅ |

**不做**：ChatMemory Store、userId/bizKey、图尾 persistMemory、认识 Lesso BusinessContext。

**验收**：业务 Provider 挂上后，call/stream 均能读到 CONVERSATION_ID；无 Provider / NONE 行为与今日一致。

**出口后业务下一步**：Biz.8 / Biz.R / Biz.9 ✅；验收批 ①～④已收口（评估 §14）；可选真 UI BFF / 增强批。

### P3.9 —（可选）LlmCallLifecycleListener · 按需

**方案**：设计 §4.6 · 评估 §13.4 · **非** Langfuse 专用 API

| 项 | 说明 | 状态 |
|---|---|---|
| 可选 SPI：`beforeCall` / `afterCall(ctx)` | 业务刷 ThreadLocal（如 nodeName） | ⏭ |
| Template 在 LLM 前后回调（有 Bean 才调） | 无 Bean 零开销 | ⏭ |

**验收**（若做）：业务能在 Filter 中读到按节点刷新的观测上下文。

---

## 5. 交业务实现排期（Biz.0～Biz.9 + Biz.R）

> SPI 框架已提供、默认空/Stub；**不进 ace-graph-dsl 产品「完成」勾选**，但必须有业务排期，否则联调永远手填 + Stub。  
> 估时按「已有 Nacos/配置中心/MCP Client 经验」的单业务开发计。  
> 业务整合对照：`lesso-ai-platform-agent-server/docs/ace-graph-dsl-nacos-integration-assessment.md`。

### 5.0 业务批次与框架依赖

| 批次 | 阶段 | 建议时机 | 人日 | 阻塞关系 | 进度 |
|---|---|---|---|---|---|
| 底座 | Biz.0 | 联调前必备 | 1～1.5 | WebFlux 入口 + 保留键 | ✅ |
| 联调 1 | Biz.2、Biz.1、Biz.9 入口 | 可与框架并行 | 2～4 | Biz.2 须挂 ObservationRegistry | ✅ 主路径 |
| 联调 2 | Biz.3、Biz.4 | **框架 A（P3.2）出口后** | 3～6 | 多轮未通时联调成本高 | ✅ 主路径 |
| 热刷新 | Biz.R | 联调 2 后补 | 1～2 | SDK 封装缺口（评估 §10） | ✅ 主路径 + MCP 冒烟 ✅ |
| 记忆 | Biz.8 | **P3.8 出口后** | 1～2 | 无钩子无法挂 Advisor；入口已先做 | ✅ 主路径（remote 两轮待验） |
| **验收批** | 设计器 E2E · 记忆两轮 · Biz.R 冒烟 · Langfuse 控制台核对 | **主路径出口后立刻** | 1～2 | 对照评估 §14.1 | ✅ **①～④已过** |
| 增强 | Biz.5、Biz.6、Biz.7 | 验收批后 / 任意 | 2～5 | — | 未开始 |
| 保存强校验 | Biz.1 Validator | **P3.6 接线后** | +0.5～1 | 可选 | 未开始 |

### Biz.0 — 图执行入口与冒烟底座 ✅

**落点**：`lesso-ai-platform-agent-server` · `com.lesso.ai.platform.acegraph` · **人日** 1～1.5

| 项 | 说明 | 状态 |
|---|---|---|
| WebFlux Catalog / Execution Controller（stream + invoke） | `/platform-agent/api/ace-graph/**` | ✅ |
| `AceGraphDslRequestSupport`：绑 `BusinessContext` + 写保留键（agentCode/runId/conversationId） | 与记忆/观测共用 | ✅ |
| bootstrap 冒烟图 `ace-smoke-generic-agent` | classpath `ace-graphs/` | ✅ |
| 适配总配置 `AceGraphDslLessoAdapterConfiguration` | 注册 Catalog/Resolver/Factory | ✅ |

**验收**：固定 sessionId 可 stream；日志可见 prepare/stream 与保留键。

### Biz.1 — AgentResourceCatalog +（可选）ResourceKeyValidator · ✅ 主路径

**方案**：§7.2 / §7.4 · **人日** 1.5～3（含 Validator）

| 项 | 说明 | 状态 |
|---|---|---|
| 实现 `AgentResourceCatalog`：prompts/models/tools/mcp/skills | 应用级按类型单例；见评估 §11.5 | ✅（tools 可空） |
| MCP 项带 **工具子节点**（配合 P3.3） | `getDetail.toolSpec.tools` → `ResourceItem.children` | ✅ |
| 可选：`ResourceKeyValidator`（廉价存在性判断） | 保存期拦截；依赖 P3.6 | 未开始 |
| 鉴权：列表按登录用户过滤（§7.5） | 业务自管 | 未开始 |

**验收**：属性面板下拉有数据；错误 key 在有 Validator 时保存失败。

### Biz.2 — 真实模型挂载（含观测 Registry）· ✅ 主路径

**方案**：§4.4 / §4.5 / §4.6 · **人日** 1～2

| 项 | 说明 | 状态 |
|---|---|---|
| `ModelMountResolver`：按 `modelConfigKey`（建议 `node_name`）解 endpoint | 密钥勿打日志 | ✅ |
| **`LessoAceGraphRuntimeBindingsResolver`**：agentCode → AgentCard → `metadata.specRef` → Spec | **禁止**裸 `bind(agentCode)`（会误找 `{code}_spec`） | ✅ |
| `ChatModelFactory`：spring-ai 原生 OpenAI 兼容（可包 Caching） | 不走 `LessoRequestChatClientFactory` | ✅ |
| **创建 ChatModel 时挂宿主 `ObservationRegistry`** | Langfuse OTLP Generation 前提；评估 §13 | ✅ |
| Nacos Spec apiKey **Fernet** 解密（`lesso.ai.encryption.key`）；INLINE 须明文 `sk-…` | 运维注意项 | ✅ 已踩通 |
| 模型 Config 热刷新 → invalidate ChatModel 缓存 | 见 **Biz.R** | ✅ |

**验收**：`modelConfigKey=nacos_agent_node` 流式打通；日志 `CONFIG_KEY` + modelId；api-key 不进日志。

### Biz.3 — 真实 MCP Resolver · ✅ 主路径

**方案**：§4.3 / §5 / P2.2 热刷新 · **人日** 2～4

| 项 | 说明 | 状态 |
|---|---|---|
| 实现 `McpToolResolver`：`openToolSession` → `NamedToolCallback`（`ToolNames` uniqueName） | `LessoNacosMcpToolResolver` | ✅ |
| **`LessoAceGraphMcpSessionRegistry`**：按 `runId` 登记会话，stream/invoke finally 关闭 | WebFlux 防泄漏 | ✅ |
| 配置变更调用 `McpToolCache.invalidate(mcpKey)` | 见 **Biz.R** | ✅（session 路径缓存多为 NOOP） |
| server 级白名单（若有）在 Resolver 内做；节点级仍靠框架 Filter | §7.1.1 | 按需 |
| 来源说明文案（消歧 description） | §5 | 可选 |

**验收**：勾选 `mcpKeys` 后模型可见真实工具并完成 tool-calling（已用 `time-mcp` 冒烟）。

### Biz.4 — Skill 仓 + forceSkills · ✅ 主路径

**方案**：§6 · **人日** 1.5～3

| 项 | 说明 | 状态 |
|---|---|---|
| `LessoNacosSkillStore`：L1 Catalog / L2 Content / L3 Resource | 包 `LessoSkillCatalog` + `LessoSkillWorkspace` | ✅ |
| 与框架 `InMemorySkillStore` 共存：业务 Bean `@Primary`；产品自动配置「已有 CatalogResolver 则跳过空仓」 | 避免注入歧义 | ✅ |
| 入口 / inputs 写入 `ACE_FORCE_SKILLS_KEY`（冒烟已验证） | 框架只认保留键 | ✅ 联调路径 |
| 用户口令 → forceSkills 自动解析 | 产品化增强 | 未开始 |
| 路径安全、按 agentCode 范围 | SkillPathSafety | 沿用 SDK |

**验收**：白名单 L1 进 system；forceSkills 预激活 L2；`load_skill` 可拉正文（冒烟已验 L2 + 一句话摘要）。

### Biz.5 — Media HEAD / 魔数

**方案**：§8.2 · **人日** 1～2 · **可立即开工**

| 项 | 说明 | 状态 |
|---|---|---|
| 扩展 `MediaRefResolver`：HEAD Content-Type + 魔数 | 遵守 SSRF/超时/大小/条数 | 未开始 |
| 可选域名白名单；mime 短 TTL 缓存 | §8.2.3 | 未开始 |

**验收**：无后缀 URL 能补全 mime；私网地址被拒并有 warn。

### Biz.6 — Formatter + 菜单权限

**方案**：§9 / §7.5 / 业务协议模板 · **人日** 1～2

| 项 | 说明 | 状态 |
|---|---|---|
| 业务 `StreamingChunkFormatter`（可继承 `KindDispatchingChunkFormatter`） | 按 kind 渲染 | 未开始 |
| 菜单权限 SPI 接入现网登录/ACL | 设计器调试按钮已挂钩 | 未开始 |
| 对照 `docs/streaming-protocol-business-template.md` 补字段 | 无强制字段 | 未开始 |

**验收**：执行/调试面板按 BIZ/OUTPUT 正确分流；无权限菜单不可见。

### Biz.7 — 大结果 URL 投递

**方案**：§8.3 · **人日** 0.5～1.5

| 项 | 说明 | 状态 |
|---|---|---|
| 约定：大对象落对象存储，state 只存 URL | 框架不强制 SPI | 未开始 |
| 业务节点/Skill 写 URL；下游按 URL 拉取 | KeyStrategy REPLACE | 未开始 |

**验收**：state 无超大 blob；下游能凭 URL 取回。

### Biz.8 — 对话记忆（ChatMemory Advisor）· ✅ 主路径

**方案**：设计 §4.2.2 · 评估 §12 · **人日** 1～2 · **依赖 P3.8**

| 项 | 说明 | 状态 |
|---|---|---|
| 实现 `ChatClientAdvisorProvider`：按 MemoryMode 返回 Ordered / ReadOnly Advisor | 复用 lesso-ai-memory / `PlatformChatMemorySupport` | ✅ |
| 入口写 `ACE_CONVERSATION_ID_KEY`=sessionId + `BusinessContext`(userId/sessionId/bizKey) | conversationId **≠** 三字段拼接 | ✅（Biz.0） |
| 节点 Spec 配置 `memoryMode`（主对话 READ_WRITE；旁路 NONE/READ_ONLY） | 禁止多节点同回合多写 ASSISTANT | ✅ 冒烟 `READ_WRITE` |
| `PlatformChatMemorySupport` 补 `createOrdered` / `createReadOnly` | 评估 §12 | ✅ |
| `GenericAgentNode` 将 `user_query` 等写入 USER（供记忆落库） | 产品小修，随 P3.8/Biz.8 | ✅ |

**验收**：同 sessionId 多轮能读到历史；USER/ASSISTANT 由 Advisor 落库；关键日志带齐身份键。

### Biz.9 — Langfuse 观测接入（业务定制；**不**做产品 Langfuse UI）· ✅

**方案**：设计 §4.6 · 评估 §13 · **人日** 0.5～1

| 项 | 说明 | 状态 |
|---|---|---|
| 入口绑 `BusinessContext` + `LessoTraceAttributes`(agentCode[, nodeName]) | 与 Biz.8 共用入口（Biz.0） | ✅ |
| ChatModelFactory 挂 `ObservationRegistry` | Biz.2 | ✅ |
| 复用既有 `ObservationFilter`（thinking_* / catalog_id 等） | **不**改产品写 langfuse.* | ✅ 现网 |
| **在现有 Langfuse 服务控制台**核对 Generation | 打开 Langfuse 自带网页，与 Vertical 对照；**非** ace-graph-dsl 自研页面 | ✅ 2026-09-15 |
| （可选）P3.9 Lifecycle 刷 nodeName | 按需 | ⏭ |
| 默认不双开 `ace-graph-dsl-langfuse` Ingestion + lesso OTLP | 避免重复上报 | 约定 |

**验收**：在 **Langfuse 服务控制台**可见与 Vertical 同类 Generation 与业务 metadata；产品源码无 `langfuse.observation.metadata` 常量、**无**产品侧 Langfuse 界面。

### Biz.R — MCP / 模型热刷新 · ✅ 主路径

**方案**：评估 §4.4 / §10 · **人日** 1～2

| 项 | 说明 | 状态 |
|---|---|---|
| lesso SDK：`subscribeMcpServer` / Config `addListener` 薄封装 | `McpRefreshSupport` + `LessoNacosConfigClient` | ✅ |
| 变更事件 → `McpToolCache.invalidate` / ChatModel 缓存失效 | `LessoAceGraphRefreshBridge` | ✅ |
| Spec bind 后自动 watch ref-model | `LessoAceGraphModelConfigWatchSupport` | ✅ |
| §10.3 AGENT 变更重绑 model watches | 可选 | ⏭ |
| 配置开关 | local：`lesso.ai.refresh.enabled=true` | ✅ |

**验收**：Nacos 改 MCP tools / 模型 ref 后，日志见 `[McpRefresh]` / `[ModelConfigWatch]` / `[AceGraphRefresh]`，下次请求无需重启即可拿到新配置。

**注意**：当前 `LessoNacosMcpToolResolver` 为 **按 run 开 session**，未套跨请求 `CachingMcpToolResolver`（避免关 session 后复用死回调）；MCP 事件仍会走 Bridge（缓存多为 NOOP），**模型侧 invalidate 是主收益**。

---

## 6. 明确不进排期 / 非目标

| 项 | 原因 |
|---|---|
| **P2.3 AgentCard** | 未拍板；§1.3 非目标（**运行期**仍用 Card 的 `specRef` 解析 Spec，≠ 产品 AgentCard 能力） |
| **emitKind / 节点内切 kind** | B3=① 定案不做 |
| **`search_skills`（上千 skill）** | 方案可选增强，非当前必做 |
| 业务 SPI **实现代码进框架仓库** | 默认 Stub/空实现即可；真实 Bean 在业务工程 |
| **产品内 ChatMemory Store / 图尾 persistMemory** | 记忆定案 B；Store 在业务 |
| **产品 Langfuse 专用 SPI / 写 langfuse.\* 键** | 定案原生 Observation + 业务 Filter |
| **裸 `SpecBinder.bind(agentCode)` 当 ModelMount 唯一路径** | 现网 Spec 多为 `{agentCode}_{n}_spec`；须 Card→specRef（Biz.2 已定） |

---

## 7. UI 欠账（跨框架/业务）

| 来源 | UI 项 | 归属 | 状态 |
|---|---|---|---|
| P0～P2.2 | 历史 | 框架 | ✅ / — |
| P3.1 | 本地工具勾选 | 框架 | ✅ 已有 |
| **P3.3** | MCP 三级树 | 框架 | ✅ PropertyPanel |
| **Biz.1** | Catalog 有数据（含 MCP children）后树可填满 | 业务 | ✅ agent-server 已接 |
| P3.7 | 旧 SPI hint/i18n | 框架 | ✅ |
| 记忆 | 节点 `memoryMode` 属性面板 | 框架/UI | 等 P3.8 Spec |

---

## 8. 已完成快照（P0～P2.2）

| 阶段 | 后端 | UI | 备注 |
|---|---|---|---|
| P0.1～P0.5 | ✅ | ✅/— | 模块、Binding、Template 初版、旧字段硬删 |
| P1.1～P1.3 | ✅ | ✅ | Catalog API、Skill、协议/调试按钮 |
| P2.1 | ✅ | ✅ | MediaRef（默认无 HEAD） |
| P2.2 | ✅ | — | MCP 缓存 + 热刷新（框架侧；业务接线见 Biz.R） |
| P2.3 | ⏭ | — | AgentCard 未拍板 |

---

### 状态快照（2026-09-14 · 联调批 1～2 出口后回写）

| 轨道 | 状态 | 下一步 |
|---|---|---|
| P0～P2.2 | ✅ | — |
| P2.3 | ⏭ | 等拍板 |
| **P3.1～P3.7** | ✅ | 框架工具链欠账清完 |
| **P3.8** | ✅ | 对话记忆 Advisor 钩子已出口；业务可开 Biz.8 |
| **P3.9** | ⏭ 按需 | 节点级观测上下文刷新 |
| **Biz.0** | ✅ | 图执行入口 + 冒烟图 |
| **Biz.1** | ✅ 主路径 | Validator / 列表鉴权可选 |
| **Biz.2** | ✅ 主路径 | 热刷新见 Biz.R；Fernet/specRef 已踩通 |
| **Biz.3** | ✅ 主路径 | session 关闭已做；热刷新见 Biz.R |
| **Biz.4** | ✅ 主路径 | 口令→forceSkills 可增强 |
| **Biz.5～7** | 未开始 | 增强批，可缓 |
| **Biz.8** | ✅ 主路径 | Provider + 冒烟 `memoryMode=READ_WRITE`；两轮同 session 验收 |
| **Biz.9** | ✅ | 现有 Langfuse 控制台 Generation 已核对（产品不做 Langfuse 页面） |
| **Biz.R** | ✅ 主路径 | MCP 冒烟已过；模型 Config 可选补验 |

**建议下一刀**：验收批已收口；可选真 UI 建图 BFF / 增强批；增强批可缓。
