# Ace Graph DSL — 多脚本引擎完善实施方案（SpEL / QLExpress / Groovy）

> 对应：`FUTURE_OPTIMIZATION_PLAN.md` §7.2  
> 版本：v2.1  
> 日期：2026-07-07  
> 状态：Phase A / B / C 已完成（Aviator + SpEL + QLExpress + Groovy 四引擎落地，含元数据 API、可选模块分包、条件装配、安全加固与单测）

---

## 1. 目标与范围

### 1.1 目标

在保持 `ScriptEngine` SPI 不变的前提下，完善 **SpEL**、落地 **QLExpress**、安全可控地引入 **Groovy Sandbox**，并补齐元数据 API、前端编辑器、配置开关、测试与文档，使脚本节点与脚本条件边可按场景选择引擎。

### 1.2 不在本期范围

- 引入 JavaScript / Python 等 JVM 外引擎
- 脚本 IDE（语法高亮、断点调试）——仅做 `multiLine` 多行 textarea 增强
- 脚本版本 diff / 在线协作编辑

---

## 2. 现状与差距

### 2.1 已实现

| 组件 | 状态 | 说明 |
|------|------|------|
| `ScriptEngine` 接口 | ✅ | `engineId` / `validate` / `compile` / `execute` |
| `ScriptEngineRegistry` | ✅ | 收集 Spring Bean，按 engineId 路由 |
| `AviatorScriptEngine` | ✅ | 超时 + 共享线程池 + 白名单 `state`/`config` |
| `SpelScriptEngine` | 🔶 基础版 | 已注册（`spel-enabled` 默认 true）；**无超时隔离、无安全加固、无单测** |
| `ScriptOutputNormalizer` | ✅ | 统一 Map / 单 outputKey 标量 返回值 |
| `GET /nodes/engines` | 🔶 | 仅返回 `id`/`label`，无 `multiLine` 等元数据 |
| 前端 `ScriptNodeEditor` | 🔶 | 已动态拉引擎列表；脚本区固定 6 行 textarea，无引擎提示 |

### 2.2 未实现

> Phase A/B/C 已落地：Aviator、SpEL（加固）、QLExpress、Groovy 四引擎；`ScriptEngineDescriptor`、`AbstractTimeoutScriptEngine`、SpEL 安全加固、optional 子模块拆分、各引擎单测均已实现。

| 组件 | 优先级 | 状态 |
|------|--------|------|
| 条件边 `conditionEngine` 前端选择器 | P1 | 后端已就绪（`GraphEdge.conditionEngine` + `ScriptEdgeActionFactory` 分发）；前端条件边编辑 UI 待实现 |
| 各引擎示例文档 | P0–P2 | 待补充 `SCRIPT_NODE_EXAMPLES.md` |

---

## 3. 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│  ScriptNodeEditor / PropertyPanel（前端）                    │
│    GET /nodes/engines → [{ id, label, multiLine, hint }]    │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  ScriptNodeService / ScriptEdgeActionFactory / GraphValidator│
│    engineRegistry.require(engineId)                          │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│  ScriptEngineRegistry                                        │
│    Map<engineId, ScriptEngine>                               │
│    Map<engineId, ScriptEngineDescriptor>  ← 新增             │
└───┬─────────┬──────────────┬────────────────────────────────┘
    │         │              │
 Aviator    SpEL         QLExpress        Groovy
 (core)   (core 加固)  (script-qlexpress) (script-groovy, opt-in)
    │         │              │              │
    └─────────┴──────────────┴──────────────┘
              AbstractTimeoutScriptEngine（超时 + 共享线程池）
              ScriptOutputNormalizer（统一出参 Map）
