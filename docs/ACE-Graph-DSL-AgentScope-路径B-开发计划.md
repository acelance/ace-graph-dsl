# ACE Graph DSL × AgentScope：路径 B MVP — 开发计划

| 项 | 内容 |
|----|------|
| 状态 | **框架实现中**（M1-D0～D3 已绿；宿主 M1-H 待跟） |
| 日期 | 2026-10-21 |
| 分支 | `feature/as-b1-path-b-mvp` |
| 方案 | [ACE-Graph-DSL-AgentScope-路径B-开发方案.md](./ACE-Graph-DSL-AgentScope-路径B-开发方案.md) |
| 阶段 | 分阶段规划 **阶段 1**；估时 **约 8～15 人日**（框架为主） |
| 下一阶段 | 合入发版后 → `feature/as-h0-harness-entry`（**另 Issue**，本计划不包含） |

---

## 1. 里程碑

| 里程碑 | 目标 | 建议人日 | 退出标准 |
|--------|------|----------|----------|
| **M1-D0** | 依赖探针 + 钉版本 | 1～2 | `agentscope-agent` 模块在 AS 2.x 下 **compile** 通过；tree 无 1.0.9 |
| **M1-D1** | 自研 BaseAgent Adapter + Resolver 切换 | 3～5 | 单测绿；不再引用 `AgentScopeAgent` |
| **M1-D2** | ModelFactory / 自动配置 / 能力探测文案 | 1～2 | 模型解析与 bridge 标识正确 |
| **M1-D3** | 集成测试 + testdata 冒烟 | 1～2 | SEQUENTIAL AGENTSCOPE 用例绿 |
| **M1-D4** | 文档 / 附录 A / CHANGELOG | 0.5～1 | README、附录、FAQ 回填 |
| **M1-H** | 宿主跟版（可并行末段） | 1～2 | 宿主编译 + capabilities + 样例跑通 |
| **缓冲** | 冲突回归 / Code Review | 1～2 | PR 可合 |

**合计**：约 **8～15 人日**。

---

## 2. 任务分解（可勾选）

### 2.1 M1-D0 — 依赖

- [x] 父 POM 增加 `agentscope.version`，**钉死 `2.0.4`**（2026-10-22 拍板）
- [x] `ace-graph-dsl-agentscope-agent/pom.xml`：移除 `spring-ai-alibaba-starter-agentscope`
- [x] 增加 `agentscope-core` + `agentscope-extensions-model-openai`（版本对齐）
- [x] **禁止**引入 `agentscope-spring-boot-starter`、`agentscope-harness`
- [x] `mvn dependency:tree` 确认无 `agentscope-core:1.0.9` / 无 starter-agentscope
- [x] 记录冲突与钉法（见下「依赖备注」）
- [x] **以 2.0.4 实物 jar/Javadoc 为准**核对包名/类名
- [x] compile 通过
- [x] 实物差异回填方案 §10 **G-API-DOC**

**依赖备注：**

```text
agentscope.version = 2.0.4
BRIDGE / agentscopeBridge 实际取值 =
  ace-graph-dsl-agentscope-agent@io.agentscope:agentscope-core:2.0.4
冲突与钉版本：tree 未见表层 Reactor/Jackson 冲突（沿用 Boot 3.5 / SAA 1.1.x）
实物 API 与方案差异：
  - OpenAIChatModel → io.agentscope.extensions.model.openai
  - ReActAgent.Builder 无 memory()；不配 LongTermMemory（Q5）
  - call 返回 Mono<Msg>，Adapter 内 .block()（MVP）
```

### 2.2 M1-D1 — Adapter + Resolver

- [x] 新增 `AceAgentscopeReActBaseAgentAdapter`（extends `BaseAgent`）
  - [x] instruction 模板解析
  - [x] 调用 AS **2.0.4 实物** `ReActAgent.call`
  - [x] 结果写入 `outputKey`（MVP：无 MCP）
  - [x] 关键日志：开始 / 成功 / 失败
- [x] 改造 `AgentScopeSubAgentResolver`：去掉 `AgentScopeAgent`，改用自研 Adapter
- [x] 保持 Q5：`READ_WRITE` 拒绝；短记忆不写 remote
- [x] Adapter 单元测试（占位符、写回）

### 2.3 M1-D2 — Model / 配置 / 探测

- [x] `DefaultAgentScopeModelFactory`：按 2.0.4 实物修正 import；明确坐标
- [x] MVP 内层 `stream(false)`；方案 §10 **G-STREAM-INNER** 已登记
- [x] `AceGraphDslAgentscopeAgentAutoConfiguration` 注释
- [x] `SaaCapabilityController`（webflux + starter）：`agentscopeBridge` 明确坐标
- [x] `AutoConfiguration.imports` 仍加载

### 2.4 M1-D3 — 集成与回归

- [x] `AgentScopeSequentialFactoryIntegrationTest` 绿
- [x] stub 模型写回 sql/score 对拍
- [x] 回归：`ace-graph-dsl-saa-agent` 既有 GENERIC 子 Agent 测试（6 tests 绿）
- [ ] **外层业务 SSE 抽测不回归**（拍板 #7；宿主联调时）
- [ ] （可选）宿主本地拉 SNAPSHOT 跑 `capabilities/saa`

