# 设计器嵌入集成方案（组件 / iframe）

> 状态：方案定稿（**P0+P1 已落地**；P2 服务端 catalog 过滤等仍后置）  
> 日期：2026-09-24  
> 范围：`ace-graph-dsl-ui` 设计器嵌入业务系统；图目录过滤 + 业务参数通道  
> **已拍板摘要：** embed **仅三键**下沉（prop 名锁定 **`embed`**）；左侧图目录 graphId 精确匹配 + blur + 恰 1 条展开；资源五类 Catalog 透传；A～E；不做 `readOnly`；**路径 `/ace-graph-designer/`**；`otherBizParams` **UTF-8≤4096**；P1 **Cookie 同源**，Bearer 可选；扩展进 `otherBizParams`、不另开 Map。  
> 相关：  
> - [`designer-resource-catalog-browse.md`](./designer-resource-catalog-browse.md)（资源 Catalog 两入口）  
> - [`LIBRARY_EMBEDDING_ROADMAP.md`](../ace-graph-dsl-backend/docs/LIBRARY_EMBEDDING_ROADMAP.md)  
> - [`SECURITY_INTEGRATION.md`](../ace-graph-dsl-backend/docs/SECURITY_INTEGRATION.md)  
> - [`MENU_PERMISSION_INTEGRATION.md`](../ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md)

---

## 1. 目标与非目标

### 1.1 目标

| 编号 | 诉求 |
|------|------|
| A1 | 业务将设计器用作**独立设计器页** |
| A2 | 业务将设计器用作 **Agent 详情中的一个页签**：可按 `graphId` 过滤图列表，并注入 `agentCode` / `otherBizParams` 等业务上下文供资源接口使用 |
| B1 | 宿主 Vue 大版本与设计器一致（Vue 3）→ **子组件**集成 |
| B2 | 宿主 Vue 2.x → **iframe** 内嵌（同源） |
| B3 | 宿主非 Vue → **iframe** 内嵌（同源） |

嵌入上下文参数（均可选）：

| 参数 | 必填 | 框架是否解释 | 作用 |
|------|------|----------------|------|
| `graphId` | 否 | **是（前端）** | 写入左侧目录**过滤文本框**初值；失焦触发过滤（可手改），见 §3.2 |
| `agentCode` | 否 | **否** | **参数通道**：业务 → UI → 后端（如资源 Catalog）。典型用途含 MCP / Skill 候选裁剪与排序；是否用、如何用由业务决定 |
| `otherBizParams` | 否 | **否** | **不透明字符串通道**：业务自行决定内容与协议格式（JSON、`k=v&…`、自定义分隔等均可）；框架只原样传递，不解析、不校验 |

### 1.2 非目标（本期不做）

- 微前端（qiankun / wujie / Module Federation）作为默认交付——仅列为「宿主已有基建时的升级项」。
- Web Component 自包含包——可作为后续增强，不阻塞本期。
- Vue 2 宿主内硬嵌 Vue 3 SFC / 双 Vue runtime。
- 跨域 iframe 由父页直接读写子页 DOM / Cookie（安全上不可接受，本方案明确拒绝）。
- 框架按 `agentCode` **重排图目录**，或在 UI 内写死 MCP / Skill 排序规则。
- **嵌入专用 `readOnly` prop / query**（见 §3.5）：本期不做；能否保存、发布等**只认现网菜单权限**。

---

## 2. 选型结论（直接可用）

1. **先做嵌入契约 + Manager 的 `graphId` 过滤 + `agentCode` / `otherBizParams` 透传通道**，不要先赌微前端。
2. **B1 = 组件 props（`:embed` 对象）**；**B2 / B3 = 同源 iframe + query**；微前端 = 已有基建时的升级项。
3. **`graphId`：写入左侧目录过滤框并失焦过滤**（§3.2）；**`agentCode` / `otherBizParams`：本期透传框架全部资源 Catalog 接口**（prompts/models/tools/mcp/skills），其它接口看效果后再定（§3.3）。业务 `bizKey` 是否映射 `otherBizParams` 由业务自定。
4. **iframe 一律按同源部署**（业务网关反代静态资源 + API），从根上避开跨域无法访问 / Cookie 丢弃 / 父页读不到子页等问题；跨域仅作明确不支持场景。

```text
业务 A1/A2
    ├── B1 同栈 Vue3 ──► GraphDslManager（:embed 对象 + 壳层 props）
    └── B2 Vue2 / B3 非 Vue ──► 同源 iframe（?embed=JSON，与对象同契约）
                                └── 可选：同源 postMessage（embed 对象改参 / 事件回传）

EmbedContext.agentCode / otherBizParams
    └── 设计器拉资源 Catalog 时作为 query 原样传给（本期白名单 = 框架全部资源类接口）
        GET /api/agent-resources/{prompts|models|tools|mcp|skills}?agentCode=…&otherBizParams=…
            └── 业务 AgentResourceCatalog：裁剪 / 排序 / 解析 / 忽略（自定）
        其它非资源接口：暂不透传，看效果后再定
```

---

## 3. 嵌入契约 EmbedContext

