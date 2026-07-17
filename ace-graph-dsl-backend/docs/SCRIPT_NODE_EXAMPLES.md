# 脚本节点填写与使用样例

> 适用：`ace-graph-dsl-ui` 设计器中的「新建脚本节点」对话框，以及后端 `ScriptNodeService` / `ScriptNodeController`。
>
> 引擎：支持 **Aviator**（默认）、**SpEL**、**QLExpress**（可选模块）、**Groovy**（可选模块，默认关闭）。  
> 方案见 [MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md)。

---

## 1. 快速上手（设计器内 3 步）

1. 左侧 **节点面板 → + 新建脚本节点**
2. 选择 **引擎** → **校验语法** → **试跑**（用 Mock State 验证输出）→ **创建**
3. 在 **「脚本」** 页签找到新节点 → **拖到画布** → 连边 → **保存草稿 → 校验 → 发布**

创建成功后，节点会带绿色 `SCRIPT` 标签；发布无需重启后端，运行时 `CompiledGraph` 会热更新。

条件边：选中条件边 → 右侧属性面板可切换 **conditionEngine** 与表达式（同样按 `multiLine` 调整输入高度）。

---

## 2. 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| **Node ID** | 是 | 必须以 `script:` 开头，全局唯一，如 `script:normalize_query` |
| **显示名** | 是 | 设计器展示名称 |
| **类别** | 否 | `NORMAL`（默认）/ `ROUTER`；仅影响面板分类标签 |
| **描述** | 否 | 节点用途说明 |
| **引擎** | 是 | `aviator` / `spel` / `qlexpress` / `groovy`（列表来自 `GET /nodes/engines`） |
| **Input Keys** | 建议填 | 逗号分隔；脚本可见的 `state` 白名单（从 Graph State 读取） |
| **Output Keys** | 建议填 | 逗号分隔；写回 Graph State 的键，须与脚本返回值一致 |
| **权限标签** | 否 | 逗号分隔，如 `public`、`cs`；对接 `GraphNodeAccessControl` |
| **脚本** | 是 | 对应引擎语法；行数随 `multiLine` 自动调整 |
| **Mock State** | 试跑用 | JSON 对象，key 对应 Input Keys |

### 脚本可见变量

| 变量 | 含义 | Aviator / QLExpress / Groovy | SpEL |
|------|------|------------------------------|------|
| `state` | 当前节点输入快照（仅含 Input Keys） | `state.query` | `#state['query']` |
| `config` | 画布上该节点实例的 `NodeRef.config` | `config.multiplier` | `#config['multiplier']` |

### 返回值约定（四引擎统一）

经 `ScriptOutputNormalizer` 规范化后合并进 Graph State：

| 引擎 | Map 写法示例 | 单 outputKey 标量 |
|------|--------------|-------------------|
| Aviator | `seq.map('k', v)` | 直接返回标量 |
| SpEL | `{'k': #state['x']}` 或字面 Map | `#state['x']?.trim()` |
| QLExpress | `return map('k', v);` | `return state.x;` |
| Groovy | `return [k: v]` | `return state.x` |

### 引擎开关与模块

| 引擎 | 默认 | 依赖 / 配置 |
|------|------|-------------|
| Aviator | 开 | core；`default-engine` 默认可为 `aviator` |
| SpEL | 开 | core；`ace.graph.dsl.script.spel-enabled`（默认 true） |
| QLExpress | 开（有模块时） | 引入 `ace-graph-dsl-script-qlexpress`；`qlexpress-enabled`（默认 true） |
| Groovy | **关** | 引入 `ace-graph-dsl-script-groovy`；须 `groovy-enabled=true` |

```yaml
ace.graph.dsl.script:
  default-engine: aviator
  execution-timeout-ms: 500
  max-script-size-bytes: 65536
  spel-enabled: true
  qlexpress-enabled: true
  groovy-enabled: false
  groovy:
    max-script-cache: 200
    allowed-imports:
      - java.util.*
      - java.math.*
      - java.time.*
```