**推荐命令（ace-graph-dsl 非 Lesso 私服场景按本机 JDK21 + Maven）：**

```powershell
$env:JAVA_HOME = "D:\JDK\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
# 在 ace-graph-dsl-backend 目录
mvn -pl ace-graph-dsl-agentscope-agent -am "-Dtest=AgentScopeSequentialFactoryIntegrationTest,AceAgentscopeReActBaseAgentAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### 2.5 M1-D4 — 文档与合规

- [x] 更新 `ace-graph-dsl-agentscope-agent/README.md`（路径 B）
- [x] 附录 A：兜底 → **是**；原因已写；**A.6** 新增
- [x] FAQ §12 / Q4 增补路径 B 现行说明
- [x] 根 `CHANGELOG.md` 条目（Unreleased）
- [x] 链到分阶段规划 / Harness 边界（方案/计划已链）
- [ ] PR 描述附 **方案 §10 缺口表**（G-MCP / G-STREAM-INNER）并开或挂起跟进 Issue

### 2.6 M1-H — 宿主跟版（可另 PR）

- [x] 分支 `feature-agentscope-B-MVP`（宿主仓）
- [x] 删除 `agentscope-core:1.0.9` 显式钉死；`lesso-ai-bom.agentscope.version` → **2.0.4**
- [x] 本地 install 路径 B 框架；host tree 已见 `agentscope-core:2.0.4`（无 1.0.9）
- [x] 更新 `ace-graph-agentscope-host.md`
- [x] 框架 **`1.1.21`** deploy mesrelease（路径 B）；宿主依赖已改 `1.1.21`
- [x] 本地冒烟：`agentscopeEnabled=true`、AGENTSCOPE SEQUENTIAL 写回、`agentscopeBridge` 含 `2.0.4`
- [ ] 测试环境：宿主镜像跟 `1.1.21` 后复查 bridge / 无 `OpenAIChatModel` CNFE
- [ ] 外层业务流式 + GENERIC 图抽测不回归

---

## 3. 建议日程（1 人参考）

| 日序 | 内容 |
|------|------|
| D1 | M1-D0 + 启动 M1-D1（compile 通；对照 2.0.4 实物） |
| D2～D3 | M1-D1 完成 + 单测 |
| D4 | M1-D2 |
| D5 | M1-D3 |
| D6 | M1-D4 + 缺口表落 PR + 开宿主 PR |
| D7～D8 | 宿主冒烟、修依赖冲突、CR、合入 |

若依赖冲突严重：D1～D2 专啃 tree，总人日靠向 **15**。

---

## 4. PR / 合入检查清单

### 框架 PR

- [ ] 标题含：`as-b1-path-b-mvp` / 路径 B  
- [ ] 描述贴本计划任务勾选结果  
- [ ] 无 `starter-agentscope`、无 `agentscope-harness`、无 Boot starter  
- [ ] 测试命令与结果粘贴  
- [ ] 附录 A / CHANGELOG 已改  
- [ ] **附方案 §10 未做缺口**（至少 G-MCP、G-STREAM-INNER）  
- [ ] **不**包含 H0/H1/设计器改动；**不**把内层非流式写成最终设计  

### 宿主 PR（若拆）

- [ ] 依赖框架 **已发布或约定 SNAPSHOT**  
- [ ] 无 1.0.9 钉死  
- [ ] 冒烟清单打勾；外层 SSE 抽测通过  

### 合入后

- [ ] 打框架版本 tag / 私服发布  
- [ ] 关闭本 Issue；**开/挂起** G-MCP、G-STREAM-INNER 跟进 Issue  
- [ ] 可选：下一阶段 Issue `as-h0-harness-entry`（非本计划强制）  

---

## 5. 验收标准（计划级）

与开发方案 §9 / Issue 一致：

1. 运行时桥为 **自研 Adapter + agentscope-core:2.0.4**（官方无 starter 2.x）  
2. `impl=AGENTSCOPE` 样例 **写回成功**（MVP 无 MCP）  
3. GENERIC SAA **回归通过**；**外层业务 SSE 保持流式**  
4. 文档与附录 A **已回填**；`agentscopeBridge` 为明确坐标  
5. 关键路径 **有日志**  
6. PR 已挂 **方案 §10 缺口跟进**（MVP ≠ 完成）  

---

## 6. 明确不做 / 本阶段暂缓（防遗忘）

| 项 | 归属 | 备注 |
|----|------|------|
| 真实 MCP / 工具挂载（G-MCP） | MVP 后跟进 | 方案 §10 |
| 内层子 Agent 流式（G-STREAM-INNER） | MVP 后优先 | 禁止永久 `stream(false)` |
| HarnessAgent / Subagent / Plan（G-HARNESS） | 阶段 2+ | |
| Memory 压缩进 ACE | 搁置 | |
| Boot 4 / SAA 2.0 | 另立项 | |
| 替换 ACE 图为 AS Workflow | 禁止 | |

---

## 7. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-10-21 | 初稿：路径 B MVP 开发计划（分支 as-b1-path-b-mvp） |
| 2026-10-22 | 对齐拍板 #2/#4/#6/#7/#8；任务与验收挂钩方案 §10 缺口表 |