三种宿主共用同一语义。**业务上下文以对象 `EmbedContext` 为唯一扩展面**；壳层仅保留 API 根、标题、语言等展示类 props（**不含**嵌入只读开关，见 §3.5）。

### 3.0 为何用对象（扩展约定）

| 方式 | 评价 |
|------|------|
| 扁平 props：`graph-id` + `agent-code` + 将来每个字段再加一个 | 短期直观；字段一多组件签名膨胀，版本兼容差 |
| **对象 prop：`embed` / `embedContext`** | **推荐**：新增字段只扩接口；透传通道可整包下发 |
| iframe 扁平 query | 适合 1～2 个常用键；字段变多后 URL 难维护 |
| iframe `embed` JSON | 与对象契约对齐，后续加参不必改解析器分支 |

**约定：**

1. **B1 主 API**：`:embed="{ graphId, agentCode, otherBizParams, … }"`（prop 名已锁定为 **`embed`**，不用 `embedContext`）。
2. **扁平别名可选**：为兼容手写，仍可识别顶层 `graphId` / `agentCode` / `otherBizParams`；**与 `embed` 同时存在时以 `embed` 为准**（别名仅作缺省填充，不覆盖对象内已有键）。
3. **B2/B3**：优先 `?embed=<url-encoded-json>`；同时允许扁平上述三键作为便捷写法，解析后**合并进同一 `EmbedContext`**（JSON 优先于扁平键）。
4. **postMessage**：`set-context` 的 payload 即为部分/完整 `EmbedContext` 对象，禁止再发明平行扁平协议。
5. **扩展字段（§3.6）**：契约上**仅三键**会下沉到后端；不要在 `embed` 上再堆平行 Map/JSON 通道——业务扩展内容写入 `otherBizParams` 字符串即可。

### 3.1 字段

```ts
/** 业务嵌入上下文（均可选；全空 = A1 独立设计器行为） */
interface EmbedContext {
  /** 左侧目录过滤框初值（前端解释）；失焦过滤，可手改。见 §3.2 */
  graphId?: string
  /**
   * 业务 Agent 编码（框架不解释）。
   * 设计器在调用后端资源等入口时原样带上；典型供 MCP/Skill 候选裁剪与排序。
   */
  agentCode?: string
  /**
   * 其它业务参数（框架不解释）。类型恒为 string，UTF-8 ≤ 4096 字节（§3.3.2 / §6.7）。
   * 内容协议由业务自行约定（可为 JSON 文本等）；框架只原样传递。
   * 业务扩展字段请编码进本字符串，不要另加 embed 顶层键指望框架下沉。
   */
  otherBizParams?: string
}

/** 壳层 props（与 EmbedContext 分离，不塞进 embed JSON） */
interface DesignerShellProps {
  apiBaseUrl?: string
  title?: string
  locale?: string
  // 本期不提供 readOnly：见 §3.5
}
```

本期框架：

| 字段 | 框架行为 |
|------|----------|
| `graphId` | Manager 拉图目录后写入左侧过滤框；**精确匹配**过滤；恰 1 条自动展开 |
| `agentCode` | 见 §3.3：资源区浏览框 + Catalog 透传 |
| `otherBizParams` | 见 §3.3：只透传不展示；**UTF-8 ≤ 4096 字节**，超限拒绝 |
| **其它顶层键** | **忽略，不下沉 API**（§3.6）；扩展请放入 `otherBizParams` |

> iframe 扁平 query 携带 `otherBizParams` 时须 **URL 编码**；若内容本身是 JSON，推荐整包放进 `embed` JSON，避免双重转义出错。

### 3.2 `graphId` 与左侧目录过滤（定案）

与「资源请求里的当前图 `graphId`」**解耦**，两套语义如下：

| 来源 | 用途 |
|------|------|
| `embed.graphId` → 左侧**过滤文本框** | 只影响 Manager 左侧图目录列表展示 |
| 用户当前打开/选中的图 id | 图内属性面板拉资源时的 `graphId` query（浏览定案不变） |

**UI 行为（与产品标注一致）：**

1. Manager 左侧目录区提供**过滤文本框**（现网若无则实现期新增）。
2. 嵌入传入明确的 `embed.graphId`（如 `a`）时：将该值**填入**过滤文本框，并按过滤规则刷新列表 → 期望左侧**只剩匹配的一项**（如仅图 `a`）。
3. **失焦（blur）**触发过滤；允许用户**手动改成其它 graphId**（或清空）后再失焦，列表随之变化。
4. 仍先 `GET …/catalog/summaries` 拉全量，**前端按过滤框内容过滤**（本期不改后端 catalog 契约）。
5. 过滤后若恰好 1 条：**自动选中并展开画布**（打开该图设计器）；0 条：目录区空态提示；多条：仅展示匹配项，不自动展开。
6. 过滤后若当前选中 id 不在可见列表中：清空选中 / 收起设计器。
7. **`agentCode` / `otherBizParams` 不参与**图目录过滤。
8. **「新建图」等写操作**：不因过滤结果（含 0 条）另加框架规则；是否可建仍只跟现有菜单权限等既有逻辑。业务如何选用过滤后的图、是否允许建图，由业务自行约束；框架只保证过滤框与 embed 初值传递/展示正确。

