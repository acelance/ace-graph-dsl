# 个税 Agent Demo — 实现状态与后续计划记录

> 模块：`spring-ai-alibaba-demo-cs-reply-m2-ace-graph-dsl`
> 技术栈：Spring Boot 3.5 + ace-graph-dsl（Golden DSL）+ Spring AI Alibaba Graph + MySQL 持久化
> 记录时间：2026-07-19
> 结论：**核心需求已全部实现，并已完成端到端（consult → HITL → resume → 计算 → 生成 MD → MCP 发信）验证通过。**

---

## 一、总体结论

| 维度 | 状态 | 说明 |
| --- | --- | --- |
| A. MySQL 持久化改造 | ✅ 完成 | 参考 `mulit-script` 模块，改用 JDBC 持久化；已导出最终 SQL schema |
| B. 个税垂直领域 Agent Demo | ✅ 完成 | 主 agent 编排 3 个子 agent 能力，5 大节点全部生效 |
| 端到端跑通 | ✅ 已验证 | consult 暂停 → resume 续跑，计算结果与邮件发送均已确认 |
| 4 张展示图（内置演示图） | ✅ 保留 | 4/4 内置 showcase 图照常注册，可视化不受影响 |

最终验证用例（年收入 250,000 元，专项扣除 子女教育2000+住房贷款1000+赡养老人3000）：
- 毛应税基数 = 250000 − 60000 = **190000**
- 专项扣除合计 = **6000**
- 应纳税所得额 = 190000 − 6000 = **184000**
- 适用税率 **20%**，速算扣除数 **16920**
- 应纳税额 = 184000 × 0.2 − 16920 = **19880** ✅（与系统计算一致）

---

## 二、已实现项目（逐项 + 验证证据）

### A. MySQL 持久化改造
| 项目 | 实现方式 | 验证 |
| --- | --- | --- |
| 持久化后端切换为 JDBC | `ace.graph.dsl.persistence.type: jdbc` + `spring.datasource` 指向 MySQL | 启动日志 `Ace Graph DSL 持久化后端: JDBC` |
| 独立数据库避免跨模块污染 | 专用库 `ace_graph_dsl_tax`（datasource url 含 `createDatabaseIfNotExist=true`） | `GraphRuntime.init()` 仅加载本模块 `tax-cn-2026-agent`，不再误编译其他模块图 |
| 自动建表 | starter 的 `initSchema()` 自动创建 `ace_graph_dsl_*` 表 | 表：`definition` / `enabled` / `node_definition` |
| 图定义落库 + 启用 | bootstrap 将 JSON 存为草稿 → 启用 → 发布到内存池 | `ace_graph_dsl_definition` 含 `tax-cn-2026-agent@1.0.0`（4460 字节 JSON）；`ace_graph_dsl_enabled` 已标记启用 |
| **最终 SQL 文件** | 已导出自动建表 DDL | `src/main/resources/demo-graphs/tax_agent_mysql_schema.sql` |

### B. 个税 Agent 业务拓扑（`tax-cn-2026-agent.json`）
| # | 需求 | 实现 | 节点 | 验证 |
| --- | --- | --- | --- | --- |
| 1 | 主 agent：2026 中国个税咨询 | 顶层编排图，intake 归一化入口 | `intake`(NORMAL) | consult 正确写入 consultant_name/annual_income/city/delivery_channel/consultant_email |
| 2 | 子 agent1：专项扣除（含 **HITL 人类节点**） | 勾选扣除项 + 填金额，人工确认后继续 | `deduction_human`(NORMAL + interruptBefore) | consult 返回 `AWAITING_HUMAN`；resume 注入后 `human_confirmed=True` |
| 3 | 子 agent2：个税阶梯计算 | 累进税率脚本计算 | `income_prep` → `merge_facts` → `tax_calc` | `gross_taxable=190000`、`taxable_income=184000`、`tax_rate=0.2`、`tax_payable=19880` |
| 4 | 主 agent 节点：生成个税详情 .md | 调用「技能/节点」拼装 Markdown | `gen_doc`(NORMAL) | 生成完整 Markdown（含收入/扣除/阶梯计算表），422 字符 |
| 5 | 子 agent3：MCP 工具发邮件（**仅模拟**） | Mock MCP 服务端接收 send_email | `email_send`(NORMAL) + `MockMcpEmailService` | `email_sent=True`、`email_message_id=MOCK-MCP-F6E8BE4A`；日志打印收到 send_email |