```

### 3.1 新增：`ScriptEngineDescriptor`

```java
public record ScriptEngineDescriptor(
    String id,           // aviator | spel | qlexpress | groovy
    String label,        // 显示名
    boolean multiLine,   // 是否推荐多行编辑器
    int maxScriptLines,  // 建议上限（软提示，硬限制仍用 maxScriptSizeBytes）
    String hintKey       // i18n 提示文案 key
) {}
```

`ScriptEngine` 增加默认方法（向后兼容）：

```java
default ScriptEngineDescriptor descriptor() {
    return new ScriptEngineDescriptor(engineId(), engineId(), false, 1, null);
}
```

各实现覆盖 `descriptor()` 返回完整元数据。

### 3.2 新增：`AbstractTimeoutScriptEngine`

抽取 Aviator 已有的执行模式，供 SpEL / QLExpress / Groovy 复用：

```java
abstract class AbstractTimeoutScriptEngine implements ScriptEngine, AutoCloseable {
    protected final long executionTimeoutMs;
    protected final ExecutorService executor;
    // submit + Future.get(timeout) + 统一异常包装
    protected abstract Object doExecute(Object compiled, ScriptExecutionContext ctx);
}
```

**SpEL 现状问题**：同步 `getValue()` 无超时，恶意或死循环表达式可阻塞编译线程。加固后 SpEL 也走 `AbstractTimeoutScriptEngine`。

### 3.3 Maven 分包与模块划分

#### 3.3.1 设计原则

| 原则 | 说明 |
|------|------|
| SPI 稳定 | `ScriptEngine` / `ScriptEngineRegistry` / `ScriptOutputNormalizer` 留在 **core**，所有引擎实现依赖 core |
| 默认轻量 | starter 传递依赖仅含 **core + persistence**；Aviator、SpEL 随 core 默认可用 |
| 按需扩展 | QLExpress、Groovy 独立 **optional 子模块**，宿主显式引入后才加载第三方 JAR |
| 条件装配 | starter 对 optional 模块使用 `<optional>true</optional>` + `@ConditionalOnClass` + `@ConditionalOnProperty` |
| 拆分时机 | **Phase A（SpEL 加固）不拆模块**；Phase B 新建 qlexpress；Phase C 新建 groovy |

#### 3.3.2 模块树（当前 → 目标）

**当前**（`ace-graph-dsl-backend/pom.xml`）：

```
ace-graph-dsl-backend/          (packaging: pom)
├── ace-graph-dsl-core
├── ace-graph-dsl-persistence
└── ace-graph-dsl-spring-boot-starter
```

**目标**（Phase B/C 完成后）：

```
ace-graph-dsl-backend/
├── ace-graph-dsl-core                          ← 必选，不变 artifactId
├── ace-graph-dsl-script-qlexpress              ← Phase B 新建
├── ace-graph-dsl-script-groovy                 ← Phase C 新建
├── ace-graph-dsl-persistence
└── ace-graph-dsl-spring-boot-starter
```

#### 3.3.3 各模块职责与依赖

| 模块 | artifactId | 传递依赖 | 包含内容 |
|------|------------|----------|----------|
| 核心 | `ace-graph-dsl-core` | 是（经 starter） | `ScriptEngine` SPI、`ScriptEngineDescriptor`、`AbstractTimeoutScriptEngine`、`ScriptEngineRegistry`、`ScriptOutputNormalizer`、`AviatorScriptEngine`、`SpelScriptEngine`（加固后） |
| QLExpress | `ace-graph-dsl-script-qlexpress` | **否**（optional） | `QlExpressScriptEngine`、QLExpress 专属单测 |
| Groovy | `ace-graph-dsl-script-groovy` | **否**（optional） | `GroovySandboxScriptEngine`、`GroovyScriptCompiler`、安全单测 |
| 持久化 | `ace-graph-dsl-persistence` | 是 | 不变 |
| 启动器 | `ace-graph-dsl-spring-boot-starter` | 是 | AutoConfiguration、Web API、对 optional 模块的 **optional 依赖** |

**SpEL 不单独拆模块的原因**：

- `spring-context`（SpEL 运行时）已是 core 传递依赖，无额外第三方 JAR
- 默认开启（`spel-enabled` 默认 true），与 Aviator 同为「基础引擎」
- Phase A 仅在 core 内加固，避免过早增加模块数

> v1.0 曾设想 `ace-graph-dsl-script-spel`，**v2.x 废弃该方案**，SpEL 长期驻留 core。

#### 3.3.4 Java 包名规范

| 位置 | 包名 | 示例类 |
|------|------|--------|
| core（SPI 与内置引擎） | `io.acelance.graph.dsl.script` | `ScriptEngine`、`AviatorScriptEngine`、`SpelScriptEngine` |
| qlexpress 模块 | `io.acelance.graph.dsl.script.qlexpress` | `QlExpressScriptEngine` |
| groovy 模块 | `io.acelance.graph.dsl.script.groovy` | `GroovySandboxScriptEngine` |
| starter 自动配置 | `io.acelance.graph.dsl.autoconfigure` | `AceGraphDslScriptQlexpressAutoConfiguration`（Phase B 新增） |

扩展模块 **不** 修改 core 包内已有类；通过 Spring Bean 注册接入 `ScriptEngineRegistry`。

#### 3.3.5 父 POM 变更

`ace-graph-dsl-backend/pom.xml` 扩展 `<modules>` 与 `dependencyManagement`：

```xml
<modules>
    <module>ace-graph-dsl-core</module>
    <module>ace-graph-dsl-script-qlexpress</module>   <!-- Phase B -->
    <module>ace-graph-dsl-script-groovy</module>      <!-- Phase C -->
    <module>ace-graph-dsl-persistence</module>
    <module>ace-graph-dsl-spring-boot-starter</module>