### 限制（默认配置）

| 项 | 默认值 | 配置项 |
|----|--------|--------|
| 执行超时 | 500 ms | `ace.graph.dsl.script.execution-timeout-ms` |
| 脚本体大小 | 64 KB | `ace.graph.dsl.script.max-script-size-bytes` |
| 默认引擎 | aviator | `ace.graph.dsl.script.default-engine` |

---

## 3. 设计器默认样例（入参 trim）

设计器打开对话框时会预填以下内容，可直接点 **试跑** 验证：

| 字段 | 值 |
|------|-----|
| Node ID | `script:custom_<timestamp>`（可改为 `script:normalize_query`） |
| 显示名 | `入参标准化` |
| Input Keys | `query` |
| Output Keys | `normalized_query` |
| 脚本 | `seq.map('normalized_query', string.trim(string(state.query)))` |
| Mock State | `{"query":"  hello  "}` |

**预期试跑结果：**

```json
{
  "normalized_query": "hello"
}
```

对应单测：`ace-graph-dsl-core/.../AviatorScriptEngineTest.executeNormalizeScript`

---

## 3.1 多引擎同场景对照（trim query）

**目标**：`query` trim 后写入 `normalized_query`。Mock：`{"query":"  hello  "}` → `{"normalized_query":"hello"}`。

### Aviator

```aviator
seq.map('normalized_query', string.trim(string(state.query)))
```

### SpEL

```spel
{'normalized_query': #state['query']?.trim()}
```

### QLExpress

```javascript
return map('normalized_query', state.query == null ? '' : state.query.trim());
```

### Groovy（需开启）

```groovy
return [normalized_query: (state.query as String)?.trim()]
```

---

## 3.2 条件边路由表达式（四引擎）

脚本条件边：表达式返回值 **toString()** 后匹配 `mapping` 的 key。示例：`score > 60` → `pass`，否则 `fail`。

| 引擎 | condition 示例 |
|------|----------------|
| Aviator | `state.score > 60 ? 'pass' : 'fail'` |
| SpEL | `#state['score'] > 60 ? 'pass' : 'fail'` |
| QLExpress | `state.score > 60 ? "pass" : "fail"` |
| Groovy | `state.score > 60 ? 'pass' : 'fail'` |

DSL 边字段：`conditionEngine` + `condition` + `mapping`（如 `{"pass":"nodeA","fail":"nodeB"}`）。`dispatcher` 与 `condition` 同时存在时 **dispatcher 优先**。

---

## 3.3 SpEL / QLExpress / Groovy 进阶样例

### SpEL：分数截断（禁止 `T()` 任意类）

```spel
{'normalized_score': #state['score'] > 100 ? 100 : (#state['score'] < 0 ? 0 : #state['score'])}
```

### QLExpress：会员折扣（多行）

```javascript
amount = state.amount;
discount = state.discount > 0 ? state.discount : 1.0;
price = amount * discount;
if (state.member == 'vip') {
  price = price * 0.9;
}
return map('final_price', price);
```

### Groovy：分组计数（需开启）

```groovy
def grouped = (state.items as List)
    ?.groupBy { it.category }
    ?.collectEntries { k, v -> [k, v.size()] } ?: [:]
return [group_counts: grouped]
```

---

## 4. 通用场景样例（Aviator）

### 4.1 字符串清洗 + 默认值

**场景**：trim 用户输入，空值时给默认语言。

| 字段 | 值 |
|------|-----|
| Node ID | `script:normalize_with_lang` |
| Input Keys | `query, lang` |
| Output Keys | `normalized_query, lang` |
| 脚本 | 见下方 |
| Mock State | `{"query":"  退货  ", "lang": "zh"}` |

```aviator
seq.map(
  'normalized_query', string.trim(string(state.query)),
  'lang', state.lang == nil ? 'zh' : state.lang
)
```

