# Ace Graph DSL — 多脚本引擎价值与改进方案

> 对应：`FUTURE_OPTIMIZATION_PLAN.md` §7.2  
> 版本：v1.0  
> 日期：2026-07-03  
> 状态：待实施

---

## 1. 背景

当前 Ace Graph DSL 的脚本节点（ScriptNode）和脚本条件边（ScriptEdge）统一使用 **Aviator 表达式引擎**作为唯一内置实现。`ScriptEngine` 接口和 `ScriptEngineRegistry` 从设计之初就支持多引擎扩展，但暂无第二引擎落地。

本文分析多引擎的价值、现状能力边界、改进方案和分期落地建议。

---

## 2. 现状：仅 Aviator 引擎

### 2.1 当前架构

```
ScriptEngine 接口（4 个方法契约：engineId / validate / compile / execute）
  └── ScriptEngineRegistry（自动收集所有 ScriptEngine Spring Bean，按 engineId 路由）
        └── AviatorScriptEngine  ← 唯一实现
              ├─ 共享守护线程池（SynchronousQueue + AbortPolicy）
              ├─ 执行超时控制（默认 500ms）
              ├─ 白名单上下文（仅 state + config）
              └─ AutoCloseable 优雅关闭
```

### 2.2 Aviator 擅长什么

Aviator 是高性能的 Java 表达式引擎，适合**单行/简短表达式**：

```java
// ✅ 字符串处理
"string.trim(string(state.query))"

// ✅ 条件路由
"state.score > 60 ? 'pass' : 'reject'"

// ✅ 数值计算（有 let 支持多步）
"let s = double(state.score); seq.map('normalized', s > 100 ? 100 : s)"

// ✅ 逻辑判断
"state.type == 'refund' && state.amount > 500"
```

### 2.3 Aviator 的边界

当业务规则变复杂时，Aviator 的语法开始捉襟见肘：

```java
// 😣 多步骤业务规则 —— 可写但不可读
"let a = state.amount;
 let d = state.discount > 0 ? state.discount : 1.0;
 let p = a * d;
 let t = p * 0.06;
 let s = p + t;
 let m = state.member_level == 'vip' ? s * 0.9 : s;
 seq.map('final_price', m)"

// ❌ 复杂集合操作（分组、排序、过滤） —— Aviator 无原生支持

// ❌ 调用 Spring Bean / 读取配置 —— Aviator 白名单内不允许

// ❌ 多行带注释的业务规则 —— 无注释语法，不支持多行 if/else
```

这些场景在真实业务中**非常常见**（报价计算、风控规则链、数据清洗流水线、阶梯定价），用 Aviator 勉强能写但可读性和可维护性很差。

---

## 3. 多引擎的价值

### 3.1 核心收益

| 收益 | 说明 |
|------|------|
| **按场景选最合适的引擎** | 简单表达式用 SpEL/Aviator 一行搞定，复杂逻辑用 QLExpress/Groovy 清晰表达 |
| **降低脚本编写门槛** | SpEL 对标 Spring 开发者的已有知识，Groovy 对标 Java 开发者的直觉语法 |
| **扩展适用场景** | 多行业务规则、数据清洗流水线、集合操作等 Aviator 不擅长的场景 |
| **安全边界不降低** | 所有引擎统一 `ScriptEngine` 契约（超时 + 白名单上下文 + 线程池隔离） |
| **前端零感知切换** | DSL 中仅改 `"engine": "spel"` 或 `"engine": "groovy"`，编辑器下拉动态获取引擎列表 |

### 3.2 场景-引擎匹配表

| 场景 | 推荐引擎 | 理由 |
|------|---------|------|
| 单行数值计算、字符串处理 | **Aviator** | 原生高性能，语法极简 |
| 条件路由表达式 | **Aviator / SpEL** | 两者皆可，SpEL 更容易被 Spring 开发者接受 |
| 多行业务规则（报价、风控） | **QLExpress** | 阿里系生态，原生多行 if/else，语法贴近 Java |
| 复杂集合操作（分组/排序/过滤） | **Groovy** | closures + 集合方法链 |
| 数据清洗流水线（多步骤字段转换） | **Groovy** | 多行代码块 + 中间变量 |
| 读取 Spring 配置/Bean | **SpEL** | `@configService.get('xxx')` 原生支持 |
| 运营/实施人员自定义规则 | **QLExpress** | 类自然语言的可读语法 |

---

## 4. 候选引擎对比