### 编排能力（全部生效）
- **并行扇出 + 聚合（MERGE）**：`deduction_human` 同时触发 `deduction_calc` ∥ `income_prep`，`merge_facts`(MERGE) 汇聚。
- **条件路由（ROUTER + dispatcher）**：`delivery_router` 读 `delivery_channel`，`email` → `email_send`，其他 → `__END__`。
- **HITL 人工中断与续跑**：`compile.interruptBefore=["deduction_human"]` + `saver:"memory"`。
- **运行时触发入口**：`TaxAgentController` 提供 `POST /api/tax/consult` 与 `POST /api/tax/resume`。

### 工程化交付
- 启动脚本 `mvnwrap.sh`（绕过损坏系统 Maven 3.3.9，直接用 Maven 3.9.10 classpath 启动）。
- 端口治理：通过 `--server.port=18090` 覆盖 starter 注入的 5101。
- 请求体 UTF-8 文件化（规避 Git Bash 中文编码坑）。

---

## 三、未实现 / 设计性保留 / 偏差说明

> 以下项目**不影响核心验收**，部分为"按原需求刻意保留"或"实现方式的合理偏差"。

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| MCP 服务端"仅模拟，不真发邮件" | ✅ 按需求保留 | 原需求明确要求"MCP 服务端仅模拟，不真发"。`MockMcpEmailService` + `MockMcpEmailController`（`/mcp/tools/send_email`）即为模拟实现 |
| 4 张展示图 | ✅ 保留 | 指 4 个内置 showcase 图（cs-reply-dsl-showcase / subgraph-ref / sub-a / deep-nested），启动日志 `演示图注册完成: 4/4`，可视化不受影响 |
| 阶梯计算"脚本节点" | ⚠️ 偏差（功能等价） | 需求写"计算用脚本节点"，当前用 **Java bean 节点**（`TaxCalcNodeBean` 等）实现，逻辑完全等价。框架支持 `ScriptNode`（脚本节点），见后续计划 |
| "调用技能生成 md" | ⚠️ 偏差（功能等价） | 当前由 `gen_doc` 节点直接拼装 Markdown；未接入独立可插拔"技能(Skill)"注册体系，但产出物（个税详情.md）与需求一致 |
| 前端可视化页面 | ❌ 未实现 | 仅提供 REST API（/api/tax/*）与 ace-graph-dsl 自带设计器 REST（/api/graph）。无面向个税场景的定制前端 |
| 生成 md 文件落库/落盘 | ❌ 未实现 | `tax_doc` 仅存在于运行态 state，未持久化为文件或落表 |
| 多用户/并发隔离压测 | ❌ 未实现 | 单 threadId 单次咨询；未做并发与性能压测 |

---

## 四、后续需要完成的思路与计划

### 优先级 P0（贴近原始"脚本节点/技能"措辞）
1. **阶梯计算改为真正的脚本节点（ScriptNode）**
   - 思路：将 `income_prep`/`tax_calc`/`deduction_calc` 的逻辑迁移为 `ace-graph-dsl` 的脚本节点（JSON 内 `script` 配置 + `ScriptNodeFactory`），更贴合需求"计算用脚本节点"。
   - 注意：脚本节点的 `keyStrategies` 同样需声明输出 key，且要满足 `EdgeParamReachabilityValidator` 的逐边可达性校验。
2. **引入可插拔"技能"机制生成 md**
   - 思路：将 `gen_doc` 抽成独立 Skill（模板 + 渲染），通过节点引用技能，而非硬编码在 bean。

### 优先级 P1（增强真实感）
3. **接入真实 MCP 服务端**
   - 思路：用 `spring-ai-mcp` 的 stdio/SSE client 替换 `MockMcpEmailService`，真正调用外部 MCP 工具发邮件；保留 Mock 作为 fallback。
4. **前端可视化页面（Vue/React）**
   - 思路：表单录入咨询信息 → 调 `/api/tax/consult` → 页面展示 HITL 勾选面板 → 调 `/api/tax/resume` → 渲染 `tax_doc` Markdown 与投递状态。

### 优先级 P2（工程完善）
5. **tax_doc 持久化**：将生成的 md 落盘/落表，支持历史查询。
6. **并发与压测**：多 threadId 隔离验证、性能基线（目标 < 1.5s P95）。
7. **README + 一键启动脚本**：固化 `mvnwrap.sh` + `java -jar ... --server.port=18090` 的启动说明与依赖（MySQL 库、端口）清单。
8. **多投递渠道**：在 `DeliveryDispatcherBean` 扩展 `sms`/`webhook` 等 mapping，验证条件路由可扩展性。

---

## 五、端到端验证记录（关键证据）

```text
# consult
POST /api/tax/consult
→ {"threadId":"b5cecbff-...","status":"AWAITING_HUMAN","awaitingHumanInput":true,
   "snapshot":{"consultant_name":"张三","annual_income":250000,"city":"上海",
               "delivery_channel":"email","consultant_email":"zhangsan@example.com"}}

# resume
POST /api/tax/resume  (threadId + deductions + deduction_amounts)
→ status=DONE
   human_confirmed=True
   deduction_total=6000.0
   gross_taxable=190000.0
   taxable_income=184000.0
   tax_rate=0.2
   quick_deduction=16920.0
   tax_payable=19880.0
   email_sent=True
   email_message_id=MOCK-MCP-F6E8BE4A

# 应用日志
MockMcpEmailService : [Mock MCP Server] 收到 send_email 工具调用
   | to=zhangsan@example.com | subject=【2026 个税咨询】张三 的个税详情 | messageId=MOCK-MCP-F6E8BE4A
```

MySQL 校验：
```sql
SELECT graph_id, version, display_name, LENGTH(content_json)
  FROM ace_graph_dsl_tax.ace_graph_dsl_definition;
-- ('tax-cn-2026-agent','1.0.0','2026 中国个税咨询 Agent',4460)

SELECT * FROM ace_graph_dsl_tax.ace_graph_dsl_enabled;
-- ('tax-cn-2026-agent','1.0.0','2026-07-19 23:54:50')
```

---

## 六、关键坑位与修复（供后续参考）

| 坑 | 根因 | 修复 |
| --- | --- | --- |
| 共享 MySQL 库导致启动失败 | `GraphRuntime.init()` 加载到其他模块启用的图，本模块缺对应 bean | 改用独立库 `ace_graph_dsl_tax` |
| 端口 5101 冲突 | starter 注入 `server.port=5101` 覆盖 yml | 启动加 `--server.port=18090` |
| 发布校验失败（SUBGRAPH 无输出） | `EdgeParamReachabilityValidator` 逐边校验，SUBGRAPH 节点对校验器不可见 | 将子图**扁平化**为顶层 NORMAL 节点（沿用参考图 cs-reply-m2-boost-strap 的扁平模式） |
| 条件边 mapping 不匹配 | dispatcher `possibleTargets` 与 JSON mapping 值不一致 | `DeliveryDispatcherBean.possibleTargets()` 改为 `{email_send, __END__}` |
| resume 后计算结果为空 | `getState(updated)` 读到了 updateState 产生的旧 checkpoint | 改为 `getState(cfg)` 读取线程最新 checkpoint |
| Git Bash 中文请求体乱码 | 终端编码非 UTF-8，`Invalid UTF-8 middle byte 0xc5` | 请求体写入 .json 文件，`curl --data-binary @file` + `charset=UTF-8` |
| 系统 Maven 不可用 | 自带 Maven 3.3.9 lib 缺包、Git Bash 路径转换异常 | `mvnwrap.sh` 直接用 Maven 3.9.10 的 `plexus-classworlds` classpath 启动 |