**过滤匹配规则（2026-09-24 定案）：**

- 过滤框非空：只保留 **`item.graphId` 与过滤框 trim 后全文精确相等** 的项（不做 displayName 模糊、不做子串包含）。
- 过滤框为空：展示全量目录（等同 A1）。
- 多 id：本期嵌入主路径按**单个 graphId** 验收；若框内出现逗号等，仍按「整串精确等于某一 `graphId`」处理（不会拆成多选），业务需要多图时分次传入或后续另议。

> 资源 Catalog 请求里的 `graphId`：**图内入口**用当前打开的那张图；**节点面板入口不传**（见 §3.3.3）。不用左侧目录过滤框字符串。

### 3.3 透传通道：`agentCode` 与 `otherBizParams`

**定位：** 业务项目 → UI 设计器 → 后端接口 的**透传通道**，不是框架内的排序 / 协议引擎。

#### 3.3.0 透传白名单（2026-09-24 确认）

| 范围 | 是否透传 `agentCode` / `otherBizParams` |
|------|------------------------------------------|
| 框架全部资源 Catalog：`GET /api/agent-resources/{prompts\|models\|tools\|mcp\|skills}` | **是（本期）** |
| 图 catalog / 保存 / 发布 / 校验等 | **否** |
| 执行 / 试跑 / SSE、stream-kinds、bizParam 解释器等 | **暂否**；看资源透传效果后再定 |

```text
宿主传入 embed.agentCode / embed.otherBizParams
        │
        ▼
  设计器持有 EmbedContext（内存 / provide）
        │
        └─ 拉资源目录时附带 query（上述五类）
             GET /api/agent-resources/…?agentCode=…&otherBizParams=…
             └─ 业务 AgentResourceCatalog.list(…)：可用可忽略
```

#### 3.3.1 `agentCode`

1. **框架不解释**含义；不在前端按该字段重排图列表或 MCP/Skill 树（顺序以接口 `items` 为准）。
2. 设计器调用资源 Catalog（§3.3.0 白名单）时，非空则写入 `params.agentCode`。
3. 与 [`designer-resource-catalog-browse.md`](./designer-resource-catalog-browse.md) 一致：浏览条件**不写入**图 DSL / 定义。
4. 空值：不传该 query（与现网 Catalog 空语义对齐）。
5. 执行态 `ace.graph.dsl.agentCode` 是另一条链路；**本期不因嵌入自动写入执行 body**（是否预填 ExecutionDrawer 看效果后再定）。

#### 3.3.2 `otherBizParams`（string，不透明）

1. **类型固定为 `string`**；框架不做 `JSON.parse`、不分隔、不解析内容。  
2. **长度上限：UTF-8 编码后 ≤ 4096 字节**（约 4KiB）。超限：拒绝使用该值并提示，**不截断静默提交**。  
3. **协议与用法完全由业务决定**（JSON 文本、`k=v`、自定义编码等均可）；框架文档不规定格式。业务若要把多个扩展字段一起带上，**编码进本字符串**即可，无需框架再提供 Map。  
4. 非空且未超限时原样写入资源等请求的 query **`otherBizParams`**（URL 编码）。Catalog SPI / Controller 增加同名可选参数；业务可读可忽略。  
5. **与业务侧 `bizKey`**：无强制映射。运行期 `BusinessContext.bizKey` 是业务字段；设计期浏览曾定案过同名参数（未落代码）——均**不等于**本嵌入字段；是否映射由业务自定。  
6. **不写入**图草稿 / Agent 定义；空串 / 未传：不带该 query。

#### 3.3.3 两入口与打开方式（2026-09-24 确认）

同一套**注册式 Agent 编辑模态框**（含「资源勾选」区），两种打开方式带参不同：

| 打开方式 | `graphId` | `agentCode` 浏览框初值 | `otherBizParams` |
|----------|-----------|------------------------|------------------|
| 右侧节点面板「新建通用 Agent」或列表上「编辑」 | **不传** | **空**（可手填） | **不展示、不传** |
| 图内属性面板「前往节点面板编辑」 | **当前打开图** | **embed.agentCode**（有则填入，可手改） | **不展示**；Catalog 请求**静默带上** embed 值 |

- 两种方式打开的是**同一个模态框**；差别只在打开时注入的上下文（对应定案 **E**）。
- 注册式节点可独立于图存在；无图打开时允许空 `agentCode` 框后再手填（对应定案 **B**）。

#### 3.3.4 资源区浏览框、刷新与会话范围（定案 A～E）

**定案对照：**

| 代号 | 结论 |
|------|------|
| **A** | `otherBizParams`：**只透传、UI 不展示** |
| **B** | 无图打开：`agentCode` **空框**，用户可手填 |
| **C** | `agentCode` **blur 重拉** Catalog，且须有 **「刷新」按钮** 可点 |
| **D** | 框值 / 静默透传**只影响当前编辑会话**的 Catalog，**不落库**；关模态后丢弃 |
| **E** | 「前往节点面板编辑」与节点面板「编辑」为**同一模态框**；仅前者打开时带上 `graphId` + `agentCode` 初值 + `otherBizParams` 静默透传 |