</modules>

<dependencyManagement>
    <dependencies>
        <!-- 已有 core / persistence -->
        <dependency>
            <groupId>io.acelance</groupId>
            <artifactId>ace-graph-dsl-script-qlexpress</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.acelance</groupId>
            <artifactId>ace-graph-dsl-script-groovy</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>QLExpress</artifactId>
            <version>3.3.4</version>   <!-- 以 properties / BOM 锁定为准 -->
        </dependency>
        <!-- groovy 版本跟随 spring-boot-starter-parent BOM -->
    </dependencies>
</dependencyManagement>
```

#### 3.3.6 子模块 POM 骨架

**ace-graph-dsl-script-qlexpress/pom.xml**：

```xml
<artifactId>ace-graph-dsl-script-qlexpress</artifactId>
<dependencies>
    <dependency>
        <groupId>io.acelance</groupId>
        <artifactId>ace-graph-dsl-core</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba</groupId>
        <artifactId>QLExpress</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**ace-graph-dsl-script-groovy/pom.xml**：

```xml
<artifactId>ace-graph-dsl-script-groovy</artifactId>
<dependencies>
    <dependency>
        <groupId>io.acelance</groupId>
        <artifactId>ace-graph-dsl-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.groovy</groupId>
        <artifactId>groovy</artifactId>
    </dependency>
    <!-- test 同上 -->
</dependencies>
```

#### 3.3.7 Starter 依赖与自动配置

`ace-graph-dsl-spring-boot-starter/pom.xml` 增加 **optional** 依赖（不传递给宿主）：

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-script-qlexpress</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-script-groovy</artifactId>
    <optional>true</optional>
</dependency>
```

自动配置拆分（与现有 `AceGraphDslAutoConfiguration` 中 Aviator / SpEL Bean 并存）：

| 配置类 | 条件 | 注册 Bean |
|--------|------|-----------|
| `AceGraphDslAutoConfiguration` | 始终 | `aviatorScriptEngine`、`spelScriptEngine` |
| `AceGraphDslScriptQlexpressAutoConfiguration` | `@ConditionalOnClass` + `qlexpress-enabled=true` | `qlExpressScriptEngine` |
| `AceGraphDslScriptGroovyAutoConfiguration` | `@ConditionalOnClass` + `groovy-enabled=true` | `groovySandboxScriptEngine` |

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 追加两行导入。

#### 3.3.8 宿主应用引入方式

**场景 A — 仅 Aviator + SpEL（默认，最常见）**

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-spring-boot-starter</artifactId>
</dependency>
```

无需额外依赖；classpath 无 QLExpress / Groovy JAR 时对应 AutoConfiguration 不生效。

**场景 B — 需要 QLExpress**

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-script-qlexpress</artifactId>
</dependency>
```

```yaml
ace.graph.dsl.script:
  qlexpress-enabled: true   # 默认 true，可省略
```

**场景 C — 需要 Groovy（需安全评审后）**

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-script-groovy</artifactId>
</dependency>
```

```yaml
ace.graph.dsl.script:
  groovy-enabled: true      # 默认 false，必须显式开启
```

**场景 D — 非 Spring Boot 宿主（仅用 core）**

```xml
<dependency>
    <groupId>io.acelance</groupId>
    <artifactId>ace-graph-dsl-core</artifactId>
</dependency>
<!-- 按需手动 new QlExpressScriptEngine 并 register 到 ScriptEngineRegistry -->
```

