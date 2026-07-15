# 多脚本引擎 — 测试联调方案

> 对应：[MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md)  
> 版本：v1.1  
> 日期：2026-07-15  
> 范围：后端自动化（L1–L3）+ 设计器手工联调清单（L4）

### 进度快照（2026-07-15）

| 层级 | 状态 | 说明 |
|------|------|------|
| L1–L3 自动化 | ✅ 已通过 | `mvn test` 相关模块全绿 |
| L4 P0 | ✅ 已完成 | M-01～M-08 均已通过（含 M-07） |
| L4 P1/P2 | ⏳ 部分 | M-09 已完成（由 M-01 覆盖）；M-10～M-14 未开始 |

---

## 1. 目标与门禁

| 层级 | 内容 | 门禁 | 当前 |
|------|------|------|------|
| L1 | 引擎 SPI 单测（Aviator / SpEL / QLExpress / Groovy） | `mvn test` 相关模块全绿 | ✅ |
| L2 | 服务层 / 条件边工厂 / GraphValidator 多引擎 | 同上 | ✅ |
| L3 | Spring 条件装配（开关 + optional 模块） | starter ContextRunner 全绿 | ✅ |
| L4 | 设计器手工联调 | P0 清单全部勾选通过 | ✅ P0 完成；P1/P2 可选 |

**发布建议**：L1–L3 与 L4 P0 已通过；L4 P1/P2（Groovy 等）按需补测。

---

## 2. 环境

| 项 | 要求 |
|----|------|
| JDK | 17+（本机建议 JDK 21） |
| Maven | 3.9.x |
| 前端手测 | Vue3 宿主 + 设计器；后端 starter 已接入 |
| QLExpress | classpath 含 `ace-graph-dsl-script-qlexpress` |
| Groovy | classpath 含 `ace-graph-dsl-script-groovy` 且 `groovy-enabled=true` |

### 关键配置

```yaml
ace.graph.dsl.script:
  enabled: true
  default-engine: aviator
  execution-timeout-ms: 500
  spel-enabled: true
  qlexpress-enabled: true
  groovy-enabled: false          # 手测 Groovy 时改为 true
```

### 自动化命令

工作目录：`ace-graph-dsl-backend`

```powershell
$env:JAVA_HOME = "D:\JDK\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "D:\Apache\apache-maven-3.9.9\bin\mvn.cmd" `
  -s "D:\Apache\apache-maven-3.9.9\conf\settings.xml" `
  -Dmaven.repo.local="D:\Apache\apache-maven-3.9.9\repository" `
  -pl ace-graph-dsl-core,ace-graph-dsl-script-qlexpress,ace-graph-dsl-script-groovy,ace-graph-dsl-spring-boot-starter `
  -am test
```

---

## 3. 自动化矩阵

| ID | 层级 | 模块 | 测试类 / 用例 | 期望 | 结果 |
|----|------|------|---------------|------|------|
| A-01 | L1 | core | `AviatorScriptEngineTest` | validate / execute / Map 出参 | ✅ |
| A-02 | L1 | core | `SpelScriptEngineTest` | Map/标量、禁 `T()`、超时 | ✅ |
| A-03 | L1 | qlexpress | `QlExpressScriptEngineTest` | 多行 Map、禁 import、超时 | ✅ |
| A-04 | L1 | groovy | `GroovySandboxScriptEngineTest` | 集合、沙箱拦截、超时 | ✅ |
| A-05 | L1 | core | `ScriptEngineRegistryTest` | supports / listDescriptors | ✅ |
| B-01 | L2 | core | `ScriptNodeServiceTest` | 空脚本/超限/未知引擎/Aviator+SpEL 试跑/inputKeys 裁剪/校验失败不落库 | ✅ |
| B-02 | L2 | core | `ScriptEdgeActionFactoryTest` | Aviator + SpEL 条件边路由 | ✅ |
| B-03 | L2 | qlexpress | `ScriptEdgeActionFactoryQlExpressTest` | `conditionEngine=qlexpress` | ✅ |
| B-04 | L2 | groovy | `ScriptEdgeActionFactoryGroovyTest` | `conditionEngine=groovy` | ✅ |
| B-05 | L2 | core | `GraphValidatorTest` | `conditionEngine=spel` 通过；未知引擎失败 | ✅ |
| C-01 | L3 | starter | `AceGraphDslScriptEngineAutoConfigurationTest` | 默认含 aviator/spel/qlexpress；默认无 groovy；开启后有 groovy | ✅ |

---

## 4. 手工联调清单（L4）

### 4.1 P0（必须）

