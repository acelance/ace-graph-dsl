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
npm version 1.0.2 --no-git-tag-version
```

已发布过的版本号不可复用；当前 npmjs `latest` 为 `1.0.1` 时，下一次发删除图/回收箱等能力请升到 `1.0.2` 及以上。

---

## 3. 安装、构建、发布到 npmjs

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\ace-graph-dsl\ace-graph-dsl-ui

npm ci
npm run build

# 必须带 registry，避免被上级 .npmrc 指到 GitHub Packages
# 账号开了 2FA 时：要么加 --otp，要么用「可绕过 2FA」的 Granular Token（见下节）
npm publish --registry=https://registry.npmjs.org --access public --otp=123456
```

`prepublishOnly` 会再执行一次 `vite build`，属正常。

---

## 3.1 发布时的 2FA（E403：Two-factor authentication … required）

`npm whoami` 成功**不等于**能 publish。开了 2FA 后，普通登录 token 发包装会被拒。官方只认两种方式（**不能**关掉组织强制 2FA）：

### 方式 A：一次性验证码（本机手动发版，推荐）

打开手机 Authenticator，取当前 6 位码：

```powershell
npm publish --registry=https://registry.npmjs.org --access public --otp=123456
```

### 方式 B：Granular Access Token（自动化 / 不想每次输 OTP）

1. 浏览器打开 [npm Access Tokens](https://www.npmjs.com/settings/~/tokens) → **Generate New Token** → **Granular Access Token**
2. 勾选：
   - **Packages**：对 `@acelance/graph-dsl-ui`（或整个 `@acelance`）勾 **Read and write**
   - **Bypass two-factor authentication**（或文案类似 “Ignore 2FA / Bypass 2FA for automation”）
3. 生成后复制 `npm_…` token（只显示一次）
4. 本机写进用户级 `.npmrc`（**不要**提交到 git）：

```powershell
# 用户目录，例如 C:\Users\<你>\.npmrc
npm config set //registry.npmjs.org/:_authToken=npm_你的Token --location=user
```

之后同一台机器可直接：

```powershell
npm publish --registry=https://registry.npmjs.org --access public
```

无需 `--otp`。Token 泄露等于可发版，定期轮换；CI 用环境变量 `NPM_TOKEN` 注入，勿写进仓库。

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
"@acelance/graph-dsl-ui": "^1.0.2"
```

安装时用官方源（不要走 GitHub Packages 的 `@acelance` 映射）：

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\lesso-ai-project\lesso-ai-platform-agent-designer-web
npm i @acelance/graph-dsl-ui@1.0.2 --registry=https://registry.npmjs.org
```

联调开发仍可用 `file:../../ace-graph-dsl/ace-graph-dsl-ui`，不必等 npm。

---

**最短一条龙（本地已是 1.0.2、已登录、带 OTP）：**

```powershell
cd d:\MyWorkSpace\ace-graph-dsl-p2\ace-graph-dsl\ace-graph-dsl-ui
npm publish --registry=https://registry.npmjs.org --access public --otp=123456
```