**UI（模态框内「资源勾选」区顶部）：**

| 控件 | 行为 |
|------|------|
| **`agentCode` 文本框** | 可编辑；初值见 §3.3.3；空则请求不带 `agentCode` |
| **「刷新」按钮** | 按当前框值 +（若有）静默 `otherBizParams` / `graphId` 重拉五类 Catalog |
| **`otherBizParams`** | 无输入框；仅图入口打开时随请求静默携带 |

**何时重拉 Catalog（C）：**

1. `agentCode` 文本框 **失焦（blur）**  
2. 点击 **「刷新」**

**请求参数怎么取：**

```text
打开模态框
  ├─ 「前往节点面板编辑」（有图）
  │     agentCode 框 ← embed.agentCode（可空，可手改）
  │     graphId     ← 当前打开图
  │     otherBizParams ← embed.otherBizParams（静默，不进 UI）
  └─ 节点面板「新建 / 编辑」（无图）
        agentCode 框 ← 空（可手填）
        不传 graphId
        不传 otherBizParams

blur 或点「刷新」
  → GET /api/agent-resources/{prompts|models|tools|mcp|skills}
       ?agentCode={框内当前值，空则省略}
       &otherBizParams={仅有图打开时的静默值，空则省略}
       &graphId={仅有图打开时}
```

**不落库（D）：**

- 上述参数**只影响本会话 Catalog 候选**；不写入 `GenericAgentDefinition` / 图 `agentSpec`。  
- 关闭模态框后丢弃；下次打开重新按 §3.3.3 取初值。

### 3.4 统一解析

| 入口 | 解析方式 |
|------|----------|
| B1 组件 | `mergeEmbed(props.embed, { graphId, agentCode, otherBizParams })` → `EmbedContext`（对象优先） |
| B2/B3 iframe | 解析 `embed` JSON；再合并扁平三键 → 同一对象 |
| 运行期改参（可选） | `postMessage` 带 `embed` 浅合并；`graphId` 变则刷新图目录；`agentCode`/`otherBizParams` 变则后续资源请求带新值（已打开资源树可触发重载） |

```ts
function mergeEmbed(primary?: EmbedContext | null, aliases?: Partial<EmbedContext>): EmbedContext {
  const a = normalize(aliases)   // trim、丢空串
  const p = normalize(primary)
  return { ...a, ...p }          // primary 覆盖 aliases
}
```

iframe query 示例：

```text
# 推荐（可扩展；otherBizParams 放在 embed JSON 内）
/ace-graph-designer/embed.html?embed=%7B%22graphId%22%3A%22order_flow%22%2C%22agentCode%22%3A%22agent_order%22%2C%22otherBizParams%22%3A%22%7B%5C%22tenantId%5C%22%3A%5C%22t1%5C%22%7D%22%7D

# 便捷（两字段；otherBizParams 含特殊字符时务必编码或改用 embed JSON）
/ace-graph-designer/embed.html?graphId=order_flow&agentCode=agent_order
```

非法 JSON / 空值：视为未传。已知字符串字段做 trim；**禁止把 token 放进 `embed` 或 query 充作登录凭证**（见 §6）。`otherBizParams` 若含业务敏感信息，仍依赖同源 HTTPS 与网关鉴权，勿当作安全边界。

### 3.5 写操作权限：不做嵌入 `readOnly`（2026-09-24 定案）

**问题背景（为何曾单独讨论）：**  
有人设想宿主嵌设计器时再传一个 `readOnly=true`，强制隐藏保存/发布。这会与现网**菜单权限**叠床架屋：用户既有 `graph:save` 权限，宿主又标只读，到底听谁的？

**本期结论：不引入、不传递嵌入 `readOnly`。**

| 事项 | 做法 |
|------|------|
| 嵌入契约 `embed` / query / postMessage | **不要**增加 `readOnly` 字段 |
| 壳层 props | **不要**增加 `readOnly`；B1/B2/B3 均不传 |
| 保存、发布、新建图、校验等按钮显隐 | **只走现网菜单权限**（`GraphMenuAccessControl` + 前端 `usePermissionStore`） |
| 内置/bootstrap 图不可编辑 | 仍按**现有**图元数据逻辑（与嵌入无关，不是 `readOnly` prop） |

业务若需要「某页签不能改图」：在**业务侧**不给该用户对应菜单权限，或业务自己藏入口；**不要**指望框架再做一个嵌入专用只读开关。

权限接入说明见 [`MENU_PERMISSION_INTEGRATION.md`](../ace-graph-dsl-backend/docs/MENU_PERMISSION_INTEGRATION.md)。

### 3.6 字段下沉规则（2026-09-24 定案，原「第 9 点」）

**问：三键独立下沉，其它字段要不要再用 Map / JSON 下沉？**

**答：不要。**

| 下沉到后端的字段 | 形式 |
|------------------|------|
| `graphId` | 独立字段（目录过滤用；资源请求另见 §3.3.3） |
| `agentCode` | 独立字段（浏览框 / Catalog query） |
| `otherBizParams` | 独立字段，**string**（Catalog query；内容可由业务自行做成 JSON 文本） |