---

### 4.2 数值截断（分数上限）

**场景**：将 score 限制在 0～100。

| 字段 | 值 |
|------|-----|
| Node ID | `script:normalize_score` |
| Input Keys | `score` |
| Output Keys | `normalized_score` |
| Mock State | `{"score": 120}` |

```aviator
let s = double(state.score);
return seq.map('normalized_score', s > 100 ? 100 : s);
```

**预期输出：** `{"normalized_score": 100}`

---

### 4.3 使用节点 config 做可配置计算

**场景**：输出 = `state.value × config.multiplier`（倍数在画布节点属性里配置）。

| 字段 | 值 |
|------|-----|
| Node ID | `script:multiply_by_config` |
| Input Keys | `value` |
| Output Keys | `result` |
| 脚本 | 见下方 |

```aviator
seq.map('result', long(state.value) * long(config.multiplier))
```

**画布节点 config（属性面板）：**

```json
{ "multiplier": 3 }
```

当 `state.value = 10` 时，输出 `result = 30`。

> 试跑时 `config` 默认为 `{}`，若依赖 config，请在图里连好并配置后再跑整条链路。

对应单测：`AviatorScriptEngineTest.executeWithConfig`

---

### 4.4 条件打标（路由前预处理）

**场景**：根据紧急程度写入 `route_hint`，供后续条件边或 Java Dispatcher 参考。

| 字段 | 值 |
|------|-----|
| Node ID | `script:urgency_tag` |
| Input Keys | `urgency, normalized_query` |
| Output Keys | `route_hint, normalized_query` |
| Mock State | `{"urgency": "high", "normalized_query": "我要退款"}` |

```aviator
let hint = state.urgency == 'high' ? 'priority' : 'normal';
seq.map('route_hint', hint, 'normalized_query', state.normalized_query)
```

---

### 4.5 组装嵌套 Map（汇聚类轻量替代）

**场景**：将多个上游字段打包为 `analysis_bundle`（类似 cs-reply 的 `merge_analysis`）。

| 字段 | 值 |
|------|-----|
| Node ID | `script:merge_analysis_lite` |
| Input Keys | `knowledge_summary, sentiment_intent, retrieval_chunks` |
| Output Keys | `analysis_bundle` |
| Mock State | 见下方 |

```json
{
  "knowledge_summary": "7天无理由退货...",
  "sentiment_intent": "neutral",
  "retrieval_chunks": ["chunk-1", "chunk-2"]
}
```

```aviator
seq.map('analysis_bundle',
  seq.map(
    'knowledge_summary', state.knowledge_summary,
    'sentiment_intent', state.sentiment_intent,
    'retrieval_chunks', state.retrieval_chunks
  )
)
```

---

### 4.6 多语言结果合并

**场景**：合并 en/ja 翻译结果为 `i18n_replies`（类似 `merge_i18n`）。

| 字段 | 值 |
|------|-----|
| Node ID | `script:merge_i18n_lite` |
| Input Keys | `translate_en, translate_ja` |
| Output Keys | `i18n_replies` |
| Mock State | `{"translate_en": "Hello", "translate_ja": "こんにちは"}` |

```aviator
seq.map('i18n_replies',
  seq.map('en', state.translate_en, 'ja', state.translate_ja)
)
```

---

### 4.7 透传 + 追加字段

**场景**：保留原 `reply_draft`，额外写入 `processed_at` 标记。

| 字段 | 值 |
|------|-----|
| Node ID | `script:stamp_draft` |
| Input Keys | `reply_draft` |
| Output Keys | `reply_draft, processed_at` |
| Mock State | `{"reply_draft": "您好，关于退货..."}` |

```aviator
seq.map(
  'reply_draft', state.reply_draft,
  'processed_at', 'script-node'
)
```

> 注：当前未内置真实时间函数；生产环境建议用 Java 节点写时间戳，或后续扩展 Aviator 安全函数库。

---