| ID | 步骤 | 期望 | 结果 |
|----|------|------|------|
| M-01 | 打开「新建脚本节点」，查看引擎下拉 | 含 Aviator、SpEL；有 QLExpress 模块时含 qlexpress；**默认无** Groovy | [x] 2026-07-15：`groovy-enabled=false` 下无 Groovy，有 Aviator/QLExpress/SpEL |
| M-02 | 选 SpEL，粘贴 `{'k': #state['query']?.trim()}`，Mock `{"query":"  a  "}`，试跑 | 返回 `k=a` | [x] 2026-07-15：试跑成功，`{"k":"a"}` |
| M-03 | SpEL 试跑 `T(java.lang.Runtime)` | 校验/试跑失败，提示安全拒绝 | [x] 2026-07-15：提示「SpEL 禁止引用类型: java.lang.Runtime」 |
| M-04 | 选 QLExpress，多行 VIP 折扣样例（见 SCRIPT_NODE_EXAMPLES），试跑 | `final_price` 正确 | [x] 2026-07-15：Input Keys=`amount,discount,member`，`member=normal` → `final_price=90`；过程中完善了 Mock/Input Keys 错配的友好报错 |
| M-05 | 切换 QLExpress 后脚本区行数变大（约 14） | hint 文案变化 | [x] 2026-07-15：多行编辑区 + hint「支持多行 if/else; return map(...)」 |
| M-06 | 条件边：`conditionEngine=spel`，表达式 `#state['score'] > 60 ? 'pass' : 'fail'`，mapping 齐全后校验/发布 | 通过 | [x] 2026-07-15：样本图 dry-run，`raw_score=60` → pass（画布表达式为 `>= 60`，仍验证 SpEL 条件边）；mapping 及格/不及格盖章正确 |
| M-07 | 条件边改未知引擎（手工改 DSL JSON）后校验 | 报引擎不可用 | [x] 2026-07-15：用户确认完成——导入不报错、试运行报引擎不可用；「应用」前端拦截已验证符合预期 |
| M-08 | 创建脚本节点 → 拖入画布 → 保存草稿 → 校验 → 发布 | 热更新成功，无强制重启 | [x] 2026-07-15：全脚本图导入/编排后试运行贯通至 `script:exam_merge_result`，产出 `final_status` 等；确认已「保存草稿→校验→发布」即可 |

### 4.2 P1（Groovy / 开关）

| ID | 步骤 | 期望 | 结果 |
|----|------|------|------|
| M-09 | 未开 `groovy-enabled` 时 `GET .../nodes/engines` | 无 `groovy` | [x] 2026-07-15：与 M-01 同场景，设计器下拉无 Groovy；底层同为 `/nodes/engines`，C-01 亦覆盖默认无 groovy |
| M-10 | 引入 groovy 模块并设 `groovy-enabled=true`，刷新引擎列表 | 出现 Groovy，`multiLine=true` | [ ] |
| M-11 | Groovy 集合分组样例试跑 | `group_counts` 正确 | [ ] |
| M-12 | Groovy 试跑 `System.exit(0)` / `Runtime.getRuntime()` | 拦截失败 | [ ] |

### 4.3 P2（体验）

| ID | 步骤 | 期望 | 结果 |
|----|------|------|------|
| M-13 | 切换引擎时已有脚本体 | （当前无确认框）脚本保留或被覆盖行为可接受 | [ ] |
| M-14 | 条件边切换引擎后保存草稿再打开 | `conditionEngine` 回显正确 | [ ] |

---

## 5. 缺陷分级

| 级 | 定义 | 处理 |
|----|------|------|
| P0 | 默认链路不可用 / SpEL 安全绕过 / 发布错配引擎 | 阻塞发版 |
| P1 | 可选引擎装配错误、超时未生效、沙箱漏拦 | 发版前修复 |
| P2 | 文档/UX/测试覆盖不足 | 可排期 |

### 联调中已处理

| 项 | 说明 | 状态 |
|----|------|------|
| 试跑错误不友好 | Mock State 字段不在 Input Keys 时原只有 QLExpress 底层异常；已在 `ScriptNodeService` 前置可读提示 | ✅ 已修 |
| 条件边「应用」不校验引擎 | 导入 `no-such-engine` 后点「应用」静默成功；试运行才报错。已在 `PropertyPanel.applyEdgeEdit` 按 `/nodes/engines` 拦截 | ✅ 已修 |

---

## 6. 残余与后续

- L4 P0 已完成；P1 中 M-09 已由 M-01 覆盖勾选；可选余项 M-10～M-14
- 「应用」边配置：已补未知 `conditionEngine` 前端拦截（与试运行/校验语义对齐）
- MockMvc：`ScriptNodeController` validate / test-run / engines HTTP
- 四引擎同图端到端（`DynamicGraphBuilder` + MemorySaver）
- 切换引擎 `ElMessageBox.confirm`
- Groovy 运维开启独立 checklist

---

## 7. 相关文档

| 文档 | 说明 |
|------|------|
| [MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md) | 实施与分包方案 |
| [SCRIPT_NODE_EXAMPLES.md](./SCRIPT_NODE_EXAMPLES.md) | 可复制样例 |
| [FUTURE_OPTIMIZATION_PLAN.md](./FUTURE_OPTIMIZATION_PLAN.md) §7.2 / §8.1 | 进度总表 |
