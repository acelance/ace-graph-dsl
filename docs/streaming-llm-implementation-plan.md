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
| **Biz.1** | 业务 | Catalog 列表 +（可选）Key 校验实现 | §7.2 / §7.4 | 未开始 | P1.1；三级树要 Biz.1 配 P3.3 |
| **Biz.2** | 业务 | 真实模型挂载（ModelMount + ChatModelFactory） | §4.4 / §4.5 | 未开始 | 可与 P3 并行 |
| **Biz.3** | 业务 | 真实 MCP Resolver（list_tools） | §4.3 / §5 | 未开始 | 建议等 P3.2；热刷新用 P2.2 Cache |
| **Biz.4** | 业务 | Skill 仓内容 + forceSkills 口令解析 | §6 | 未开始 | 建议等 P3.2 |
| **Biz.5** | 业务 | Media HEAD/魔数 Resolver | §8.2 | 未开始 | P2.1 已可扩展 |
| **Biz.6** | 业务 | 流式 Formatter + 菜单权限接入 | §9 / §7.5 | 未开始 | P1.3 |
| **Biz.7** | 业务 | 大结果 URL / 投递约定落地 | §8.3 | 未开始 | 业务节点自行 |

图例：`✅` 已完成 · `⏭` 延期 · 空格/未开始 · 框架=`P3.*` · 业务=`Biz.*`

---

## 2. 总排期一张图（框架 + 业务）

```text
【框架下一刀 · 迭代 A】     P3.1 LocalTool ──► P3.2 ChatClient 多轮
【框架 · 迭代 B】           P3.3 MCP 三级树 · P3.4 ConflictPolicy
【框架 · 迭代 C】           P3.5 Resolvers 收口 · P3.6 Validator 接线 · P3.7 删旧层

【业务可并行】
  随时可开：  Biz.1 Catalog（手填也能跑，但列表为空）· Biz.2 真实模型 · Biz.5 Media HEAD · Biz.6 Formatter/权限 · Biz.7 大结果 URL
  建议等 A：  Biz.3 真实 MCP · Biz.4 Skill 仓（多轮通了再联调省事）
  建议等 B：  Biz.1 补 MCP 工具子节点（配合 P3.3 三级树）
  建议等 C：  Biz.1 可选 ResourceKeyValidator 实现（框架接线 P3.6 完成后才生效）
```

| 泳道 | 迭代/批次 | 包含 | 约人日 | 出口 |
|---|---|---|---|---|
| 框架 | **下一刀 A** | P3.1 + P3.2 | 3～4.5 | 本地/MCP/Skill 工具能多轮调用 |
| 框架 | B | P3.3 + P3.4 | 2.5～3.5 | 三级勾选 + 冲突策略 |
| 框架 | C | P3.5 + P3.6 + P3.7 | 3～4 | 与方案骨架对齐、清债 |
| 业务 | **联调批 1** | Biz.2 + Biz.1（扁平列表） | 2～4 | 设计器有可选资源；能打真实模型 |
| 业务 | **联调批 2** | Biz.3 + Biz.4 | 3～6 | 真 MCP + Skill 渐进披露可用 |
| 业务 | **增强批** | Biz.5 + Biz.6 + Biz.7 | 2～5 | 多模态补全、协议渲染、大结果 |

**框架合计约 9～12 人日；业务合计约 7～15 人日**（视 Nacos/MCP/模型平台复杂度浮动）。

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

---

## 5. 交业务实现排期（Biz.1～Biz.7）

> SPI 框架已提供、默认空/Stub；**不进 ace-graph-dsl 产品「完成」勾选**，但必须有业务排期，否则联调永远手填 + Stub。  
> 估时按「已有 Nacos/配置中心/MCP Client 经验」的单业务开发计。

### 5.0 业务批次与框架依赖

| 批次 | 阶段 | 建议时机 | 人日 | 阻塞关系 |
|---|---|---|---|---|
| 联调 1 | Biz.2、Biz.1（扁平） | 可立即与框架 A 并行 | 2～4 | 不阻塞框架 |
| 联调 2 | Biz.3、Biz.4 | **框架 A（P3.2）出口后**最省事 | 3～6 | 多轮未通时联调成本高 |
| 增强 | Biz.5、Biz.6、Biz.7 | 任意；Media/协议不依赖 P3 | 2～5 | — |
| 对齐三级树 | Biz.1 补 MCP children | **配合 P3.3** | +0.5～1 | 无 children 则三级树只能手填工具 |
| 保存强校验 | Biz.1 Validator 实现 | **P3.6 接线后** | +0.5～1 | 接线前实现了也不生效 |

### Biz.1 — AgentResourceCatalog +（可选）ResourceKeyValidator

**方案**：§7.2 / §7.4 · **人日** 1.5～3（含 Validator）

| 项 | 说明 | 状态 |
|---|---|---|
| 实现 `AgentResourceCatalog`：prompts/models/tools/mcp/skills | 按 `agentCode` 初筛 | |
| MCP 项带 **工具子节点**（配合 P3.3） | `ResourceItem.children` | |
| 可选：`ResourceKeyValidator`（廉价存在性判断） | 保存期拦截；依赖 P3.6 | |
| 鉴权：列表按登录用户过滤（§7.5） | 业务自管 | |

**验收**：属性面板下拉有数据；错误 key 在有 Validator 时保存失败。

### Biz.2 — 真实模型挂载

**方案**：§4.4 / §4.5 · **人日** 1～2 · **可立即开工**