| 不做 | 原因 |
|------|------|
| `embed` 上再设 `extras: Map` / `extraJson` 等平行通道 | 与 `otherBizParams` 重复；框架要猜如何摊到 query |
| 把未知顶层键自动拼进请求 | 调用方误以为「随便加键就会进后端」 |
| 框架解析 `otherBizParams` 再拆成多个 query | 协议归业务；框架只原样传一个 string |

**规则：**

1. 解析 `embed` 时**只认三键**（外加壳层 `apiBaseUrl` / `title` / `locale`）。  
2. 多出来的顶层键：**忽略**（可打 debug 日志），**不下沉**任何 API。  
3. 业务要扩展：把内容放进 `otherBizParams`（例如 `JSON.stringify({ tenantId, spaceId })`），由业务 Catalog 自行 `JSON.parse`。  
4. 若将来需要新的**一等公民**字段：走文档 RFC，新增具名字段，而不是开放任意 Map。

---

## 4. 业务场景落地

### 4.1 A1 独立设计器

- 不传或传空 `graphId` / `agentCode` / `otherBizParams`。
- B1：全屏路由挂 `GraphDslManager`。
- B2/B3：同源 iframe 打开 embed 壳，无 query 或仅 `locale` 等。

### 4.2 A2 Agent 详情页签

- 页签激活时带上当前 Agent 的上下文，例如：
  - `graphId` = 填入左侧过滤框（失焦过滤，可手改）
  - `agentCode` / `otherBizParams` = 透传全部资源 Catalog 接口
- 预期 UI：左侧目录随过滤框只显示匹配图；资源候选由业务 Catalog 按透传参数决定。

---

## 5. 技术场景落地

### 5.1 B1 同栈子组件（Vue 3）

```vue
<script setup>
import { computed } from 'vue'
import { GraphDslManager } from '@acelance/graph-dsl-ui'
import '@acelance/graph-dsl-ui/style'

const embed = computed(() => ({
  graphId: boundGraphId.value,
  agentCode: agentCode.value,
  otherBizParams: otherBizParams.value // 业务自定 string，框架不解析
}))
</script>

<template>
  <!-- A1 -->
  <GraphDslManager api-base-url="/" />

  <!-- A2：主推对象；agentCode / otherBizParams 仅透传 -->
  <GraphDslManager
    api-base-url="/"
    :embed="embed"
  />
</template>
```

要点：

- peer：Vue 3 / Pinia / Element Plus 等与库声明一致。
- 鉴权：`createGraphApi` / `configureGraphApi` 注入宿主 axios（Bearer 等），**与现有能力对齐**，iframe 不走这条。
- 容器需明确高度（`height: 100%` 链）。
- 扁平别名仅作兼容，文档示例以 `:embed` 为准。
- MCP/Skill 顺序与附加过滤：**不要**在宿主前端再排；改业务侧 Catalog 对 `agentCode` / `otherBizParams` 的处理。

### 5.2 B2 / B3：同源 iframe + query

```html
<iframe
  title="Graph DSL Designer"
  src="/ace-graph-designer/embed.html?embed=..."
  style="width:100%;height:100%;border:0;"
  referrerpolicy="same-origin"
></iframe>
```

宿主侧推荐封装：

```js
function designerEmbedSrc(ctx) {
  const q = new URLSearchParams({ embed: JSON.stringify(ctx) })
  return `/ace-graph-designer/embed.html?${q}`
}
// designerEmbedSrc({
//   graphId: 'order_flow',
//   agentCode: 'agent_order',
//   otherBizParams: JSON.stringify({ tenantId: 't1' }) // 或任意业务串
// })
```

要点：

- **`src` 必须与业务页同站**（同 scheme + host + port），见 §6。
- 设计器提供独立静态入口 `embed.html`（或路由 `/embed`），内部 `parseEmbedFromSearch` → 挂 Manager。
- Vue2 / React / 原生只负责写 iframe 与序列化 `embed` 对象，无框架耦合。
- 两字段临时调试仍可用扁平 query；正式接入推荐 JSON，避免以后加参再改拼接逻辑。

### 5.3 微前端（升级项，非本期）

若宿主已有 qiankun / wujie 等：子应用入口复用同一 embed 壳与 `EmbedContext`；props / 路由 query 映射到契约即可。**不单独发明第三套参数模型。**

---

## 6. iframe 同源与安全（重点）

### 6.1 原则：禁止依赖跨域嵌入

跨域 iframe 下常见问题（父页读子 DOM、共享登录 Cookie、CORS 预检、第三方 Cookie 拦截、`postMessage` 滥用来传 token）在安全策略下会直接不可用或高风险。

**本方案要求：设计器静态资源与 Graph API 均挂在业务站点同源路径下**，由网关 / Nginx 反代到设计器静态与后端，浏览器视角无跨域。

