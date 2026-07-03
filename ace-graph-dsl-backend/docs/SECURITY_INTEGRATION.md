# Spring Security 集成指南

> 适用场景：宿主系统使用 Spring Security 作为认证/授权框架，需要将 Ace Graph DSL 的权限 SPI
> 映射到 Spring Security 的 `Authentication` / `GrantedAuthority` 机制。

---

## 1. 前置阅读

Ace Graph DSL 提供了两层权限抽象：

| 抽象 | 粒度 | 文档 |
|------|------|------|
| `GraphMenuAccessControl` | 菜单/功能按钮（新建、保存、校验、预览、发布、回滚、脚本节点） | [MENU_PERMISSION_INTEGRATION.md](./MENU_PERMISSION_INTEGRATION.md) |
| `GraphNodeAccessControl` | 业务节点/Dispatcher/脚本节点资源 | [FUTURE_OPTIMIZATION_PLAN.md](./FUTURE_OPTIMIZATION_PLAN.md) §1.1 |

本文聚焦 **Spring Security 与 `GraphMenuAccessControl` 的集成**。

`GraphNodeAccessControl` 集成模式一致，只需实现 `accessibleNodes()` / `accessibleDispatchers()` / `canManageScriptNode()` 并从 `Authentication.getAuthorities()` 解析即可，不在本文赘述。

---

## 2. 架构概览

```
┌─────────────────────────────────────────────────────┐
│  浏览器                                                │
│  Authorization: Bearer <JWT>                         │
└────────────────────┬────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────┐
│  Spring Security Filter Chain                         │
│  ├─ JwtAuthenticationFilter (宿主自定义)                │
│  ├─ SecurityContextHolder.setAuthentication(...)      │
│  └─ GrantedAuthority: "ACE_GRAPH_PUBLISH"             │
└────────────────────┬────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────┐
│  Ace Graph DSL                                        │
│  ├─ SecurityMenuAccessControl.isMenuGranted(key)      │
│  │   └─ SecurityContextHolder.getAuthentication()     │
│  │      .getAuthorities()                             │
│  ├─ MenuPermissionGuard.require(key)                  │
│  └─ CachingGraphMenuAccessControl (请求级缓存)         │
└─────────────────────────────────────────────────────┘
```

---

## 3. 菜单权限映射

### 3.1 标准菜单 key 与建议的 Spring Security Authority

| Ace Graph DSL Key | Spring Security Authority | 说明 |
|-------------------|--------------------------|------|
| `graph:view` | `ACE_GRAPH_VIEW` | 查看图 |
| `graph:create` | `ACE_GRAPH_CREATE` | 新建图 |
| `graph:save` | `ACE_GRAPH_SAVE` | 保存草稿 |
| `graph:validate` | `ACE_GRAPH_VALIDATE` | 校验 |
| `graph:preview` | `ACE_GRAPH_PREVIEW` | 预览 |
| `graph:publish` | `ACE_GRAPH_PUBLISH` | 发布 |
| `graph:rollback` | `ACE_GRAPH_ROLLBACK` | 回滚 |
| `script-node:view` | `ACE_SCRIPT_NODE_VIEW` | 查看脚本节点 |
| `script-node:create` | `ACE_SCRIPT_NODE_CREATE` | 新建脚本节点 |
| `script-node:delete` | `ACE_SCRIPT_NODE_DELETE` | 删除脚本节点 |
| `script-node:test` | `ACE_SCRIPT_NODE_TEST` | 校验/试跑 |

> 命名约定：`ACE_` + key 中 `:` 和 `-` 替换为 `_` 并大写。
> 宿主可按自身权限体系自定义映射规则，此表仅为建议。

---

## 4. 接入示例

### 4.1 实现 `GraphMenuAccessControl`

```java
package com.example.myapp.config;

import io.acelance.graph.dsl.security.menu.GraphMenuAccessControl;
import io.acelance.graph.dsl.security.menu.GraphMenuPermissions;
import io.acelance.graph.dsl.security.menu.MenuPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 基于 Spring Security GrantedAuthority 的菜单权限实现。
 * <p>
 * 约定：Authority 命名规则为 ACE_ + key.toUpperCase().replace(':', '_').replace('-', '_')
 * 例如 graph:publish → ACE_GRAPH_PUBLISH
 * </p>
 */
@Component
public class SecurityMenuAccessControl implements GraphMenuAccessControl {

    @Override
    public boolean isMenuGranted(String menuKey) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String authority = toAuthority(menuKey);
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }

    @Override
    public Optional<MenuPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        // 从 JWT claims / UserDetails 提取用户信息
        String name = auth.getName();
        Set<String> roles = auth.getAuthorities().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        return Optional.of(new MenuPrincipal(name, name, roles));
    }

    private String toAuthority(String menuKey) {
        return "ACE_" + menuKey.toUpperCase().replace(':', '_').replace('-', '_');
    }
}
```

