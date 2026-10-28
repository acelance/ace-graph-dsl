# ACE Graph DSL × AgentScope：路径 B MVP — 开发方案

| 项 | 内容 |
|----|------|
| 状态 | **MVP 已验收**（框架 `1.1.21` mesrelease；测试环境业务回归通过；待 PR 合入；缺口 G-MCP / G-STREAM-INNER） |
| 日期 | 2026-10-21（进度更新 2026-10-23） |
| 分支 | 框架 `feature/as-b1-path-b-mvp`；宿主 `feature-agentscope-B-MVP` |
| 阶段 | 分阶段规划 **阶段 1** |
| 主战场 | `ace-graph-dsl-agentscope-agent`（框架约 70%～85%） |
| 关联 | [分阶段分支规划](./ACE-Graph-DSL-AgentScope-路径B与Harness分阶段分支规划.md)；[Harness 能力边界](./ACE-Graph-DSL-AgentScope-Harness能力边界-core与ReAct薄桥.md)；宿主 [integration-paths](../../lesso-ai-project/lesso-ai-platform-agent-server/docs/ace-graph-agentscope-integration-paths.md)；[开发计划](./ACE-Graph-DSL-AgentScope-路径B-开发计划.md) |

---

## 0.1 已拍板（2026-10-22）

