目标包：`@acelance/graph-dsl-ui` → [npmjs `@acelance` packages](https://www.npmjs.com/settings/acelance/packages)

当前 `publishConfig` 已是 `registry.npmjs.org` + `access: public`。注意仓库根目录 `ace-graph-dsl/.npmrc` 把 `@acelance` 指到了 GitHub Packages，**publish 时请显式加 `--registry`**，避免发错源。

---

## 1. 登录 npm（acelance 组织权限）

浏览器先确认账号在 [npmjs acelance](https://www.npmjs.com/settings/acelance/packages) 有 **publish** 权限，然后：

```powershell
npm login --registry=https://registry.npmjs.org
# 按提示输入用户名 / 密码 / OTP（若开了 2FA）
npm whoami --registry=https://registry.npmjs.org
```

---

## 2. 升版（1.0.0 若已发过必须升）

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\ace-graph-dsl\ace-graph-dsl-ui
npm version 1.0.1 --no-git-tag-version
```

首次发 `1.0.0` 可跳过。

---

## 3. 安装、构建、发布到 npmjs

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\ace-graph-dsl\ace-graph-dsl-ui

npm ci
npm run build

# 必须带 registry，避免被上级 .npmrc 指到 GitHub Packages
npm publish --registry=https://registry.npmjs.org --access public
```

`prepublishOnly` 会再执行一次 `vite build`，属正常。

---

## 4. 核对

```powershell
npm view @acelance/graph-dsl-ui version --registry=https://registry.npmjs.org
```

浏览器：https://www.npmjs.com/package/@acelance/graph-dsl-ui  

组织列表：https://www.npmjs.com/settings/acelance/packages  

---

## 5. 业务侧改依赖（可选）

`lesso-ai-platform-agent-designer-web` 可从本地 `file:` 改为：

```json
"@acelance/graph-dsl-ui": "^1.0.1"
```

安装时用官方源（不要走 GitHub Packages 的 `@acelance` 映射）：

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\lesso-ai-project\lesso-ai-platform-agent-designer-web
npm i @acelance/graph-dsl-ui@1.0.1 --registry=https://registry.npmjs.org
```

---

**最短一条龙（已登录、要发 1.0.1）：**

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\ace-graph-dsl\ace-graph-dsl-ui
npm version 1.0.1 --no-git-tag-version
npm ci
npm publish --registry=https://registry.npmjs.org --access public
```