```text
浏览器
  └─ https://biz.example.com/agent/123          （业务页）
  └─ https://biz.example.com/ace-graph-designer/embed.html?…  （iframe，同源）
  └─ https://biz.example.com/api/graph/**       （API，同源 Cookie / 同站 Bearer）
         │
         ▼ 网关反代
    ┌────────────┬─────────────────────┐
    │ 静态资源    │  ace-graph-dsl 后端  │
    └────────────┴─────────────────────┘
```

### 6.2 规范挂载路径与部署（交付清单）

**规范路径（写入交付清单，B2/B3 默认约定）：**

| 资源 | 浏览器路径 |
|------|------------|
| 设计器 embed 页 | **`/ace-graph-designer/embed.html`** |
| 静态资源目录 | **`/ace-graph-designer/`**（js/css 等） |
| Graph API | **`/api/graph/**`**（与独立设计器一致） |

业务页 iframe 示例：`src="/ace-graph-designer/embed.html?embed=..."`（须与业务页**同源**）。

```nginx
# 业务已有 server: biz.example.com

location /ace-graph-designer/ {
    alias /data/ace-graph-dsl-ui-embed/;
    try_files $uri $uri/ /ace-graph-designer/embed.html;
}

location /api/graph/ {
    proxy_pass http://ace-graph-dsl-backend:8087/api/graph/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

效果：

| 问题 | 同源方案下 |
|------|------------|
| 父页能否「访问」iframe 内 DOM | 同源下可以，但**业务仍应通过约定事件通信**，避免强耦合 DOM |
| Cookie / Session 登录态 | 同站请求自动带上；与业务 API 一致 |
| CORS | 浏览器同 ORIGIN，**不需要**为设计器单独开跨域 CORS |
| 第三方 Cookie / ITP | 不涉及第三方嵌入 |
| 混合内容 | 全站 HTTPS，避免 http iframe 嵌 https 页 |

### 6.3 点击劫持与被嵌控制

业务页嵌设计器是**主动嵌入**；同时要防止**恶意站点**嵌你们的设计器：

| 手段 | 建议 |
|------|------|
| `Content-Security-Policy: frame-ancestors` | 仅允许自家域名，例如 `frame-ancestors 'self' https://biz.example.com` |
| 废弃的 `X-Frame-Options` | 可与 CSP 并存：`SAMEORIGIN`（仅同源父页） |
| 设计器 embed 页 | 不提供「任意跨域父页」的宽松配置 |

这样：**合法业务同源页可嵌；外站无法嵌或无法利用跨域读内容。**

### 6.4 鉴权（同源；P1 以 Cookie 为准）

| 做法 | 阶段 | 说明 |
|------|------|------|
| ✅ **同站 Session Cookie** | **P1 默认** | 用户已登录业务域，iframe 内请求自动带 Cookie；与业务 API 一致 |
| ⚪ 同源 `postMessage` 传 Bearer | **可选（非 P1 必做）** | 仅当业务无法用 Cookie、必须 Bearer 时启用；见下 |
| ❌ `?access_token=` 挂在 iframe src | 禁止 | 进日志 / Referer / 历史 |
| ❌ 跨域 iframe + 任意 origin 收 token | 禁止 | 必须同源且校验 `event.origin` |

B1 组件模式：继续用 `configureGraphApi({ headers / interceptors })`（宿主 axios）。  

B2/B3 **P1**：只要求 **Cookie 同源** 可用即可。  

Bearer（可选增强）步骤：

1. 父页与 iframe **同源**；  
2. 父页 `postMessage({ type: 'ace-graph/auth', token }, location.origin)`；  
3. 子页校验 `event.origin === location.origin` 后写入 axios 默认头；  
4. token 仅存内存，不写 URL / 不默认写 `localStorage`。

### 6.5 可选 postMessage 协议（同源）

仅在需要「页签切换 Agent 不刷 iframe」或「保存成功通知宿主」时使用。  
**`ace-graph/auth`（Bearer）为可选**；**P1 不强制实现**，P1 以 Cookie 同源为准。

```ts
// 父 → 子（业务上下文一律走 embed 对象）
{ type: 'ace-graph/set-context', embed: EmbedContext }  // 浅合并进当前 ctx
{ type: 'ace-graph/auth', token: string }               // 可选；不属于 EmbedContext

// 子 → 父
{ type: 'ace-graph/ready' }
{ type: 'ace-graph/saved', graphId: string }
{ type: 'ace-graph/published', graphId: string, version?: string }
```

规则：

- 只接受 `event.origin === window.location.origin`（或业务配置的白名单，默认仅 self）。
- `type` 必须带命名空间前缀 `ace-graph/`，避免与宿主其它消息碰撞。
- `set-context` **不要**再平铺 `graphId`/`agentCode`/`otherBizParams` 顶层字段（若实现期兼容旧消息，仅作别名并写入 `embed`）。

### 6.6 明确不支持的嵌入方式

| 方式 | 原因 |
|------|------|
| `https://designer-cdn.other.com/embed.html` 嵌进 `https://biz.example.com` | 跨域；Cookie 不共享；父页无法安全访问子页；易被迫把 token 塞进 query |
| 文件协议 / 混合内容 | 浏览器限制多，登录态不稳定 |
| 放宽 `frame-ancestors *` | 点击劫持风险 |