| 引擎 | 依赖大小 | 沙箱能力 | 多行支持 | Spring 集成 | 学习成本 | 适用场景 |
|------|---------|----------|---------|------------|---------|---------|
| **SpEL** | 0（Spring 内置） | 中等 | ❌ 仅单行 | 原生 | 低（Spring 开发者零成本） | Spring 配置/表达式 |
| **QLExpress** | ~200KB | 良好 | ✅ if/else/for | 需注册 Bean | 低（类 Java 语法） | 多行业务规则 |
| **Groovy** | ~8MB+ | 需手动沙箱 | ✅ 全语法 | 成熟 | 中（需加沙箱限制） | 复杂脚本/集合操作 |
| **Aviator**（已有） | ~600KB | ✅ 内置白名单 | ❌ 仅单行 | 轻量 | 低 | 表达式计算 |

> **不建议一期全上**：先 SpEL + QLExpress 覆盖 90% 场景，Groovy 单独安全评审后默认关闭、按需开启。

---

## 5. 统一引擎契约（已有，无需改动）

```java
// ace-graph-dsl-core/.../script/ScriptEngine.java
public interface ScriptEngine {
    String engineId();                              // "aviator" | "spel" | "qlexpress" | "groovy"
    void validate(String script);                    // 校验脚本语法
    Object compile(String script);                   // 预编译脚本
    Object execute(Object compiled, ScriptExecutionContext ctx);  // 执行（state + config 白名单上下文）
}
```

所有新引擎共享以下能力（可抽 `AbstractTimeoutScriptEngine` 基类）：
- `executionTimeoutMs`：单次执行超时
- 共享线程池：`SynchronousQueue` + `AbortPolicy`
- 上下文白名单：仅暴露 `state` / `config`，禁止反射、IO、网络

---

## 6. 分期实施路径

### Phase 1：SpEL（2–3 人日）⭐ 推荐优先

**零新依赖**（Spring 内置 `spring-expression`），覆盖简单表达式补充。

**要点：**
- `StandardEvaluationContext`，注册 `state` 为变量，`#config` 为变量
- `SpelCompilerMode.IMMEDIATE` 可以开启编译模式提升性能

**示例：**

```java
@Component
public class SpelScriptEngine implements ScriptEngine {
    private final SpelParserConfiguration config = new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, null);
    private final SpelExpressionParser parser = new SpelExpressionParser(config);

    @Override public String engineId() { return "spel"; }

    @Override public void validate(String script) {
        parser.parseExpression(script);  // 语法错直接抛异常
    }

    @Override public Object compile(String script) {
        return parser.parseExpression(script);
    }

    @Override public Object execute(Object compiled, ScriptExecutionContext ctx) {
        StandardEvaluationContext ec = new StandardEvaluationContext();
        ec.setVariable("state", ctx.state());
        ec.setVariable("config", ctx.config());
        return ((Expression) compiled).getValue(ec);
    }
}
```

**DSL 中使用：**

```json
{ "nodeId": "script:route", "engine": "spel",
  "script": "#state.score > 60 ? 'pass' : 'reject'",
  "inputKeys": ["score"], "outputKeys": ["result"] }
```

---

### Phase 2：QLExpress（3–5 人日）

引入轻量依赖 `com.alibaba:QLExpress`（~200KB），覆盖**多行业务规则**。

**要点：**
- `ExpressRunner` + `QLExpressRunStrategy`，关闭 `import`
- 支持多行 `if/else`、`for`、中间变量

**示例：**

```java
@Component
public class QlExpressScriptEngine implements ScriptEngine {

    @Override public String engineId() { return "qlexpress"; }

    @Override public void validate(String script) {
        ExpressRunner runner = new ExpressRunner();
        runner.getExpressInstructionSetFromJavaCode(script);  // 语法校验
    }

    @Override public Object compile(String script) {
        ExpressRunner runner = new ExpressRunner();
        return runner.getExpressInstructionSetFromJavaCode(script);
    }

    @Override public Object execute(Object compiled, ScriptExecutionContext ctx) {
        InstructionSet is = (InstructionSet) compiled;
        IExpressContext<String, Object> context = new DefaultContext<>();
        context.put("state", ctx.state());
        context.put("config", ctx.config());
        return InstructionSetRunner.executeOuter(is, context, null, true, false, null);
    }
}
```

**DSL 中使用（多行规则示例）：**

```json
{ "nodeId": "script:pricing", "engine": "qlexpress",
  "script": "// 报价计算规则\nlet amount = state.amount;\nlet discount = state.discount > 0 ? state.discount : 1.0;\nlet price = amount * discount;\nlet tax = price * 0.06;\nlet final = price + tax;\nif state.member == 'vip' {\n  final = final * 0.9;\n}\nreturn map('final_price', final);",
  "inputKeys": ["amount", "discount", "member"], "outputKeys": ["final_price"] }
```

---

### Phase 3：Groovy Sandbox（5–8 人日 + 安全评审）

覆盖**复杂脚本**（集合操作、闭包、数据清洗流水线）。依赖 `org.codehaus.groovy:groovy`（~8MB）。

**安全要点（上线前必过）：**

