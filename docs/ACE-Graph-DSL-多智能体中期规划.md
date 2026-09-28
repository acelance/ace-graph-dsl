# ACE Graph DSL 多智能体中期规划计划书

> 文档类型：产品中期规划  
> 状态：草案  
> 日期：2026-09-18  
> 前置文档：[ACE-Graph-DSL-多智能体内核选型.md](./ACE-Graph-DSL-多智能体内核选型.md)  
> 配套文档：[ACE-Graph-DSL-多智能体初步技术方案.md](./ACE-Graph-DSL-多智能体初步技术方案.md)

---

## 1. 规划结论

中期目标采用三层结构，职责不交叉：

```text
ACE Graph DSL（图 + 设计器）          ← 图资产、版本、发布、注册目录
    └─ 高阶模式节点                    ← SAA Agent Framework
         Sequential / Parallel / Routing / Loop
            └─ 子 Agent 实现
                 GENERIC_AGENT（默认）
                 AgentScope ReActAgent（可选）
```

| 层 | 负责人 | 中期必须交付 | 不做 |
|----|--------|--------------|------|
| ACE Graph DSL | 平台 | 继续作为唯一主编排与资产载体 | 不用 Framework 替换整张业务图 |
| SAA Framework | 平台 | 高阶模式节点：顺序、并行、路由、循环 | 不另做第二套设计器 |
| AgentScope | 平台（可选能力） | 作为子 Agent 实现之一接入 | 不做主编排，不替换图边 |

一句话：**图管拓扑与资产，Framework 管节点内多智能体模式，AgentScope 只增强子 Agent。**

---

## 2. 背景

### 2.1 现状

ACE Graph DSL 已具备：

- `spring-ai-alibaba-graph-core` 运行时（`StateGraph` → `CompiledGraph`）
- JSON 图资产：校验、版本、发布、试运行、执行轨迹
- 设计器：拖拽、条件边、图级并行（`FanOutNodeAction`）、子图
- 注册式 `GENERIC_AGENT`：Prompt / Model / MCP / Skill / Memory 按 key 挂载

ACE 目前**没有** SAA Agent Framework 的 `SequentialAgent`、`ParallelAgent`、`RoutingAgent`、`LoopAgent`。现有「多 Agent」是多个 GenericAgent 用边连起来，属于图级原语，不是成套多智能体模式。

### 2.2 为什么中期要补这一层

| 问题 | 影响 |
|------|------|
| 复杂协作只能画很多边 | 设计器图变大，业务同学难复用「顺序 / 并行 / 路由 / 循环」这类固定模式 |
| 工具循环自建在 GenericAgent 内 | 与社区标准 React / FlowAgent 不对齐，后续观测与评测要对两套语义 |
| 竞品话术已包含 Framework 模式 | 对外容易被理解成「只有画布、没有多智能体编排」 |
| Agent 运行时深度有上限 | 沙箱、A2A、更强 ReAct 等需求出现时，没有预留子 Agent 插槽 |

中期不追求「全面替换 GenericAgent」，而是在图上增加**可选的高阶节点**，子 Agent 默认仍引用现有 GenericAgent。

---

## 3. 目标与非目标

### 3.1 目标

1. 设计器可拖入高阶模式节点，并配置 Sequential / Parallel / Routing / Loop。
2. 高阶节点的子 Agent **默认引用**已注册的 `GENERIC_AGENT`，复用平台目录（模型、Prompt、MCP、Skill）。
3. 同一高阶节点可选挂载 **AgentScope `ReActAgent`** 作为子 Agent 实现，不改变图的主编排语义。
4. 旧图（纯 GenericAgent + 边）零迁移、行为不变。
5. 试运行能看到：进入高阶节点 → 子步骤摘要 → 写回 `outputKey`。

### 3.2 非目标（中期明确不做）

- 不用 AgentScope Pipeline / MsgHub / Debate 替换 ACE 图边。
- 不把现网图（如 `ztc-service-agent`）整图收成一个 SequentialAgent。
- 不新建第二套 MCP / Skill / Prompt 目录。
- 不在中期交付 Supervisor、多智能体辩论、分布式 A2A 作为必达项。
- 不重写 `StreamingLlmTemplate`；GenericAgent 与高阶节点并存。

---

## 4. 用户与场景