#### 3.3.9 依赖关系图

```
                    ┌─────────────────────────────┐
                    │  宿主 Spring Boot 应用       │
                    └──────────────┬──────────────┘
                                   │ compile
                    ┌──────────────▼──────────────┐
                    │ ace-graph-dsl-spring-boot-  │
                    │ starter                     │
                    └──┬────────────┬─────────────┘
                       │            │ optional（不传递）
              compile  │            ├──────────────────────┐
                       │            │                      │
         ┌─────────────▼──┐  ┌──────▼────────────┐  ┌──────▼─────────────┐
         │ ace-graph-dsl- │  │ script-qlexpress  │  │ script-groovy      │
         │ core           │  │ → QLExpress JAR   │  │ → groovy JAR       │
         │ Aviator+SpEL   │  └───────────────────┘  └────────────────────┘
         └────────┬───────┘
                  │ compile
         ┌────────▼───────┐
         │ persistence    │
         └────────────────┘
```

#### 3.3.10 实施检查清单

| Phase | 模块动作 |
|-------|----------|
| A | 仅在 `core` 内新增 `AbstractTimeoutScriptEngine`、`ScriptEngineDescriptor`，加固 `SpelScriptEngine` |
| B | 新建 `ace-graph-dsl-script-qlexpress`；parent / starter / AutoConfiguration.imports 登记 |
| C | 新建 `ace-graph-dsl-script-groovy`；同上；**不**将 groovy 加入任何模块的默认传递依赖 |

---

## 4. 三引擎实施方案

### 4.1 SpEL — 加固完善（Phase A，2 人日）

**现状**：`SpelScriptEngine` 已存在，功能可用但不符合统一安全/超时标准。

#### 4.1.1 代码改动

| 项 | 内容 |
|----|------|
| 继承基类 | `SpelScriptEngine extends AbstractTimeoutScriptEngine` |
| 编译模式 | `SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, ...)` |
| 上下文 | `StandardEvaluationContext` 仅注册 `#state`、`#config`（Map） |
| 安全 | `setTypeLocator(typeName -> { throw ... })` 禁用 `T(java.lang.Runtime)` |
| 安全 | 不注册 `BeanFactoryResolver`，禁止 `@bean` 引用 |
| 返回值 | 与 Aviator 一致：Map 或单 key 标量，经 `ScriptOutputNormalizer` |

#### 4.1.2 脚本约定（写入 `SCRIPT_NODE_EXAMPLES.md`）

```spel
// 条件路由（脚本条件边）
#state['score'] > 60 ? 'pass' : 'reject'

// 节点输出 Map
{#normalized_query: #state['query']?.trim()?.toLowerCase()}

// 单 outputKey 标量
#state['query']?.trim()
```

#### 4.1.3 测试用例

- `SpelScriptEngineTest`：语法错误、正常 Map 返回、单 key 标量、超时中断、`T(Runtime)` 被拒绝
- `ScriptEdgeActionFactoryTest` 增加 `conditionEngine: spel` 用例

#### 4.1.4 验收标准

- [ ] 设计器可选 SpEL，校验/试跑/发布全链路通过
- [ ] 执行超时与 Aviator 共用 `executionTimeoutMs`
- [ ] 无法通过 SpEL 加载任意 Java 类

---

### 4.2 QLExpress — 新建落地（Phase B，4 人日）

#### 4.2.1 模块与依赖

模块划分、POM 骨架、宿主引入方式见 **§3.3**。本 Phase 交付：

```
ace-graph-dsl-script-qlexpress/
  pom.xml
  src/main/java/io/acelance/graph/dsl/script/qlexpress/QlExpressScriptEngine.java
  src/test/java/.../QlExpressScriptEngineTest.java
```

starter 侧新增 `AceGraphDslScriptQlexpressAutoConfiguration`（§3.3.7），条件注册 Bean：

```java
@Bean
@ConditionalOnClass(name = "com.ql.util.express.ExpressRunner")
@ConditionalOnProperty(prefix = "ace.graph.dsl.script", name = "qlexpress-enabled", havingValue = "true", matchIfMissing = true)
public ScriptEngine qlExpressScriptEngine(AceGraphDslProperties properties) {
    return new QlExpressScriptEngine(properties.getScript().getExecutionTimeoutMs(),
                                     properties.getScript().getExecutionPoolSize());
}
```

