# 菜单 / 功能权限抽象与接入指南

> 目标：为 ace-graph-dsl 设计器提供**标准的菜单/功能权限抽象**，让宿主系统能对接自有权限框架
> （Spring Security / Shiro / Sa-Token 等）或自定义接口返回的菜单权限；**未接入时默认全部放行（true）**。
> 同时提供 REST API，为 `ace-graph-dsl-ui` 的菜单显隐与按钮禁用提供数据支撑。

---

## 1. 概念区分

库内有两层权限抽象，职责不同：

| 抽象 | 粒度 | 作用 |
|------|------|------|
| `GraphNodeAccessControl` | 节点 / Dispatcher / 脚本节点 | 控制设计器**节点面板可见的节点**、脚本节点的管理/删除（API 强校验） |
| `GraphMenuAccessControl`（本次新增） | 菜单 / 功能按钮 | 控制设计器**菜单与操作按钮的显隐/禁用**（新建、保存、校验、预览、发布、回滚、脚本节点等） |

二者均默认放行，互不依赖；宿主可只接入其中之一，或两者都接入并保持一致。

---

## 2. 后端抽象

### 2.1 SPI 接口

`io.acelance.graph.dsl.security.menu.GraphMenuAccessControl`

```java
public interface GraphMenuAccessControl {
    // 已授予的菜单 key 集合；empty 表示不限制（全放行）
    default Optional<Set<String>> grantedMenus() { return Optional.empty(); }

    // 逐项判定（默认基于 grantedMenus）
    default boolean isMenuGranted(String menuKey) {
        return grantedMenus().map(set -> set.contains(menuKey)).orElse(true);
    }

    // 当前登录主体（供 UI 展示，可选）
    default Optional<MenuPrincipal> currentPrincipal() { return Optional.empty(); }
}
```

- **默认实现** `PermissiveGraphMenuAccessControl`：全部放行。starter 通过
  `@ConditionalOnMissingBean` 注册，宿主提供自己的 Bean 即可覆盖。

### 2.2 标准菜单 key

`io.acelance.graph.dsl.security.menu.GraphMenuPermissions`

| key | 含义 | 分组 |
|-----|------|------|
| `graph:view` | 查看 Graph | graph |
| `graph:create` | 新建 Graph DSL | graph |
| `graph:save` | 保存草稿 | graph |
| `graph:validate` | 校验 | graph |
| `graph:preview` | 预览（PlantUML/Mermaid） | graph |
| `graph:publish` | 发布 | graph |
| `graph:rollback` | 回滚 | graph |
| `script-node:view` | 查看脚本节点 | script-node |
| `script-node:create` | 新建/编辑脚本节点 | script-node |
| `script-node:delete` | 删除脚本节点 | script-node |
| `script-node:test` | 校验/试跑脚本 | script-node |

### 2.3 菜单目录（可扩展）

`GraphMenuCatalog` 定义可被权限控制的菜单清单，默认 `DefaultGraphMenuCatalog` 提供上表内置项。
宿主如需**新增自定义菜单项**，提供自己的 `GraphMenuCatalog` Bean 覆盖即可。

### 2.4 解析器

`GraphMenuPermissionResolver` 将「目录 + 权限 SPI」组合为返回给前端的视图：

```java
MenuPermissionView resolve();           // 全部菜单项 + 授权结果 + 已授权 key 集合
boolean isGranted(String menuKey);      // 供后端写操作做服务端兜底校验
```

---

## 3. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/graph/permissions/menus` | 返回当前用户的菜单权限视图 |

**响应示例（默认全放行）：**

```json
{
  "principal": null,
  "menus": [
    { "key": "graph:create", "label": "新建 Graph DSL", "group": "graph", "permitted": true },
    { "key": "graph:publish", "label": "发布", "group": "graph", "permitted": true }
  ],
  "grantedKeys": ["graph:create", "graph:publish", "..."]
}
```

**接入权限框架后的示例：**