| 角色 | 中期能做什么 |
|------|----------------|
| 业务编排人员 | 在设计器里用一个节点表达「先生成再评分」「多角度并行再汇总」「按意图路由」「循环直到达标」 |
| 平台研发 | 子 Agent 仍走注册目录；需要更强 ReAct 时切换实现为 AgentScope，而不改图拓扑 |
| 垂直业务（如 ztc） | 跨阶段流程继续用图边；仅在单阶段内部需要固定协作模式时嵌入高阶节点 |

典型场景：

1. **顺序**：自然语言 → SQL 生成 Agent → SQL 评分 Agent，封装为一个节点。
2. **并行**：同一问题分给多个业务 Agent，汇总后再回到图的下一跳。
3. **路由**：意图分类后只调用一个或一组专家 Agent。
4. **循环**：生成 → 评分，未达阈值则重复，有最大轮次。

图级的「意图节点 → 业务节点 → 输出节点」仍用现有边，不强制改成 Framework。

---

## 5. 范围

### 5.1 产品范围

| 能力 | 中期 | 说明 |
|------|------|------|
| 高阶节点 Sequential | 必达 | 子 Agent 有序执行，状态键向后传递 |
| 高阶节点 Parallel | 必达 | 子 Agent 并行，结果汇入约定键 |
| 高阶节点 Routing | 必达 | LLM 或规则路由到子 Agent |
| 高阶节点 Loop | 必达 | 退出条件 + 最大轮次 |
| 子 Agent = GENERIC_AGENT 引用 | 必达 | `ref: generic:{nodeId}` |
| 子 Agent = AgentScope ReActAgent | 必达（可选开关） | 未启用模块时校验失败信息明确，不影响旧图 |
| 设计器属性面板 | 必达 | pattern、子 Agent 列表、输入输出键、循环条件 |
| 试运行轨迹摘要 | 必达 | 至少节点级 + 子步骤名 |
| Supervisor / Handoff / Debate | 远期 | 仅预留 `pattern` 扩展位 |
| 模式模板市场 | 远期 | 中期只提供 4 个内置示例 JSON |

### 5.2 技术范围边界

- 新能力放在可选模块，不把 `spring-ai-alibaba-agent-framework`、`agentscope-core` 设为 `ace-graph-dsl-core` 的强制依赖。
- 版本与现有 `spring-ai-alibaba-graph-core` **同一 BOM**。
- AgentScope 只通过适配器实现「子 Agent」接口，不直接出现在图的边模型里。

---

## 6. 分期计划

中期拆成四个阶段。前一阶段验收通过再开下一阶段，避免同时铺开 Framework 与 AgentScope。

### M1 · 内核打通（React + 顺序）

**目标：** 证明高阶节点能编译、能跑、旧图不受影响。

| 项 | 内容 |
|----|------|
| 交付 | `SAA_WORKFLOW` 节点；`pattern=SEQUENTIAL`；子 Agent 仅引用 GenericAgent |
| 运行时 | 可选模块 `ace-graph-dsl-saa-agent` |
| 设计器 | 可拖入节点、编辑子 Agent 引用与 input/output key |
| 验证 | 一条顺序样例图试运行通过；关闭模块时旧图编译成功 |
| 不做 | Parallel、Routing、Loop、AgentScope |

**退出标准：** 样例图输出写入约定 `outputKey`；执行轨迹能看到两个子步骤；无 Framework 依赖的构建仍通过。

### M2 · 模式补齐（并行 / 路由 / 循环）

**目标：** 四种高阶模式都可配置、可校验、可试运行。

| 项 | 内容 |
|----|------|
| 交付 | `PARALLEL`、`ROUTING`、`LOOP` |
| 设计器 | 按 pattern 切换表单（并行无顺序、路由要候选集、循环要条件与 maxIterations） |
| 校验 | 子 Agent 引用存在性、键冲突、循环缺少退出条件即校验失败 |
| 验证 | 每种模式一条样例图 |

**退出标准：** 四种 pattern 均有自动化测试；设计器保存的 JSON 可被后端原样编译。

### M3 · AgentScope 子 Agent

**目标：** 同一高阶节点的子 Agent 实现可切换，图结构不变。

| 项 | 内容 |
|----|------|
| 交付 | `impl=GENERIC_AGENT \| AGENTSCOPE` |
| 运行时 | 可选模块或 starter：`AgentScope ReActAgent` → 子 Agent 适配器 |
| 桥接 | 优先评估官方 `spring-ai-alibaba-starter-agentscope` / `AgentScopeAgent`，避免自研第二套消息协议 |
| 资源 | 模型、MCP、Skill 仍从 ACE 目录注入，不新建 AgentScope 资源中心 |
| 验证 | 同一 Sequential 样例，切换 impl 后主输出键一致（允许文案差异，结构键必须一致） |