> 提示：若宿主使用 `@PreAuthorize` 注解或方法级拦截，可在 Controller 层加
> `@PreAuthorize("hasAuthority('ACE_GRAPH_PUBLISH')")` 作为第二层兜底。
> Ace Graph DSL 内置的 `MenuPermissionGuard.require()` 已对写操作做服务端校验，
> 二者可互补。

### 4.2 缓存配置

请求级权限缓存 `CachingGraphMenuAccessControl` 默认启用，无需额外配置。
如需关闭（例如每次判权都需实时查询外部系统）：

```yaml
ace:
  graph:
    dsl:
      access-control:
        cache-enabled: false
```

### 4.3 CORS 配置

Ace Graph DSL 内置可选 CORS，与 Spring Security 的 CorsFilter 互不冲突，
因为内置 CORS 仅作用于设计器的 `base-path`（默认 `/api/graph`）。

```yaml
ace:
  graph:
    dsl:
      web:
        cors:
          enabled: true
          allowed-origins: ["https://myapp.example.com"]
          allowed-methods: ["GET", "POST", "PUT", "DELETE"]
          allowed-headers: ["Authorization", "Content-Type", "X-Tenant-Id"]
          allow-credentials: true
          max-age: 3600
```

若宿主已有全局 `CorsFilter`，建议**关闭内置 CORS**（默认关闭），避免双重处理。

---

## 5. 常见问题

### 5.1 如何添加自定义菜单项？

实现 `GraphMenuCatalog` 并注册为 Bean，扩展 `DefaultGraphMenuCatalog`：

```java
@Component
public class MyMenuCatalog extends DefaultGraphMenuCatalog {
    public MyMenuCatalog() {
        super();
        // 添加自定义菜单项
        addMenuItem("graph:export", "导出", "graph");
    }
}
```

### 5.2 如何让未登录用户看到只读设计器？

`GraphMenuAccessControl` 默认实现 `PermissiveGraphMenuAccessControl` 全部放行。
若需区分登录/未登录，在 `SecurityMenuAccessControl.isMenuGranted()` 中做判断：

```java
@Override
public boolean isMenuGranted(String menuKey) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
        // 未登录：只给查看权限
        return "graph:view".equals(menuKey) || "script-node:view".equals(menuKey);
    }
    // 已登录：按 authority 判权
    String authority = toAuthority(menuKey);
    return auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals(authority));
}
```

### 5.3 写操作未被拦截？

确认以下两点：
1. `SecurityMenuAccessControl` 已注册为 `@Component`（或 `@Bean`），覆盖了默认的 `PermissiveGraphMenuAccessControl`。
2. 当前用户的 `Authentication.getAuthorities()` 中不包含对应的 `ACE_*` authority。

排查方法：在 `isMenuGranted()` 中打断点或打日志，确认 `Authentication` 对象的内容。

### 5.4 Spring Security 的 filter 顺序冲突？

Ace Graph DSL 的 `PathMatchConfigurer.addPathPrefix` 和 `CorsConfiguration` 注册在
Spring MVC 层，不影响 Security Filter Chain 的顺序。若遇到 403，优先检查：
- Security filter 是否正确放行 `/api/graph/**` 的 OPTIONS 预检请求
- 自定义 `SecurityMenuAccessControl` 的 `isMenuGranted()` 是否返回 `false`

---

## 6. 完整示例项目

参考 `spring-ai-alibaba-demo` 中的以下模块（均以第三方依赖方式引用 Ace Graph DSL）：

| 模块 | 说明 |
|------|------|
| `spring-ai-alibaba-demo-cs-reply-m2-ace` | 后端 demo：业务节点 + SSE Controller + golden DSL |
| `spring-ai-alibaba-demo-cs-reply-web-m2-ace` | 前端 demo：`<GraphDslManager>` 一行接入 |

> 这两个 demo 当前使用默认的 `PermissiveGraphMenuAccessControl`（全放行）。
> 要在 demo 中启用权限拦截，按本文 §4.1 添加 `SecurityMenuAccessControl` Bean 即可。

---

## 7. 相关文档

| 文档 | 说明 |
|------|------|
| [MENU_PERMISSION_INTEGRATION.md](./MENU_PERMISSION_INTEGRATION.md) | 菜单权限 SPI 完整说明 |
| [FUTURE_OPTIMIZATION_PLAN.md](./FUTURE_OPTIMIZATION_PLAN.md) | 整体规划与权限模型边界（§1.1、§4） |
| [LIBRARY_EMBEDDING_ROADMAP.md](./LIBRARY_EMBEDDING_ROADMAP.md) | 库化嵌入路线图 |