| 层级 | 措施 |
|------|------|
| AST 限制 | `SecureASTCustomizer`：禁止 `System`、`Runtime`、`File`、`ProcessBuilder`、反射 |
| 导入白名单 | 仅允许 `java.util.*`、`java.math.*`、`java.time.*` |
| 编译缓存 | `GroovyClassLoader` 单例复用，避免每次新建 |
| 默认关闭 | `ace.graph.dsl.script.groovy-enabled` 默认 `false`，需管理员显式开启 |

**示例（数据清洗流水线）：**

```groovy
// 多步数据清洗
def q = state.query.trim().toLowerCase()
def score = Math.max(0, Math.min(100, state.rawScore))
def tags = state.tags.findAll { it != null && it.length() > 0 }

// 集合分组
def grouped = state.items.groupBy { it.category }
    .collectEntries { k, v -> [k, v.size()] }

return [normalized_query: q, normalized_score: score, clean_tags: tags, group_counts: grouped]
```

---

## 7. 模块规划

```
ace-graph-dsl-core               → ScriptEngine 接口 + Aviator（默认）
ace-graph-dsl-script-spel        → SpelScriptEngine（optional，Spring 内置零新依赖）
ace-graph-dsl-script-qlexpress   → QLExpressScriptEngine（optional）
ace-graph-dsl-script-groovy      → GroovyScriptEngine（optional，默认不引入 starter）
```

starter 中按开关注册：

```java
@Bean @ConditionalOnProperty("ace.graph.dsl.script.spel-enabled")
ScriptEngine spelScriptEngine(...) { ... }

@Bean @ConditionalOnProperty("ace.graph.dsl.script.qlexpress-enabled")
ScriptEngine qlExpressScriptEngine(...) { ... }

@Bean @ConditionalOnProperty("ace.graph.dsl.script.groovy-enabled")
ScriptEngine groovyScriptEngine(...) { ... }
```

---

## 8. 配套改动

### 8.1 后端：新增引擎元数据 API

```
GET {base}/script/engines
→ [
    { "id": "aviator", "label": "Aviator 表达式", "multiLine": false },
    { "id": "spel", "label": "SpEL", "multiLine": false },
    { "id": "qlexpress", "label": "QLExpress", "multiLine": true },
    { "id": "groovy", "label": "Groovy", "multiLine": true }
  ]
```

在 `ScriptEngineRegistry` 增加 `listEngines()` 方法即可，**无需改 DSL 模型**。

### 8.2 前端：引擎下拉动态获取

`ScriptNodeEditor.vue`：
- 引擎字段改为调用 `GET /script/engines` 获取下拉选项
- 按 `multiLine` 切换 `el-input` / `textarea`
- 条件边 `PropertyPanel` 同步支持 `conditionEngine` 选择

### 8.3 校验分发

`GraphValidator` / `ScriptNodeService.validate()` 按 `engineId` 分发校验，每引擎独立错误提示。

---

## 9. 安全清单（上线前必过）

- [x] 脚本大小上限（已有 `maxScriptSizeBytes`）
- [x] 执行超时 + 线程池隔离（已有模式可复用）
- [ ] 引擎级开关（`spel-enabled` / `qlexpress-enabled` / `groovy-enabled`）
- [ ] Groovy 单独安全评审 + **默认关闭**
- [ ] SpEL 禁用 `T(...)` 类型引用（防止类加载攻击）
- [ ] QLExpress 关闭 `import`、自定义 `Operator`
- [ ] 各引擎注册时向 `ScriptEngineRegistry` 声明 `multiLine` 等元数据

---

## 10. 建议落地顺序

```
Phase 1: SpEL  →  2–3 人日，零依赖，覆盖 Spring 表达式的兼容场景
Phase 2: QLExpress  →  3–5 人日，轻量依赖，覆盖多行业务规则场景
Phase 3: Groovy Sandbox  →  5–8 人日 + 安全评审，覆盖复杂脚本场景（默认关闭）
```

> **近期优先做 SpEL**：零风险、零新依赖、与 Aviator 形成「轻量表达式双引擎」；
> QLExpress 和 Groovy 按实际业务需求触发。

---

## 11. 相关文档

| 文档 | 说明 |
|------|------|
| [FUTURE_OPTIMIZATION_PLAN.md](./FUTURE_OPTIMIZATION_PLAN.md) | 总体规划 §7.2 |
| [NODE_FLEXIBILITY_EXPLORATION.md](./NODE_FLEXIBILITY_EXPLORATION.md) | 节点灵活性探索（含引擎对比 §3.2–3.3） |
| [REMAINING_ITEMS_PLAN.md](./REMAINING_ITEMS_PLAN.md) | 剩余项说明（含 7.2 分期方案 §3） |
| [SCRIPT_NODE_EXAMPLES.md](./SCRIPT_NODE_EXAMPLES.md) | 脚本节点使用样例 |