**退出标准：** 未引入 AgentScope 时，`impl=AGENTSCOPE` 给出明确错误；引入后样例可跑通；GenericAgent 路径回归通过。

### M4 · 可运营（轨迹、模板、文档）

**目标：** 业务可独立使用，不必读 Framework 源码。

| 项 | 内容 |
|----|------|
| 交付 | 试运行子步骤摘要；4 个示例图；设计器内简短说明 |
| 观测 | 高阶节点 span 下挂子 Agent span（能接现有 Langfuse / 日志即可，不单独立项） |
| 培训 | 一页「何时用图边、何时用高阶节点」 |
| 不做 | 模式市场、可视化子图展开编辑（可列为远期） |

**退出标准：** 非作者按文档能在设计器搭出一条 Routing 或 Loop 样例并试运行成功。

---

## 7. 里程碑依赖

```text
M1 顺序 + GenericAgent 子引用
        │
        ├─► M2 四种 pattern
        │         │
        │         └─► M4 轨迹 / 模板 / 培训材料
        │
        └─► M3 AgentScope 子 Agent（可与 M2 后半并行，但不阻塞 M2）
```

M3 依赖 M1 的子 Agent 接口稳定，不依赖 M2 四种模式全部完成。建议 M2 的 Sequential 稳定后再切 AgentScope，减少双变量排障。

---

## 8. 验收标准（中期整体）

| 编号 | 标准 |
|------|------|
| AC-1 | 旧图 JSON 无需修改即可编译执行 |
| AC-2 | 设计器可配置 Sequential / Parallel / Routing / Loop，保存后可发布 |
| AC-3 | 子 Agent 默认引用 GenericAgent，MCP / Skill key 与单节点行为一致 |
| AC-4 | AgentScope 为可选 impl；关闭依赖时产品可构建、可运行 |
| AC-5 | 试运行轨迹能区分高阶节点与其子步骤 |
| AC-6 | 图边仍是跨阶段编排的唯一主模型；文档与界面文案不把 AgentScope 写成主编排 |
| AC-7 | Framework、graph-core、AgentScope 桥接版本锁定在同一 BOM，构建无冲突 |

---

## 9. 风险与对策

| 风险 | 级别 | 对策 |
|------|------|------|
| Framework 与 graph-core 版本错位 | 高 | 只通过现有 SAA BOM 引入，禁止单独写死冲突版本 |
| FlowAgent 无法导出 `CompiledGraph`，轨迹变粗 | 中 | M1 先做 NodeAction 适配；导出能力作为增强，不阻塞模式交付 |
| AgentScope 与 OverAllState 双状态 | 高 | M3 只允许适配器边界做 Msg ↔ state key 转换，禁止业务图直接依赖 Msg |
| 与图级 FanOut / 条件边概念混淆 | 中 | 设计器文案固定：「图级并行/条件」与「节点内模式」分开展示 |
| 流式 BIZ/OUTPUT、MCP session 在子 Agent 中丢失 | 高 | M1 即把 streamResponseKind、MCP session 列入必测项 |
| 范围膨胀到 Supervisor / Debate | 中 | 中期 pattern 枚举只开放四个；其余值校验拒绝 |

---

## 10. 协同与前置条件

| 前置 | 说明 |
|------|------|
| BOM | 确认 `spring-ai-alibaba` 版本同时包含 graph-core 与 agent-framework |
| 设计器扩展 | `NodePanel` 增加节点类型，不改画布引擎 |
| 平台目录 | GenericAgent 注册查询 API 可被高阶节点解析 `generic:{id}` |
| 垂直试点 | 选一条非核心图做试点，**不**首期改造 `ztc-service-agent` 主链路 |
| 选型约束 | 以《多智能体内核选型》为准：Framework 是模式库，AgentScope 是子 Agent |

---

## 11. 成功之后的产品口径

对外可以这样说：

> ACE Graph DSL 负责可发布的图资产与设计器。节点内的顺序、并行、路由、循环由 Spring AI Alibaba Agent Framework 提供。子 Agent 默认复用平台 GenericAgent，需要更强推理运行时时可选用 AgentScope ReActAgent。

对内评审口径：

> 中期补的是高阶节点，不是换编排内核。图边、版本、注册目录保持不变。