### 4.8 空值 / nil 安全

**场景**：上游 key 可能缺失时给默认值。

| 字段 | 值 |
|------|-----|
| Node ID | `script:safe_domain` |
| Input Keys | `domain_code` |
| Output Keys | `domain_code` |
| Mock State | `{}` |

```aviator
seq.map('domain_code', state.domain_code == nil ? 'general' : state.domain_code)
```

---

## 5. cs-reply-m2 图集成样例

当前 golden DSL（`cs-reply-m2.json`）使用 14 个**内置 Java 节点**。脚本节点适合以下场景：

| 场景 | 建议做法 |
|------|----------|
| 实验性预处理 | 在 `intake_normalize` 与 `inquiry_router` 之间插入脚本节点 |
| 轻量字段转换 | 用脚本替代简单 Java Bean，减少发版 |
| 临时业务规则 | 改脚本 + 发布，无需改代码 |

### 5.1 替换 `intake_normalize`（脚本版）

若要用脚本节点替代内置 `intake_normalize`：

1. 创建 `script:normalize_query`（Input/Output 与内置节点对齐）
2. 画布删除 `intake_normalize`，拖入脚本节点
3. 连线：`__START__` → `script:normalize_query` → `inquiry_router`
4. **保存 → 校验 → 发布**

**Input Keys：** `query, product_id, lang, urgency, user_id, domain_code`

**Output Keys：** `normalized_query, product_id, lang, urgency, user_id, domain_code, thread_id`

> 完整入参标准化逻辑较复杂（含 thread_id 生成等），生产环境仍推荐保留 Java 版 `IntakeNormalizeNode`；脚本版适合演示或简化场景。

**简化脚本示例（仅 trim query）：**

```aviator
seq.map(
  'normalized_query', string.trim(string(state.query)),
  'product_id', state.product_id,
  'lang', state.lang == nil ? 'zh' : state.lang,
  'urgency', state.urgency == nil ? 'normal' : state.urgency,
  'user_id', state.user_id,
  'domain_code', state.domain_code
)
```

### 5.2 连边与 State 对齐检查清单

- [ ] 上游节点输出的 key ⊆ 本节点 **Input Keys**
- [ ] 本节点 **Output Keys** 能被下游节点读取
- [ ] DSL **Key Strategy** 对已声明的 key 有配置（cs-reply 图已预置 `REPLACE`）
- [ ] 发布后调用 `/cs/reply/stream?query=...` 做端到端验证

---

## 6. REST API（与设计器契约一致）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/graph/nodes/engines` | 可用引擎元数据（含 `multiLine` / `hintKey`） |
| GET | `/api/graph/nodes/definitions` | 列出所有脚本节点定义 |
| GET | `/api/graph/nodes/definitions/{nodeId}` | 获取单个定义 |
| POST | `/api/graph/nodes` | 创建 |
| PUT | `/api/graph/nodes/{nodeId}` | 更新 |
| DELETE | `/api/graph/nodes/{nodeId}` | 删除 |
| POST | `/api/graph/nodes/validate-script` | 校验语法（body 含 `engine`） |
| POST | `/api/graph/nodes/test-run` | 试跑草稿 |
| POST | `/api/graph/nodes/{nodeId}/test-run` | 试跑已存节点 |

**创建请求体示例：**

```json
{
  "nodeId": "script:normalize_query",
  "displayName": "入参标准化",
  "category": "NORMAL",
  "description": "trim query",
  "inputKeys": ["query"],
  "outputKeys": ["normalized_query"],
  "engine": "aviator",
  "scriptBody": "seq.map('normalized_query', string.trim(string(state.query)))",
  "permissionTags": ["public"],
  "supportsParallel": false,
  "version": "1.0.0",
  "operator": "designer"
}
```

**试跑草稿请求体示例：**

