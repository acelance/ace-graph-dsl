# 学员考试成绩及格分流（全脚本节点联调样本）

> 用途：手工联调 **M-06**（SpEL 条件边）与 **M-08**（创建/导入 → 保存草稿 → 校验 → 发布）  
> 目录：`docs/testdata/script-exam-score-route/`

## 业务说明

模拟「学员考试分数」处理链路，**全部由 `script:*` 节点组成，无 Java 业务节点 / Dispatcher**：

```text
__START__
    │
    ▼
script:exam_normalize_score   # Aviator：raw_score → 裁剪到 0~100 的 score
    │
    ▼  条件边 conditionEngine=spel
       #state['score'] > 60 ? 'pass' : 'fail'
    ├─ pass → script:exam_stamp_pass   # 盖「及格」章
    └─ fail → script:exam_stamp_fail   # 盖「不及格」章
    │
    ▼
script:exam_merge_result      # 汇总 result / message → final_*
    │
    ▼
__END__
```

| 试跑初始 state | 条件边路由 | 预期 final_status |
|----------------|------------|-------------------|
| `raw_score: 85` | pass | pass |
| `raw_score: 45` | fail | fail |
| `raw_score: 60` | fail（`> 60`，等分为 fail） | fail |

与清单 M-06 表达式一致：`#state['score'] > 60 ? 'pass' : 'fail'`。

## 文件

| 文件 | 说明 |
|------|------|
| [script-nodes.json](./script-nodes.json) | 4 个脚本节点创建请求体（须先于图导入注册） |
| [graph-definition.json](./graph-definition.json) | 图 DSL，设计器「导入」用 |

> Graph DSL **只引用** `nodeId`，不会自动创建脚本节点。校验/发布前，节点面板中须已存在这 4 个 `script:*`。

## 操作步骤（建议顺序）

### 1. 注册脚本节点（对应 M-08「创建」）

任选其一：

**A. 设计器手工创建**（严格按 M-08）：按 `script-nodes.json` 各字段在「新建脚本节点」中逐个创建（或复制试跑成功后再创建）。

**B. REST 批量创建**（更快，便于反复联调）：

```http
POST /api/graph/nodes
Content-Type: application/json
```

对 `script-nodes.json` 数组中每一项发一次请求（字段与设计器创建体一致）。

### 2. 导入图 DSL（M-06 / M-08）

1. 设计器打开目标 Graph（可新建空图）  
2. 工具栏 **导入** → 选择 `graph-definition.json`  
3. 确认画布出现 4 个脚本节点 + SpEL 条件边  
4. 点击条件边，属性面板核对：  
   - 引擎 = SpEL  
   - 条件 = `#state['score'] > 60 ? 'pass' : 'fail'`  
   - mapping：`pass` → `script:exam_stamp_pass`，`fail` → `script:exam_stamp_fail`  
5. **保存草稿** → **校验** → **发布**（M-08）  
6. （可选）DryRun / 业务调用初始 state：`{"raw_score": 85}`

### 3. M-07 可顺带测

发布或校验通过后，导出 JSON，把条件边的 `conditionEngine` 改成 `no-such-engine` 再导入并校验，应报「脚本引擎不可用」。

## 注意

- 宿主需启用 SpEL（默认开）与 Aviator；本样本脚本节点未使用 QLExpress/Groovy。  
- 若节点尚未注册，校验会报「节点不存在」。先完成步骤 1。  
- `raw_score=60` 按表达式走 **fail**（严格 `>`），与样例表一致。