| # | 议题 | 结论 |
|---|------|------|
| 1 | `agentscope.version` | **钉 `2.0.4`** |
| 2 | 附录 A「改用 core 兜底」 | **勾「是」**。原因：**官方没有 `spring-ai-alibaba-starter-agentscope` 适配 agentscope 2.x，只能自研适配**（见 §7） |
| 3 | 宿主跟版 | **同迭代**（框架 + 宿主 host 并行） |
| 4 | `agentscopeBridge` 探测字段 | **当前是什么桥就写什么明确坐标/版本**，禁止沿用会歧义的旧 starter 文案（见 §5.4） |
| 5 | Adapter 类名 | 不冲突即可，实施时可微调 |
| 6 | MVP 工具/MCP | **知难行易**：本阶段只做 MVP（对齐 M3：桥通 + 模型写 `outputKey`）。**MVP ≠ 完成**；缺口必须记入 [§10 跟进缺口表](#10-mvp-后须跟进的缺口防遗忘)，后续另开开发 |
| 7 | 流式 | **外层与用户交互的业务 SSE = 硬性指标**（如 `AceGraphExecutionController`）。**内层 AGENTSCOPE 子 Agent 可暂时非流式**，记缺口跟进；**不得默认永久 `stream(false)`**（见 §5.6） |
| 8 | AS 2.x API | **勿假定**方案中的 API 代码片段与实物 `agentscope 2.0.4` 一致；思路可参考，**实际集成以实物为准**（D0/D1 对照 jar/Javadoc，有差改实现与方案一小节） |

---

## 1. 背景与目标

### 1.1 现状问题

| 点 | 说明 |
|----|------|
| 现行桥 | `spring-ai-alibaba-starter-agentscope` → 包装类 `AgentScopeAgent`，钉 **`agentscope-core:1.0.9`** |
| 缺口 | 同 jar 无法合法吃 AS **2.x**；无官方 `starter-agentscope` 2.0 |
| Q4 原定案 | **优先** starter；**直接 core 仅兜底**（须附录 A 勾「是」并写原因） |

### 1.2 本阶段目标（MVP）

1. **旁路**官方 starter，依赖 **`agentscope-core:2.x`**（+ model 扩展）。
2. **自研** SAA `BaseAgent` 适配器，内部调用 AS 2.x **`ReActAgent`**（对齐现 M3 冒烟能力）。
3. `impl=AGENTSCOPE` 样例图 **可跑通**；GENERIC / 无 AS 路径 **回归不坏**。
4. Boot **3.5** / Spring AI **1.1** / SAA **1.1.x** 保持不变。
5. 子记忆继续 **NONE**（Q5）。

### 1.3 非目标（本阶段禁止顺带做）

- `agentscope-harness` / `HarnessAgent` / Subagent / Plan Mode  
- Memory/Compaction 进 ACE、设计器新字段、WebFlux resume  
- Boot 4 / SAA 2.0 整栈  
- AS Service Workflow 替换 ACE 图  
- `GENERIC_AGENT` 统一换成 AS  

---

## 2. 设计原则

| # | 原则 |
|---|------|
| P1 | **ACE 图仍是主编排**；AS 仅 `impl=AGENTSCOPE` 子运行时 |
| P2 | **资源仍走 ACE 目录**：`ref=agentscope:{id}` → 已注册 GenericAgent Spec / 模型 key，不另建 AS 资源目录 |
| P3 | **对齐 `GenericAgentBaseAgentAdapter` 模式**：扩展 SAA `BaseAgent`，供 FlowAgent `subAgents` 使用 |
| P4 | **不引入** `agentscope-spring-boot-starter`（钉 Boot 4） |
| P5 | 依赖冲突（Reactor / Jackson 等）用 BOM **显式钉版本**，写入本方案附录与 CHANGELOG |
| P6 | 附录 A：**勾选「改用 agentscope-core 兜底 = 是」** 并写原因（官方无 2.0 starter） |

---

## 3. 目标架构

### 3.1 现状（路径 A）

```text
AgentScopeSubAgentResolver
  → ReActAgent.Builder (AS 1.0.9)
  → AgentScopeAgent.fromBuilder(...)   // 来自 starter-agentscope
  → FlowAgent.subAgents
```

### 3.2 目标（路径 B MVP）

```text
AgentScopeSubAgentResolver
  → ReActAgent.Builder (AS 2.x API)
  → AceAgentscopeReActBaseAgentAdapter (自研，extends BaseAgent)
        └─ doCall / 状态键：读 instruction 模板、写 outputKey
  → FlowAgent.subAgents
```

模型仍由 `AgentScopeModelFactory` → `OpenAIChatModel`（或 2.x 等价 extensions 包名）；`BRIDGE_ARTIFACT` 常量改为自研桥标识（如 `ace-graph-dsl-agentscope-agent/core-2.x`）。

### 3.3 与 GENERIC_AGENT 并存

| impl | 解析器 | 执行体 |
|------|--------|--------|
| `GENERIC_AGENT` | `GenericAgentSubAgentResolver` | `GenericAgentBaseAgentAdapter`（不变） |
| `AGENTSCOPE` | `AgentScopeSubAgentResolver` | **自研** ReAct BaseAgent 适配器 |

---

## 4. 模块与依赖变更

### 4.1 `ace-graph-dsl-agentscope-agent/pom.xml`

| 动作 | 坐标 | 说明 |
|------|------|------|
| **移除** | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-agentscope` | 旁路官方桥 |
| **新增** | `io.agentscope:agentscope-core`（**钉 `2.0.4`**） | 核心 |
| **新增** | `io.agentscope:agentscope-extensions-model-openai`（与 core 同版本） | 现工厂用 OpenAI 兼容；若包名/类名有变，以 2.x 文档为准 |
| **保留** | `ace-graph-dsl-core` / `ace-graph-dsl-ai` / `saa-agent`(test) | 不变 |
| **禁止** | `io.agentscope:agentscope-spring-boot-starter` | Boot 4 |
| **本阶段禁止** | `io.agentscope:agentscope-harness` | 留给 as-h0 |

父 BOM / `dependencyManagement`：在 `ace-graph-dsl-backend/pom.xml` 增加 AS 2.x 版本属性（如 `agentscope.version`），便于宿主对齐。

### 4.2 依赖共存风险（须在开发首日验证）

| 风险 | 应对 |
|------|------|
| Reactor / Jackson 偏新 | 父 POM 钉与 Boot 3.5 兼容版本；exclusion 冲突传递 |
| 与 SAA 1.1 传递的旧 AS 残留 | 强制不引入 starter；`dependency:tree` 确认无 1.0.9 |
| API 包名迁移（1.x→2.x） | 编译期改 import；以 AS 2.x Javadoc 为准 |

---

## 5. 核心类设计

### 5.1 新增：`AceAgentscopeReActBaseAgentAdapter`（名称可微调）

| 项 | 说明 |
|----|------|
| 包 | `io.acelance.graph.dsl.agentscope` |
| 继承 | `com.alibaba.cloud.ai.graph.agent.BaseAgent`（与 `GenericAgentBaseAgentAdapter` 同族） |
| 构造 | `name, description, instruction, outputKey, ReActAgent`（或 Builder 产物） |
| 职责 | 按 instruction 从 state 拼输入 → 调 AS 2.0.4 **实物 API**（方案中的 `call` 等仅为思路）→ 写 `outputKey` |
| 日志 | 关键节点：开始/结束、耗时、outputKey、错误栈（**必打**） |
| 参考 | `GenericAgentBaseAgentAdapter` 的 state 读写与异常处理；**不要**复制 starter 闭源细节；**以 SAA BaseAgent 契约 + AS 2.0.4 实物为准** |

### 5.2 改造：`AgentScopeSubAgentResolver`

- 删除对 `com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent` 的依赖。
- 按 **2.0.4 实物** 构建 `ReActAgent`（或等价）后交给自研 Adapter。
- Q5：继续拒绝 `READ_WRITE`；短记忆不写 remote。
- 日志 `bridge=` 与探测字段使用 **明确坐标版本**（见 §5.4）。

### 5.3 改造：`DefaultAgentScopeModelFactory`

- `BRIDGE_ARTIFACT` / 探测用字符串 = **当前真实桥坐标**（例：`io.agentscope:agentscope-core:2.0.4` + 自研 Adapter 模块名，实施时写死常量）。
- 模型类 import、builder 参数以 **2.0.4 实物** 为准（勿照抄方案伪代码）。
- 行为保持：`modelConfigKey` → `ModelMountResolver`；否则 Spec 内联端点。
- MVP 阶段子 Agent 内模型可暂 `stream(false)`，**必须**在 §10 登记「子路径流式」跟进项。

### 5.4 自动配置 / 能力探测

- `AceGraphDslAgentscopeAgentAutoConfiguration`：仍注册 Resolver + ModelFactory；注释与 README 改为路径 B / core **2.0.4**。
- `SaaCapabilityController.agentscopeBridge`：**当前是什么就写什么**（明确 GAV 或 `模块名@core:2.0.4`），**禁止**再返回会歧义的 `"spring-ai-alibaba-starter-agentscope"`。

### 5.5 测试

| 测试 | 要求 |
|------|------|
| `AgentScopeSequentialFactoryIntegrationTest` | 在 2.x + 自研 Adapter 下仍绿（可用 stub model） |
| 新增 Adapter 单测 | instruction 占位、`outputKey` 写回、异常路径 |
| 回归 | `ace-graph-dsl-saa-agent` 既有 SEQUENTIAL/PARALLEL 等（GENERIC 子 Agent）不受影响 |

### 5.6 流式分层（对应拍板 #7）

存在 **两层「流」**，勿混：

| 层 | 现状 | 路径 B 要求 |
|----|------|-------------|
| **A. 外层业务 SSE**（直接对用户） | `AceGraphExecutionController` 等 | **硬性指标：必须保持流式**；不得因路径 B 破坏 |
| **B. 内层 AGENTSCOPE 子 Agent 模型** | 现工厂常 `.stream(false)`，整段写 `outputKey` | **MVP 可暂时非流式**；**必须**记入 §10；**不得默认永久 `stream(false)`** |

### 5.7 工具/MCP（对应拍板 #6）

| 路径 | 行为 |
|------|------|
| 现 M3 AGENTSCOPE 冒烟 | 多为无工具：模型出文本 → 写 `outputKey` |
| `GENERIC_AGENT` 生产 | 常挂 MCP/Skill |
| 路径 B MVP | **知难行易**：只做桥 + 写回（对齐 M3）；**MCP/工具另项跟进**（§10） |

---

## 6. 宿主侧（辅）

| 项 | 说明 |
|----|------|
| pom | 升 `ace-graph-dsl-*` 含本改动的版本；**去掉**显式 `agentscope-core:1.0.9` |
| 文档 | 更新 `ace-graph-agentscope-host.md`：路径 B、禁止 Boot starter、冒烟清单 |
| 附录联动 | 宿主 integration-paths 链到本方案；附录 A 在框架设计文档回填 |
| 冒烟 | `saa-agentscope-sequential-sql` 或等价；`capabilities/saa` → `agentscopeEnabled=true` |

宿主 **不**在本阶段改利费/`GENERIC_AGENT` 主图。

---

## 7. 文档与合规

| 文档 | 动作 |
|------|------|
| `ACE-Graph-DSL-SAA高阶模式节点-开发设计与计划.md` 附录 A | 「是否改用 agentscope-core 兜底」→ **是**；原因：**官方没有 `spring-ai-alibaba-starter-agentscope` 适配 agentscope 2.x，只能自研适配** |
| FAQ §12 / Q4 | 增补：路径 B 已立项时的现行坐标说明（优先仍写「历史 M3 为 starter」+「B 阶段起 core 兜底」） |
| `ace-graph-dsl-agentscope-agent/README.md` | 全面改为路径 B |
| `CHANGELOG.md` | 记录破坏性：宿主须升版本并去 1.0.9 钉死 |
| 本方案 / [开发计划](./ACE-Graph-DSL-AgentScope-路径B-开发计划.md) | 实施过程勾选 |

---

## 8. 风险与回滚

| 风险 | 等级 | 缓解 |
|------|------|------|
| AS 2.x API 与 1.0.9 差异大 | 中 | 首日 spike 编译；对照官方 Migration |
| BaseAgent 契约与官方 AgentScopeAgent 行为差 | 中 | 对齐 GenericAgent 适配器；SEQUENTIAL 样例对拍 |
| 传递依赖炸宿主 | 高 | `dependency:tree` + 钉版本；宿主单独 host 分支跟版 |
| 与未来官方 starter 2.x 分叉 | 低 | Adapter 边界清晰，便于日后换回官方包装 |

**回滚**：宿主退回旧框架版本 + 钉 1.0.9；或临时不引入 `ace-graph-dsl-agentscope-agent`（图禁用 AGENTSCOPE）。

---

## 9. 验收标准（方案级）

与 Issue / 开发计划一致：

1. 无 `starter-agentscope` 运行时依赖；`agentscope-core` 为 **2.0.4**（自研适配）  
2. `impl=AGENTSCOPE` 样例写回成功（MVP 无 MCP）  
3. GENERIC SAA 回归通过；**外层业务 SSE 保持流式**  
4. 附录 A 已勾兜底；原因写明「官方无 starter 适配 2.x，只能自研」  
5. `agentscopeBridge` 为当前明确坐标版本  
6. 日志关键路径可排查；合入材料附 **§10 缺口跟进**

---

## 10. MVP 后须跟进的缺口（防遗忘）

> **MVP 合入 ≠ 路径 B 能力完成。** 下列项本阶段可暂缓，但必须另开跟进（建议在 PR/Issue 勾选并开子 Issue）。

| ID | 缺口 | 说明 | 建议时机 | 状态 |
|----|------|------|----------|------|
| **G-MCP** | AGENTSCOPE 子 Agent 挂真实工具 / MCP | 与 GENERIC 工具会话对齐；本 MVP 仅模型写 `outputKey` | MVP 合入后下一迭代 | **能力已验收**（`1.1.22`；Skill + `translation` MCP 真实调用，2026-10-26）；`time-mcp` 环境 404；待 PR / 测环境跟版。[方案](./ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md) / [计划](./ACE-Graph-DSL-AgentScope-G-MCP-开发计划.md) |
| **G-STREAM-INNER** | 内层子 Agent 模型流式 → 接到图 SSE；**含 SAA 父 applyDeepThinking/bizParam 补齐** | 外层业务流已是硬指标；**禁止永久 `stream(false)`** | 进行中 | **框架侧已落地**（§0.5 全推模型可见文本；宿主冒烟/发版=M4）：[方案](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md) / [计划](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发计划.md)；分支 `feature-as-b1-g-stream-inner` |
| **G-API-DOC** | 方案伪代码 vs 2.0.4 实物差异回填 | 已对照：`OpenAIChatModel`→`extensions.model.openai`；Builder **无** `memory()`；`call` 返回 `Mono`（Adapter `.block()`） | M1-D0/D1 | **已回填** |
| **G-HARNESS** | `agentscope-harness` / HarnessAgent / Subagent / Plan | 属阶段 2+，非本 MVP | 见分阶段分支规划 H0～ | 未开始 |

开发计划中的勾选表须与本表联动；合入 PR 描述中附「未做缺口清单」链接本节。

---

## 11. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-21 | 初稿：路径 B MVP 开发方案（对应分支 as-b1-path-b-mvp） |
| 2026-10-22 | 拍板回填：#1～#8；增补 §0.1、§5.6、§5.7 |
| 2026-10-22 | 精化 #2/#4/#6/#7/#8 措辞；新增 **§10 MVP 后跟进缺口表** |
| 2026-10-23 | MVP 验收：`1.1.21` 私服 + 本地 AS SEQUENTIAL 冒烟 + 测试环境业务回归；状态更新 |
| 2026-10-23 | §10 G-MCP：链独立[开发方案](./ACE-Graph-DSL-AgentScope-G-MCP-开发方案.md)/[计划](./ACE-Graph-DSL-AgentScope-G-MCP-开发计划.md) |
| 2026-10-23 | §10 G-MCP：宿主 Skill 冒烟通过；MCP 环境阻挡记入方案 §6.1 |
| 2026-10-26 | §10 G-MCP：**MCP 真实调用验收**（`translation`）；状态→能力已验收；下一优先 **G-STREAM-INNER** |
| 2026-10-26 | §10 G-STREAM-INNER：链独立[开发方案](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发方案.md)/[计划](./ACE-Graph-DSL-AgentScope-G-STREAM-INNER-开发计划.md)（初稿，待拍板） |
| 2026-10-26 | §10 G-STREAM-INNER：状态→框架侧主路径已落地；文档对齐 §0.5 / #8 |