#### 4.2.2 引擎实现要点

| 项 | 措施 |
|----|------|
| Runner 单例 | `ExpressRunner` 复用，禁止 `import` |
| 安全策略 | `QLExpressRunStrategy.RiskControlStrategy` 关闭危险操作 |
| 上下文 | `IExpressContext` 仅 `state`、`config` 两个变量 |
| 多行脚本 | 支持 `if/else`、`for`、中间变量、`return` Map |
| 出参 helper | 注册自定义函数 `map(k1,v1,...)` 简化返回（与 Aviator `seq.map` 对齐文档） |
| 执行 | `AbstractTimeoutScriptEngine` 包装 `executeOuter` |
| 编译缓存 | `InstructionSet` 缓存在 compile 结果中 |

#### 4.2.3 脚本约定

```javascript
// 多行业务规则
amount = state.amount;
discount = state.discount > 0 ? state.discount : 1.0;
price = amount * discount;
if (state.member == 'vip') {
  price = price * 0.9;
}
return map('final_price', price);
```

#### 4.2.4 配置项（`AceGraphDslProperties.Script`）

```yaml
ace.graph.dsl.script:
  qlexpress-enabled: true          # 默认 true（引入 optional 模块后）
  qlexpress-max-loop-count: 10000  # 防死循环
```

#### 4.2.5 测试用例

- 语法校验失败 / 成功
- 多行 if/else 返回 Map
- 超时被中断
- `import java.io.File` 被拒绝
- 与 `ScriptRegisteredGraphNode` 集成试跑

#### 4.2.6 验收标准

- [ ] `GET /nodes/engines` 含 `qlexpress`，`multiLine: true`
- [ ] 前端切换 QLExpress 后脚本区扩展为 12+ 行 textarea
- [ ] 脚本节点 CRUD + 图发布编译通过
- [ ] 条件边 `conditionEngine: qlexpress` 可用

---

### 4.3 Groovy Sandbox — 安全可控引入（Phase C，6 人日 + 安全评审）

#### 4.3.1 原则

- **默认关闭**：`groovy-enabled: false`
- **独立 optional 模块**，不进入默认 starter 传递依赖
- **上线前必过**安全评审 checklist（§7）

#### 4.3.2 模块结构

模块划分与 POM 见 **§3.3.6–§3.3.8**。本 Phase 交付：

```
ace-graph-dsl-script-groovy/
  src/main/java/io/acelance/graph/dsl/script/groovy/
    GroovySandboxScriptEngine.java
    GroovyScriptCompiler.java       // SecureASTCustomizer + 编译缓存
  src/test/java/.../GroovySandboxScriptEngineTest.java
```

依赖：`org.apache.groovy:groovy`（与 Spring Boot BOM 对齐）。自动配置见 `AceGraphDslScriptGroovyAutoConfiguration`（§3.3.7）。

#### 4.3.3 沙箱层级

| 层级 | 实现 |
|------|------|
| AST | `SecureASTCustomizer`：禁止 `System`、`Runtime`、`ProcessBuilder`、`Class.forName`、反射 API |
| 导入 | `ImportCustomizer` 白名单：`java.util.*`、`java.math.*`、`java.time.*` |
| 闭包 | 禁止 `evaluate`、`GroovyShell` 嵌套执行 |
| ClassLoader | 独立 `GroovyClassLoader`，不委托应用 ClassLoader 加载业务类 |
| 执行 | `AbstractTimeoutScriptEngine` + `InvokerHelper` 调用预编译 `Script` |
| 编译缓存 | `ConcurrentHashMap<scriptHash, Class<? extends Script>>`，上限可配置 |

#### 4.3.4 脚本约定

```groovy
def q = (state.query as String)?.trim()?.toLowerCase()
def score = Math.max(0, Math.min(100, state.rawScore as Integer))

def grouped = (state.items as List)
    ?.groupBy { it.category }
    ?.collectEntries { k, v -> [k, v.size()] } ?: [:]

return [
  normalized_query: q,
  normalized_score: score,
  group_counts: grouped
]
```

#### 4.3.5 配置项