```json
{
  "principal": { "id": "1001", "name": "张三", "roles": ["cs-designer"] },
  "menus": [
    { "key": "graph:create", "label": "新建 Graph DSL", "group": "graph", "permitted": true },
    { "key": "graph:publish", "label": "发布", "group": "graph", "permitted": false }
  ],
  "grantedKeys": ["graph:create", "graph:save", "graph:validate", "graph:preview"]
}
```

---

## 4. 宿主接入示例

### 4.1 对接自有「菜单权限接口」（最常见）

```java
@Component
public class MyMenuAccessControl implements GraphMenuAccessControl {

    private final MyAuthFacade auth; // 你的权限服务

    public MyMenuAccessControl(MyAuthFacade auth) {
        this.auth = auth;
    }

    @Override
    public Optional<Set<String>> grantedMenus() {
        // 从当前登录态 / 自定义接口取菜单码，并映射到 ace 标准 key
        Set<String> codes = auth.currentUserMenuCodes();      // 例如 {"GRAPH_PUBLISH", ...}
        Set<String> mapped = codes.stream()
                .map(this::mapToAceKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return Optional.of(mapped);
    }

    @Override
    public Optional<MenuPrincipal> currentPrincipal() {
        var u = auth.currentUser();
        return Optional.of(new MenuPrincipal(u.id(), u.name(), u.roles()));
    }

    private String mapToAceKey(String bizCode) {
        return switch (bizCode) {
            case "GRAPH_PUBLISH" -> GraphMenuPermissions.GRAPH_PUBLISH;
            case "GRAPH_EDIT"    -> GraphMenuPermissions.GRAPH_SAVE;
            // ... 其余映射
            default -> null;
        };
    }
}
```

### 4.2 对接 Spring Security（逐项判定）

```java
@Component
public class SecurityMenuAccessControl implements GraphMenuAccessControl {

    @Override
    public boolean isMenuGranted(String menuKey) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        // 约定权限名为 "ACE_" + 大写 key，如 ACE_GRAPH_PUBLISH
        String authority = "ACE_" + menuKey.toUpperCase().replace(':', '_').replace('-', '_');
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }
}
```

> 提示：写操作（发布/删除等）应在后端 Controller 内用
> `GraphMenuPermissionResolver.isGranted(...)` 或既有 `GraphNodeAccessControl` 做**服务端兜底校验**，
> 前端的显隐仅用于体验，不能作为唯一防线。

---

## 5. 前端使用（ace-graph-dsl-ui）

组件库已内置接入，开箱即用：

- 新增 API：`getMenuPermissions()` → `GET /api/graph/permissions/menus`
- 新增 Pinia store：`usePermissionStore`，导出标准 key 常量 `MENU`
- 组件挂载时自动加载权限，并对按钮做显隐：
  - `GraphDslManager`：新建 Graph DSL
  - `Toolbar`：保存草稿 / 校验 / 预览 / 发布
  - `NodePanel` + `ScriptNodeEditor`：新建脚本节点 / 校验 / 试跑

**容错策略（fail-open）**：权限接口不可用（如旧版后端无该接口）时，前端**默认放行**，避免误隐藏功能。

在宿主前端自定义判定：

```js
import { usePermissionStore, MENU } from '@acelance/graph-dsl-ui'

const perm = usePermissionStore()
await perm.load()
if (perm.can(MENU.GRAPH_PUBLISH)) {
  // 显示发布入口
}
```

---

## 6. 相关代码

| 资源 | 路径 |
|------|------|
| SPI 接口 | `ace-graph-dsl-core/.../security/menu/GraphMenuAccessControl.java` |
| 默认放行实现 | `.../security/menu/PermissiveGraphMenuAccessControl.java` |
| 标准 key | `.../security/menu/GraphMenuPermissions.java` |
| 菜单目录 | `.../security/menu/GraphMenuCatalog.java` / `DefaultGraphMenuCatalog.java` |
| 解析器 | `.../security/menu/GraphMenuPermissionResolver.java` |
| REST 控制器 | `ace-graph-dsl-spring-boot-starter/.../web/GraphPermissionController.java` |
| 自动配置 | `.../autoconfigure/AceGraphDslAutoConfiguration.java` |
| 前端 store | `ace-graph-dsl-ui/src/stores/permissions.js` |