| 项 | 说明 | 状态 |
|---|---|---|
| `ModelMountResolver`：按 `modelConfigKey` 解 baseUrl/apiKey/modelId | 密钥勿打日志 | |
| `ChatModelFactory`：真实 OpenAI 兼容客户端（可包在 `CachingChatModelFactory` 外） | 替换 Stub | |
| 请求级覆盖与 A5 优先级联调 | 与现网 Template 路径 | |

**验收**：节点能打通真实 endpoint；缓存命中有日志；api-key 不进日志。

### Biz.3 — 真实 MCP Resolver

**方案**：§4.3 / §5 / P2.2 热刷新 · **人日** 2～4 · **建议** 框架 A 后

| 项 | 说明 | 状态 |
|---|---|---|
| 实现 `McpToolResolver`（或替换 Registry 适配底层） | `list_tools` → `NamedToolCallback` | |
| 配置变更调用 `McpToolCache.invalidate(mcpKey)` | 热刷新 | |
| server 级白名单（若有）在 Resolver 内做；节点级仍靠框架 Filter | §7.1.1 | |
| 来源说明文案（消歧 description） | §5 | |

**验收**：勾选 mcpKeys 后模型可见真实工具；invalidate 后下次请求拿到新清单。

### Biz.4 — Skill 仓 + forceSkills 口令

**方案**：§6 · **人日** 1.5～3 · **建议** 框架 A 后

| 项 | 说明 | 状态 |
|---|---|---|
| 填充 `SkillCatalogResolver` / `ContentLoader` / `ResourceLoader`（或 `InMemorySkillStore` 灌数） | L1/L2/L3 | |
| 入口解析用户口令 → 写入 `ACE_FORCE_SKILLS_KEY` | 框架只认保留键 | |
| 路径安全、按 agentCode 范围 | SkillPathSafety | |

**验收**：白名单 L1 进 system；forceSkills 预激活；`load_skill` 多轮可拉正文。

### Biz.5 — Media HEAD / 魔数

**方案**：§8.2 · **人日** 1～2 · **可立即开工**

| 项 | 说明 | 状态 |
|---|---|---|
| 扩展 `MediaRefResolver`：HEAD Content-Type + 魔数 | 遵守 SSRF/超时/大小/条数 | |
| 可选域名白名单；mime 短 TTL 缓存 | §8.2.3 | |

**验收**：无后缀 URL 能补全 mime；私网地址被拒并有 warn。

### Biz.6 — Formatter + 菜单权限

**方案**：§9 / §7.5 / 业务协议模板 · **人日** 1～2

| 项 | 说明 | 状态 |
|---|---|---|
| 业务 `StreamingChunkFormatter`（可继承 `KindDispatchingChunkFormatter`） | 按 kind 渲染 | |
| 菜单权限 SPI 接入现网登录/ACL | 设计器调试按钮已挂钩 | |
| 对照 `docs/streaming-protocol-business-template.md` 补字段 | 无强制字段 | |

**验收**：执行/调试面板按 BIZ/OUTPUT 正确分流；无权限菜单不可见。

### Biz.7 — 大结果 URL 投递

**方案**：§8.3 · **人日** 0.5～1.5

| 项 | 说明 | 状态 |
|---|---|---|
| 约定：大对象落对象存储，state 只存 URL | 框架不强制 SPI | |
| 业务节点/Skill 写 URL；下游按 URL 拉取 | KeyStrategy REPLACE | |

**验收**：state 无超大 blob；下游能凭 URL 取回。

---

## 6. 明确不进排期 / 非目标

| 项 | 原因 |
|---|---|
| **P2.3 AgentCard** | 未拍板；§1.3 非目标 |
| **emitKind / 节点内切 kind** | B3=① 定案不做 |
| **`search_skills`（上千 skill）** | 方案可选增强，非当前必做 |
| 业务 SPI **实现代码进框架仓库** | 默认 Stub/空实现即可；真实 Bean 在业务工程 |

---

## 7. UI 欠账（跨框架/业务）

| 来源 | UI 项 | 归属 | 状态 |
|---|---|---|---|
| P0～P2.2 | 历史 | 框架 | ✅ / — |
| P3.1 | 本地工具勾选 | 框架 | ✅ 已有 |
| **P3.3** | MCP 三级树 | 框架 | ✅ PropertyPanel |
| **Biz.1** | Catalog 有数据（含 MCP children）后树可填满 | 业务 | 未开始 |
| P3.7 | 旧 SPI hint/i18n | 框架 | ✅ |

---

## 8. 已完成快照（P0～P2.2）

| 阶段 | 后端 | UI | 备注 |
|---|---|---|---|
| P0.1～P0.5 | ✅ | ✅/— | 模块、Binding、Template 初版、旧字段硬删 |
| P1.1～P1.3 | ✅ | ✅ | Catalog API、Skill、协议/调试按钮 |
| P2.1 | ✅ | ✅ | MediaRef（默认无 HEAD） |
| P2.2 | ✅ | — | MCP 缓存 + 热刷新 |
| P2.3 | ⏭ | — | AgentCard 未拍板 |

---

### 状态快照（2026-09-11 · P3.1～P3.7 框架欠账清完）

| 轨道 | 状态 | 下一步 |
|---|---|---|
| P0～P2.2 | ✅ | — |
| P2.3 | ⏭ | 等拍板 |
| **P3.1～P3.7** | ✅ | 框架欠账清完；业务联调 |
| **Biz.1～Biz.7** | 排期定 | 联调 1 可并行；Biz.1 补 MCP children 才能填满三级树；Biz.3 接真 MCP |