```yaml
ace.graph.dsl.script:
  groovy-enabled: false            # 默认关闭
  groovy-max-script-cache: 200       # 编译缓存条目上限
  groovy-allowed-imports:            # 可扩展白名单
    - java.util.*
    - java.math.*
    - java.time.*
```

#### 4.3.6 安全评审交付物

- [ ] 渗透测试用例集（尝试读文件、执行命令、反射加载类）
- [ ] 资源限制验证（超时、线程池拒绝、脚本大小）
- [ ] 运维开启指南（何时允许 `groovy-enabled=true`）

#### 4.3.7 验收标准

- [ ] 默认配置下 `GET /nodes/engines` **不**返回 groovy
- [ ] 显式开启后可用，复杂集合脚本试跑通过
- [ ] 安全测试用例 100% 拦截

---

## 5. 配套工程清单

### 5.1 后端 API

#### `GET /nodes/engines` 响应升级

```json
[
  {
    "id": "aviator",
    "label": "Aviator 表达式",
    "multiLine": false,
    "maxScriptLines": 3,
    "hint": "单行表达式；返回 seq.map('key', value) 或标量"
  },
  {
    "id": "spel",
    "label": "SpEL 表达式",
    "multiLine": false,
    "maxScriptLines": 3,
    "hint": "使用 #state['key'] / #config['key']；Map 用 {#k: v}"
  },
  {
    "id": "qlexpress",
    "label": "QLExpress",
    "multiLine": true,
    "maxScriptLines": 100,
    "hint": "支持多行 if/else；return map('key', value)"
  },
  {
    "id": "groovy",
    "label": "Groovy",
    "multiLine": true,
    "maxScriptLines": 200,
    "hint": "支持集合操作；return [key: value]；需管理员开启"
  }
]
```

实现：`ScriptNodeController.listEngines()` 改为 `engineRegistry.listDescriptors()`。

#### 校验与试跑

| 入口 | 改动 |
|------|------|
| `POST /nodes/validate-script` | body 含 `engine`，已有；错误信息前缀带引擎名 |
| `POST /nodes/test-run` | 已有；各引擎 mockState 行为一致 |
| `GraphValidator` 脚本条件边 | `conditionEngine` 分发到对应引擎 validate |
| `ScriptNodeService.create/update` | 引擎不存在时 400 + 可用引擎列表 |

### 5.2 前端（ace-graph-dsl-ui）

| 文件 | 改动 |
|------|------|
| `ScriptNodeEditor.vue` | 根据选中引擎 `multiLine` 动态 `:rows`（3 vs 14）；展示 `hint`；切换引擎时可选确认（防误覆盖脚本） |
| `PropertyPanel.vue` | 条件边编辑区增加 `conditionEngine` 下拉（调用 `/nodes/engines`，过滤 `multiLine=false` 的引擎优先） |
| `api/graph.js` | 类型注释补充 `EngineMeta` 字段 |
| `i18n` | 各引擎 hint 文案（zh-CN / en-US） |
| `SCRIPT_NODE_EXAMPLES.md` 链接 | 编辑器底部增加「查看示例」外链 |

### 5.3 配置与元数据

`AceGraphDslProperties.Script` 扩展：

```java
private boolean qlexpressEnabled = true;
private boolean groovyEnabled = false;
private int qlexpressMaxLoopCount = 10_000;
private int groovyMaxScriptCache = 200;
```

`spring-configuration-metadata.json` 同步生成说明。

### 5.4 测试矩阵

| 测试类 | 覆盖 |
|--------|------|
| `SpelScriptEngineTest` | Phase A |
| `QlExpressScriptEngineTest` | Phase B |
| `GroovySandboxScriptEngineTest` | Phase C + 安全用例 |
| `ScriptEngineRegistryTest` | 开关注册 / listDescriptors |
| `ScriptNodeServiceIntegrationTest` | CRUD + 四引擎试跑 |
| `ScriptEdgeActionFactoryTest` | 各引擎条件边 |

### 5.5 文档

| 文档 | 更新内容 |
|------|----------|
| `SCRIPT_NODE_EXAMPLES.md` | 每引擎 2–3 个可复制样例（节点 + 条件边） |
| `FUTURE_OPTIMIZATION_PLAN.md` §7.2 | 进度表拆分 Phase A/B/C |
| `PROJECT_OVERVIEW.md` | 脚本引擎架构图 |
| `CHANGELOG.md` | 按 Phase 发版记录 |
| UI `README` 快问快答 | 「如何选择脚本引擎」 |