```json
{
  "engine": "aviator",
  "scriptBody": "seq.map('normalized_query', string.trim(string(state.query)))",
  "inputKeys": ["query"],
  "outputKeys": ["normalized_query"],
  "mockState": { "query": "  hello  " },
  "config": {}
}
```

**响应：**

```json
{
  "output": {
    "normalized_query": "hello"
  }
}
```

---

## 7. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| `nodeId 必须以 script: 开头` | ID 格式错误 | 改为 `script:xxx` |
| 试跑提示 Mock State 字段被过滤 | Input Keys 与 Mock 不一致 | 把 Mock 用到的字段都写入 Input Keys |
| 校验语法失败 | 引擎语法错误 | 对照本章各引擎样例；确认 `engine` 字段 |
| 引擎列表无 qlexpress / groovy | 未引入 optional 模块或未开开关 | 见 §2 模块与配置 |
| SpEL 报 TypeLocator / `T(` | 安全策略禁止任意类 | 只用 `#state` / `#config`，勿写 `T(Runtime)` |
| 试跑报错「脚本返回值为 null」 | 脚本无返回值 | 返回 Map（见 §2 返回值约定） |
| 试跑结果 key 不对 | Output Keys 与脚本不一致 | 对齐 Output Keys 与 Map key |
| Mock State JSON 格式错误 | JSON 不合法 | 用双引号、无尾逗号 |
| 发布后图执行失败 | 连边或 key 未对齐 | 检查上下游 Input/Output |
| 脚本执行超时 | 超过配置超时 | 简化逻辑或调大 `execution-timeout-ms` |
| 403 无权管理脚本节点 | 鉴权未通过 | 实现 `GraphNodeAccessControl` 或默认放行策略 |

---

## 8. 相关代码与文档

| 资源 | 路径 |
|------|------|
| 设计器脚本表单 | `ace-graph-dsl-ui/.../ScriptNodeEditor.vue` |
| 条件边引擎 | `ace-graph-dsl-ui/.../PropertyPanel.vue` |
| Aviator / SpEL | `ace-graph-dsl-core/.../script/` |
| QLExpress | `ace-graph-dsl-script-qlexpress/.../QlExpressScriptEngine.java` |
| Groovy | `ace-graph-dsl-script-groovy/.../GroovySandboxScriptEngine.java` |
| 输出规范化 | `ace-graph-dsl-core/.../script/ScriptOutputNormalizer.java` |
| 实施方案 | [MULTI_SCRIPT_ENGINE_PLAN.md](./MULTI_SCRIPT_ENGINE_PLAN.md) |
| 设计背景 | [NODE_FLEXIBILITY_EXPLORATION.md](./NODE_FLEXIBILITY_EXPLORATION.md) |
| 架构总览 | [PROJECT_OVERVIEW.md](./PROJECT_OVERVIEW.md) |

---

## 9. 样例速查表

| # | Node ID 示例 | 用途 | 引擎 | Input | Output |
|---|--------------|------|------|-------|--------|
| 1 | `script:normalize_query` | trim query | 任意（§3.1） | `query` | `normalized_query` |
| 2 | `script:normalize_with_lang` | trim + 默认 lang | Aviator | `query, lang` | `normalized_query, lang` |
| 3 | `script:normalize_score` | 分数上限 100 | Aviator / SpEL | `score` | `normalized_score` |
| 4 | `script:multiply_by_config` | config 倍数 | Aviator | `value` | `result` |
| 5 | `script:urgency_tag` | 紧急度打标 | Aviator | `urgency, normalized_query` | `route_hint, normalized_query` |
| 6 | `script:merge_analysis_lite` | 分析汇聚 | Aviator | `knowledge_summary, ...` | `analysis_bundle` |
| 7 | `script:vip_price` | 会员折扣 | QLExpress | `amount, discount, member` | `final_price` |
| 8 | `script:group_counts` | 分组计数 | Groovy | `items` | `group_counts` |
| 9 | （条件边） | score 分流 | 任意（§3.2） | — | mapping: pass/fail |
