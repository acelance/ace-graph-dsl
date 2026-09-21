# 设计期资源目录：两个入口与浏览参数

> 状态：**定案（2026-09-21），代码未改。**  
> 现网 SPI 仍是 `AgentResourceCatalog.list(agentCode, graphId, agentDefId)`，查询参数还没有 `bizKey`。  
> 权威补充：本文。总方案原节 [`streaming-llm-node-template-design.md`](streaming-llm-node-template-design.md) §7.2 / §7.3。  
> 业务侧排期与缺口：`lesso-ai-platform-agent-server/docs/designer-resource-option-dev-plan.md`、`designer-resource-option-tag-proposal.md`。

---

## 1. 结论

注册式通用 Agent 是独立资源，图只按 `nodeId` 引用。设计期拉 MCP / Skill / Prompt / Model 候选时，**不要用「当前打开的图」充当节点的资源范围**。

框架只提供两个可空的浏览参数，原样交给业务实现的 `AgentResourceCatalog`：

| 参数 | 框架是否解释 | 空时 |
|------|----------------|------|
| `agentCode` | 认识这个名字：智能体产品/入口编码。不规定业务如何按它裁剪 | 业务自定：全量或空列表（Lesso 的 Model 空则空列表；MCP / Skill / Prompt 今天是全量） |
| `bizKey` | **不解释**。一段不透明字符串，分隔符和每段含义由业务定 | 业务当「没有额外条件」 |

`graphId` 只表示「用户正在哪张图的属性面板里看内联节点」，不是节点归属，也不是 `bizKey` 的替身。

浏览条件**不写入** `GenericAgentDefinition`，也不写入图内 `agentSpec`。

---

## 2. 两种节点

| | 注册式定义 | 图内联 spec |
|--|------------|-------------|
| 存在哪 | `GenericAgentDefinition`，节点面板 AGENT 列表 | 某张图的节点 JSON（如内置图 `ztc-service-agent` 的 `biz_node`） |
| 能不能进多张图 | 能。图上只留 `nodeId` | 不能原样复用。spec 属于这张图 |
| 改 MCP / Skill 的地方 | 「编辑通用 Agent 节点」弹窗 | 图内属性面板 |
| 看起来像「属于某张图」 | 常因为提示词和输入输出按那张图的业务写的，定义本身仍独立 | 这份 spec 确实属于这张图 |

按图定制提示词，不改变注册式节点可被图 1、图 2 引用这一事实。

---

## 3. 两个入口各传什么

### 3.1 节点面板：直接编辑注册式节点

没有图。即使用户是从某张图点「去节点面板编辑」跳进来，编辑的仍是共享定义，**不继承**那张图的 `graphId`。

```
GET /api/agent-resources/{prompts|models|tools|mcp|skills}?agentCode={可空}&bizKey={可空}
```

不传 `graphId`，不传 `agentDefId`。两个浏览框都可空；都空时与今天「无过滤全量」相同（以业务 Catalog 实现为准）。用户改了框再重新请求。名称关键字若要做，只在前端过滤已返回的 `items`，不进 SPI。

### 3.2 图内属性面板：内联 spec

当前图 id 可以作为上下文传上，与浏览框并列，而不是代替它们：

```
GET /api/agent-resources/mcp?graphId={当前图}&agentCode={可空}&bizKey={可空}
```

`graphId` 不表示「这些 MCP 属于这张图」。业务 Catalog 可以忽略它（Lesso 现网 MCP 就是忽略）。

注册式节点被拖进图之后，画布上没有内联 spec，属性面板不在这里改资源，也不必为了点一下节点就请求 Catalog。

---

## 4. 业务系统接在哪

入口就是业务自己注册的 `AgentResourceCatalog`（每种 `ResourceType` 一个）。框架控制器只做：

1. 读查询参数；
2. 原样调用 `list`；
3. 返回 `{ "items": [ ... ] }`。

不拆 `bizKey`，不校验段数，不把设计期 `bizKey` 写入运行期 `BusinessContext`。

待实现的 SPI 形状（在现有三参数上增加不透明字符串，不替换 `agentCode`）：

```text
list(agentCode, graphId, agentDefId, bizKey)
```

| 参数 | 节点面板 | 图内属性面板 | 业务可做什么 |
|------|----------|--------------|----------------|
| `agentCode` | 用户填写，可空 | 同上，可空 | 按智能体收窄；空则沿用该类型今天的空语义 |
| `bizKey` | 用户填写，可空 | 同上 | 按业务自己的分隔规则拆开再过滤；不认就忽略 |
| `graphId` | 不传 | 当前图，可空 | 可选上下文。忽略则列表不变 |
| `agentDefId` | 不传 | 不传 | 接口仍保留。它是注册式节点 id，不是 `agentCode`，也不是资源范围 |

Lesso 若要让框生效，改的是 `LessoMcpAgentResourceCatalog` 等实现，不是框架里写死 Nacos 查询。

运行期执行入口里的 `bizKey`（例如 `catalog_id` → 记忆 / 观测）是另一次请求上的字段。设计器浏览框**不会**自动变成那次执行的 `bizKey`。业务若希望两边格式相同，在 Catalog 和执行入口各自解析。

---

## 5. 勾选结果

目录只决定候选。写入定义的仍是现有字段：`mcpKeys`、`mcpToolWhitelist`、`skillKeys`、`promptKeys`、`modelConfigKey` 等。

MCP 有 `items` 时用三级树：`items[].key` 是 server，`children[].key` 是工具。全选不写该 server 的白名单；只勾部分工具才写 `mcpToolWhitelist`。

**暂缓：** 目录外手填 key 进入已选（含 `allow-create`）。边界未定前不把任意字符串写成资源 key。见业务方案 `designer-resource-option-tag-proposal.md` §4.0。

---

## 6. 和现网代码的差距

| 项 | 今天 | 定案之后 |
|----|------|----------|
| 内联节点的属性面板 | 有 `agentSpec` 才请求 Catalog；本仓库只传 `graphId` | 可继续带 `graphId`；浏览框可选 |
| 节点面板弹窗 | 已请求 `/mcp`、`/skills`，不带 `graphId`。有数据时 MCP 为三级树，Skill 为不可手造的多选；空列表与失败分开提示 | 浏览框（`agentCode` / `bizKey`）仍不做 |
| `bizKey` 查询参数 | 无 | 待加，框架不解析 |
| Lesso MCP/Skill/Prompt | 忽略 `agentCode` / `graphId` | 业务若要收窄，在 Catalog 实现里读 `agentCode` 与 `bizKey` |

UI 先接上弹窗的三级树、且两个浏览框都空，不依赖 `bizKey` 落地。过滤框在业务 Catalog 会使用这两个参数之后再出现，避免空控件。

---

## 7. 关联

| 文档 | 关系 |
|------|------|
| [streaming-llm-node-template-design.md](streaming-llm-node-template-design.md) §7.2 | 列表 API 原定案；本文补充入口与 `bizKey` |
| [streaming-llm-implementation-plan.md](streaming-llm-implementation-plan.md) | 弹窗接 MCP / Skill 目录已做；浏览参数 `bizKey` 未做 |
| [CHANGELOG.md](../CHANGELOG.md) | `[Unreleased]` 文档条目 |
| `designer-catalog-filter-evaluation.md`（业务仓） | 2026-09-15 把「当时就做名称/归属过滤」搁置；浏览参数形态以本文为准 |
| `designer-resource-option-dev-plan.md`（业务仓） | UI：弹窗接 MCP 树；过滤框不在第一期 |
| `designer-resource-option-tag-proposal.md`（业务仓） | 手填入选暂缓 |