---

## 6. 统一契约与兼容性

### 6.1 脚本返回值（所有引擎必须遵守）

经 `ScriptOutputNormalizer.normalize(result, outputKeys)`：

1. 返回 `Map<String, Object>`（推荐）
2. 或单 `outputKey` 时返回标量
3. `null` 视为错误

各引擎文档须说明各自 Map 构造语法（Aviator `seq.map`、SpEL `{#k: v}`、QLExpress `map()`、Groovy `[k: v]`）。

### 6.2 条件边返回值

脚本条件边表达式返回值 **toString()** 后作为 `mapping` 的 key（与现 Aviator 行为一致）。文档中明确四引擎示例。

### 6.3 向后兼容

- 未指定 `engine` 的 DSL 仍默认 `aviator`（`defaultEngine` 可配置）
- 已有 `script:*` 节点无需迁移
- `GET /nodes/engines` 新增字段为扩展，旧前端忽略即可

---

## 7. 安全清单（上线门禁）

| # | 项 | SpEL | QLExpress | Groovy |
|---|-----|------|-----------|--------|
| 1 | 执行超时 | ✅ 基类 | ✅ 基类 | ✅ 基类 |
| 2 | 线程池隔离 | ✅ 共享池 | ✅ 共享池 | ✅ 共享池 |
| 3 | 脚本大小上限 | ✅ | ✅ | ✅ |
| 4 | 白名单上下文 state/config | ✅ | ✅ | ✅ |
| 5 | 禁止任意类加载 | ✅ 禁 TypeLocator | ✅ 禁 import | ✅ SecureAST |
| 6 | 禁止 IO / 进程 / 反射 | N/A（无 API） | ✅ RiskControl | ✅ AST 黑名单 |
| 7 | 引擎级开关 | `spel-enabled` | `qlexpress-enabled` | `groovy-enabled` 默认 false |
| 8 | 审计日志 | 创建/更新/删除已有 | 同左 | 同左 |

---

## 8. 实施排期与里程碑

```
Week 1 ── Phase A：SpEL 加固 + ScriptEngineDescriptor + AbstractTimeoutScriptEngine
              + /engines API 升级 + 前端 multiLine 切换

Week 2 ── Phase B：ace-graph-dsl-script-qlexpress 模块 + 单测 + 文档样例
              + 条件边 conditionEngine 前端

Week 3-4 ─ Phase C：ace-graph-dsl-script-groovy 模块 + 沙箱 + 安全评审
              + 集成测试 + CHANGELOG 1.1.0
```

### 里程碑验收

| 里程碑 | 交付 | 版本建议 |
|--------|------|----------|
| M1 | SpEL 加固 + 元数据 API + 前端 multiLine | 1.0.4 |
| M2 | QLExpress 全链路 | 1.1.0 |
| M3 | Groovy Sandbox（可选开启） | 1.2.0 |

---

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| Groovy 沙箱绕过 | 默认关闭；独立安全评审；CI 跑攻击用例 |
| QLExpress 版本漏洞 | 依赖 BOM 锁定；Dependabot 监控 |
| SpEL 表达式阻塞 | 迁入 `AbstractTimeoutScriptEngine` |
| 多引擎导致 starter 体积膨胀 | optional 模块 + 条件自动配置 |
| 前端切换引擎覆盖脚本 | 切换前 `ElMessageBox.confirm` |
| 出参格式不一致 | `ScriptOutputNormalizer` 统一 + 文档明示 |

---

## 10. 相关文档

| 文档 | 说明 |
|------|------|
| [FUTURE_OPTIMIZATION_PLAN.md](./FUTURE_OPTIMIZATION_PLAN.md) | 总体规划 §7.2 |
| [SCRIPT_NODE_EXAMPLES.md](./SCRIPT_NODE_EXAMPLES.md) | 脚本样例（待按引擎扩充） |
| [NODE_FLEXIBILITY_EXPLORATION.md](./NODE_FLEXIBILITY_EXPLORATION.md) | 节点灵活性背景 |
| [REMAINING_ITEMS_PLAN.md](./REMAINING_ITEMS_PLAN.md) | 剩余项 |