若业务强诉求「设计器独立域名」：必须在**同一父域**下用反向代理做成**浏览器同 ORIGIN**（推荐），或升级为完整微前端 + 统一认证中心（成本高，非本期）。

### 6.7 第 8 点交付清单（路径 / 长度 / 鉴权，2026-09-24 定案）

实现与联调验收时按本表勾选：

| # | 项 | 定案值 |
|---|-----|--------|
| 1 | embed 静态挂载路径 | **`/ace-graph-designer/`**，入口 **`/ace-graph-designer/embed.html`** |
| 2 | API 路径 | **`/api/graph/**`**（同源反代） |
| 3 | `otherBizParams` 长度 | **UTF-8 ≤ 4096 字节**；超限拒绝、不截断 |
| 4 | P1 鉴权 | **Cookie 同源优先**；同站 Session 可用即达标 |
| 5 | Bearer `postMessage` | **可选**，不进 P1 必做范围 |
| 6 | CSP | 配置 `frame-ancestors`（至少 `'self'` + 业务域） |

---

## 7. 框架侧改造清单

### 7.1 前端 `ace-graph-dsl-ui`

| 项 | 说明 |
|----|------|
| `EmbedContext` + `mergeEmbed` | 对象主入口；扁平别名仅合并用 |
| 解析工具 | `parseEmbedFromProps` / `parseEmbedFromSearch`（支持 `embed` JSON） |
| `GraphDslManager` 新 props | **`embed?`**、可选别名 `graphId`/`agentCode`/`otherBizParams` |
| 图目录流水线 | 左侧过滤框：embed 填初值；blur 过滤；**graphId 精确匹配**；恰 1 条自动展开 |
| 资源请求透传 | 五类 Catalog 带参；`agentCode` 框 + blur/刷新；`otherBizParams` 静默透传（仅图入口） |
| embed 入口 | `embed.html` + 轻量 `embed/main.js`：解析 query → `:embed`、监听 postMessage |
| 构建 | embed 可作为 lib 之外的 app 入口一并产出到 `dist-embed/` 或静态目录 |
| i18n | 空态：「当前上下文无可用图」等 |

### 7.2 后端（最小）

| 项 | 说明 |
|----|------|
| 本期可不改图 catalog API | `graphId` 过滤在前端完成 |
| 资源 Catalog | 扩展透传 `agentCode`、`otherBizParams`（SPI/Controller 增加可选参数）；业务实现可读可忽略；**框架不映射业务 `bizKey`** |
| 安全头建议 | 文档给出 `frame-ancestors` / 网关示例（实现可在 starter 可选 Filter） |

### 7.3 文档与示例

| 项 | 说明 |
|----|------|
| 本文 | 方案 SSOT |
| UI README | 增加「嵌入」一节链接本文；示例 A1/A2、iframe 片段 |
| 业务 Demo | 可选：同仓加「反代 + embed」最小示例 |

---

## 8. 处理时序（实现契约）

### 8.1 图目录（左侧过滤框 + `embed.graphId`）

```text
refreshCatalog() / 过滤框 blur
  │
  ├─1─ GET /api/graph/catalog/summaries     → raw[]（全量）
  ├─2─ 过滤框文案 ← 初值可为 embed.graphId
  ├─3─ visible = raw 中 graphId === trim(过滤框) 的项（精确匹配）
  └─4─ 渲染 visible；恰 1 条 → **自动选中并展开画布**
        （资源请求的 graphId = 当前打开图，≠ 过滤框）
```

### 8.2 资源 Catalog（按打开入口组参）

```text
打开注册式编辑模态框 / blur·刷新
  │
  └─ GET /api/agent-resources/{prompts|models|tools|mcp|skills}
        ?agentCode={浏览框当前值?}
        &otherBizParams={仅「前往节点面板编辑」打开时的静默值?}
        &graphId={仅有图打开时的当前图?}
```

```js
// 有图打开（前往节点面板编辑）
listAgentResources(kind, {
  agentCode: agentCodeBox.trim() || undefined,
  otherBizParams: sessionOtherBizParams || undefined, // 静默，无 UI
  graphId: currentOpenGraphId
})

// 节点面板新建/编辑（无图）
listAgentResources(kind, {
  agentCode: agentCodeBox.trim() || undefined
  // 不传 graphId、otherBizParams
})
```

---

## 9. 验收标准

| 编号 | 验收项 |
|------|--------|
| V1 | B1 A1：无嵌入业务字段时，图目录与现网一致；资源请求不带 `agentCode`/`otherBizParams`（或空） |
| V2 | 传入 `graphId=a`：过滤框填入 `a`，按 **graphId 精确匹配** 后左侧只剩该项并**自动展开画布**；可手改过滤框后再 blur |
| V3 | 从图进入编辑：Catalog 带 graphId+agentCode（框）+otherBizParams（静默）；节点面板进入：无 graphId、agentCode 框空可手填、不带 otherBizParams |
| V3b | agentCode 框 blur 与「刷新」按钮均重拉五类 Catalog；框值与 otherBizParams 不落库 |
| V4 | 业务 Catalog 按上述参数改变 MCP/Skill 顺序或过滤时，设计器展示与接口 `items` 一致 |
| V5 | `otherBizParams`：不展示、不 parse；UTF-8≤4096；超限拒绝；不写入业务 `bizKey` |
| V5b | embed 上未知顶层键不下沉 API；扩展只进 `otherBizParams` 字符串（§3.6） |
| V6 | B2/B3：同源 iframe 的 `embed` JSON 与 B1 行为一致 |
| V7 | 鉴权：已登录用户在 iframe 内可拉目录、保存 |
| V8 | 安全：外域无法嵌入；embed URL **无**长期登录 token |
| V9 | 跨域独立域名直嵌 → 文档标明不支持 |

---

## 10. 实施分期

| 阶段 | 内容 | 产出 |
|------|------|------|
| P0 | EmbedContext + 左侧过滤框（精确匹配 graphId）+ 恰 1 条自动展开 | B1 A2 图目录 |
| P0 | 资源勾选区 agentCode 框 + blur/刷新；图入口静默透传 otherBizParams | §3.3.3～3.3.4 |
| P0 | provide + 资源 API 透传 `agentCode`、`otherBizParams` | Catalog 通道 |
| P1 | `embed.html` 挂到 **`/ace-graph-designer/`** + query 解析 | B2/B3 iframe |
| P1 | 同源 Cookie 鉴权可用；部署文档含 §6.7 清单 | 安全闭环 |
| P1 | 可选：`set-context` / `ready` / `saved` postMessage（**不含**必做 Bearer auth） | 页签无刷切换 |
| P1+ | 可选：`ace-graph/auth` Bearer | 按需 |
| P2 | 服务端图 catalog 按 graphIds 过滤；Catalog SPI 落地 `otherBizParams` 形参 | 性能与后端契约 |
| P2 | 微前端 / Web Component（按需） | 升级项 |

---

## 11. 决策摘要

| 议题 | 决策 |
|------|------|
| 同栈 | Vue3 子组件 + **`:embed` 对象** |
| 参数扩展 | **对象为唯一扩展面**；扁平三键仅别名 |
| Vue2 / 非 Vue | **同源 iframe + `embed` JSON query** |
| 微前端 | 非默认；复用同一契约 |
| graphId（目录） | 左侧过滤框初值 + blur；**按 graphId 精确匹配**；恰 1 条自动展开画布 |
| graphId（资源） | 图入口=当前打开图；节点面板入口不传 |
| agentCode | 资源区可编辑框；图入口用 embed 初值，无图空框可手填；blur + 刷新重拉 Catalog；不落库 |
| otherBizParams | 只透传不展示；UTF-8≤4096；仅图入口静默带上；不落库；不映射业务 bizKey |
| 字段下沉 | **仅三键**独立下沉；**不做**额外 Map/JSON 通道；扩展写入 `otherBizParams`（§3.6） |
| iframe 路径 | 规范 **`/ace-graph-designer/embed.html`**（§6.2 / §6.7） |
| P1 鉴权 | **Cookie 同源**；Bearer postMessage **可选** |
| 写操作 / 只读 | **不做嵌入 `readOnly`**；保存/发布等**完全走现网菜单权限**（§3.5） |
| 跨域 iframe | **不支持**；反代做成同源 |

---

## 12. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-09-24 | 初稿：嵌入契约、B1/B2/B3、同源 iframe 安全与分期 |
| 2026-09-24 | 明确以 `embed` 对象为主 API；扁平字段 / query 为别名；postMessage 对齐 |
| 2026-09-24 | 纠正 `agentCode`：非图目录重排，而是透传至资源 Catalog（MCP/Skill 等） |
| 2026-09-24 | 增加 `otherBizParams: string` 不透明透传通道 |
| 2026-09-24 | 澄清：`otherBizParams` 与业务 `BusinessContext.bizKey` 无强制映射；是否赋值由业务决定 |
| 2026-09-24 | 确认透传白名单=全部资源 Catalog；graphId=左侧过滤框初值+blur，与资源请求当前图解耦 |
| 2026-09-24 | 定案：过滤结果恰 1 条时自动选中并展开画布 |
| 2026-09-24 | 确认两入口 graphId：图内=当前打开图，节点面板不传；资源浏览框与 embed 优先级初稿（§3.3.3～3.3.4） |
| 2026-09-24 | 定案 A–E 写入 §3.3.3～3.3.4，并增加 A～E 对照表便于验收 |
| 2026-09-24 | 定案左侧图目录过滤：仅 graphId 精确匹配；§8.2/验收/文首摘要对齐 A～E 与过滤定案 |
| 2026-09-24 | 定案不做嵌入 `readOnly`，写操作完全走现网菜单权限（§3.5） |
| 2026-09-24 | 定案 §6.7：路径 `/ace-graph-designer/`、otherBizParams≤4096 字节、P1 Cookie、Bearer 可选；§3.6：仅三键下沉、扩展进 otherBizParams、不另开 Map |
| 2026-09-24 | 开始实现：锁定 prop=`embed`；UI EmbedContext/过滤/资源浏览框；后端 Catalog 增加 `otherBizParams`；embed.html 入口 |
