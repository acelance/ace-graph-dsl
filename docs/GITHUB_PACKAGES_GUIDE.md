# Ace Graph DSL — GitHub 仓库与 Packages 发布指南

> 仓库地址：[https://github.com/acelance/ace-graph-dsl](https://github.com/acelance/ace-graph-dsl)  
> 适用范围：Monorepo 仅包含 `ace-graph-dsl-backend`（Maven）与 `ace-graph-dsl-ui`（npm）  
> 首阶段目标：发布到 **GitHub Packages**（公开），Maven 与 npm **可独立发版**

---

## 1. 仓库规划

### 1.1 为什么用 Monorepo

`backend` 与 `ui` 强关联，放在同一仓库 `ace-graph-dsl` 最省事：

- 版本号、Issue、PR、Release 统一管理
- GitHub Packages 的 Maven 仓库地址统一：`https://maven.pkg.github.com/acelance/ace-graph-dsl`
- npm 包 `@acelance/graph-dsl-ui` 的 scope 与 GitHub 组织 `acelance` 对齐
- 与本地工作区目录结构一致

### 1.2 目标目录结构

```
ace-graph-dsl/                    ← GitHub 仓库根
├── LICENSE                       ← Apache-2.0（远程已有）
├── README.md                     ← 项目说明 + 消费方接入
├── .gitignore                    ← 合并 backend / ui 忽略规则
├── docs/
│   └── GITHUB_PACKAGES_GUIDE.md  ← 本文档
├── .github/workflows/            ← CI / 发版（待建）
├── ace-graph-dsl-backend/        ← Maven 多模块
│   ├── ace-graph-dsl-core/
│   ├── ace-graph-dsl-persistence/
│   └── ace-graph-dsl-spring-boot-starter/
└── ace-graph-dsl-ui/             ← npm 包 @acelance/graph-dsl-ui
```

**本阶段不纳入仓库**：`spring-ai-alibaba-demo`（可另建 `ace-graph-dsl-demo` 或后续加 `examples/`）。

### 1.3 Maven 与 npm 能否独立发布？

**可以。** 同一 Git 仓库内，Maven 与 npm 在 GitHub Packages 上是**两套独立包**：

| | Maven（backend） | npm（ui） |
|--|------------------|-----------|
| 发布命令 | `mvn deploy` | `npm publish` |
| 包坐标 | `io.acelance:ace-graph-dsl-*` | `@acelance/graph-dsl-ui` |
| 版本文件 | `ace-graph-dsl-backend/pom.xml` | `ace-graph-dsl-ui/package.json` |
| 构建目录 | `ace-graph-dsl-backend/` | `ace-graph-dsl-ui/` |

- 只改 UI → 只发 npm  
- 只改 backend → 只发 Maven  
- 版本号可独立（如 backend `1.0.1`、ui `1.0.2`），首版建议对齐为 `1.0.0`

---

## 2. 阶段一：代码入库

### 2.1 合并为 Monorepo

当前若 `ace-graph-dsl-backend` / `ace-graph-dsl-ui` 各自有 `.git`，需去掉子目录 git 元数据，在仓库根保留**单一** Git 历史。

**推荐步骤（Windows PowerShell）：**

```powershell
# 1. 克隆远程骨架仓库
cd D:\MyWorkSpace
git clone https://github.com/acelance/ace-graph-dsl.git ace-graph-dsl-publish

# 2. 复制 backend / ui（排除构建产物与子 .git）
robocopy ace-graph-dsl\ace-graph-dsl-backend ace-graph-dsl-publish\ace-graph-dsl-backend /E /XD .git target node_modules
robocopy ace-graph-dsl\ace-graph-dsl-ui ace-graph-dsl-publish\ace-graph-dsl-ui /E /XD .git node_modules dist

# 3. 复制文档（若本地已有）
if (Test-Path ace-graph-dsl\docs) {
  robocopy ace-graph-dsl\docs ace-graph-dsl-publish\docs /E
}
```

> 若直接在原目录操作：删除 `ace-graph-dsl-backend\.git` 与 `ace-graph-dsl-ui\.git` 后，在根目录 `git init` 并关联远程。

### 2.2 根目录 `.gitignore`

合并 backend / ui 规则，至少包含：

```gitignore
# Maven
**/target/

# Node
**/node_modules/
ace-graph-dsl-ui/dist/

# IDE / OS
.idea/
.vscode/
*.iml
.DS_Store
Thumbs.db

# 日志
*.log
logs/

# 本地 / 密钥
.env
.env.*
**/application-local.*
**/application-local.yml
```

### 2.3 更新 `README.md`

在远程 README 基础上补充：

- 目录说明（`ace-graph-dsl-backend` / `ace-graph-dsl-ui`）
- 本地开发命令（见 §7）
- **消费方如何从 GitHub Packages 引用**（见 §6）
- Demo 仓库链接（若有）

### 2.4 首次推送

```powershell
cd D:\MyWorkSpace\ace-graph-dsl-publish
git pull origin main --rebase
git add ace-graph-dsl-backend ace-graph-dsl-ui docs .gitignore
git status
git commit -m "Add backend and UI modules"
git push origin main
```

**注意**：远程已有 `README.md`、`LICENSE`、`.gitignore`，先 pull 再 push，避免覆盖冲突。

### 2.5 阶段一完成标准

- [ ] GitHub 仓库 `main` 分支可见 `ace-graph-dsl-backend/` 与 `ace-graph-dsl-ui/` 源码
- [ ] 无 `node_modules/`、`target/`、`dist/` 被提交
- [ ] 无嵌套 `.git` 目录

---

## 3. 阶段二：发布配置

### 3.1 Maven — 父 POM

文件：`ace-graph-dsl-backend/pom.xml`

**版本**：首版将 `1.0.0-SNAPSHOT` 改为 `1.0.0`（GitHub Packages 首版建议用正式版）。

**增加 `distributionManagement`：**

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/acelance/ace-graph-dsl</url>
  </repository>
</distributionManagement>
```

**将发布的 artifact：**

| artifactId | 说明 |
|------------|------|
| `ace-graph-dsl-core` | 核心编译 / 校验 |
| `ace-graph-dsl-persistence` | 持久化 |
| `ace-graph-dsl-spring-boot-starter` | **业务项目主要依赖此 starter** |

### 3.2 npm — package.json

文件：`ace-graph-dsl-ui/package.json`

**补充字段：**

```json
{
  "name": "@acelance/graph-dsl-ui",
  "version": "1.0.0",
  "repository": {
    "type": "git",
    "url": "git+https://github.com/acelance/ace-graph-dsl.git",
    "directory": "ace-graph-dsl-ui"
  },
  "publishConfig": {
    "registry": "https://npm.pkg.github.com",
    "@acelance:registry": "https://npm.pkg.github.com"
  }
}
```

> scope `@acelance` 须与 GitHub 组织名一致（当前为 `acelance`，无需改名）。

### 3.3 本地认证

#### Maven — `~/.m2/settings.xml`

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>你的GitHub用户名</username>
      <password>你的PAT</password>
    </server>
  </servers>
</settings>
```

`<id>github</id>` 必须与 `distributionManagement` 中 `<id>` 一致。

#### npm — `~/.npmrc`（勿提交 Token 到 Git）

```ini
@acelance:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=你的PAT
```

#### Personal Access Token 权限

| 用途 | 所需 scope |
|------|------------|
| 本地发布 | `write:packages`、`read:packages` |
| 消费方拉包 | `read:packages` |
| CI 发布 | `GITHUB_TOKEN`（workflow 需 `packages: write`） |

在 [GitHub Token 设置](https://github.com/settings/tokens) 创建。

### 3.4 阶段二完成标准

- [ ] 父 `pom.xml` 含 `distributionManagement`，版本为 `1.0.0`
- [ ] `package.json` 含 `repository` 与 `publishConfig`
- [ ] 本地 `settings.xml` / `.npmrc` 已配置（Token 不入库）

---

## 4. 阶段三：CI 自动发版

### 4.1 建议文件

`.github/workflows/publish.yml`

### 4.2 Tag 与发版策略（独立发版）

| Tag 格式 | 行为 |
|----------|------|
| `backend-v1.0.0` | 仅发布 Maven |
| `ui-v1.0.0` | 仅发布 npm |
| `v1.0.0` | Maven + npm 同时发布 |

### 4.3 Workflow 要点

```yaml
name: Publish Packages

on:
  push:
    tags:
      - 'v*'
      - 'backend-v*'
      - 'ui-v*'

permissions:
  contents: read
  packages: write

jobs:
  publish-maven:
    if: startsWith(github.ref, 'refs/tags/v') || startsWith(github.ref, 'refs/tags/backend-v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven
      - name: Set Maven version from tag
        working-directory: ace-graph-dsl-backend
        run: |
          if [[ "${{ github.ref_name }}" == v* ]]; then VERSION="${GITHUB_REF_NAME#v}"
          else VERSION="${GITHUB_REF_NAME#backend-v}"; fi
          mvn -B versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false
      - name: Deploy to GitHub Packages
        working-directory: ace-graph-dsl-backend
        run: mvn -B clean deploy -DskipTests
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  publish-npm:
    if: startsWith(github.ref, 'refs/tags/v') || startsWith(github.ref, 'refs/tags/ui-v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          registry-url: 'https://npm.pkg.github.com'
          scope: '@acelance'
      - name: Set npm version from tag
        working-directory: ace-graph-dsl-ui
        run: |
          if [[ "${{ github.ref_name }}" == v* ]]; then VERSION="${GITHUB_REF_NAME#v}"
          else VERSION="${GITHUB_REF_NAME#ui-v}"; fi
          npm version $VERSION --no-git-tag-version --allow-same-version
      - run: npm ci
        working-directory: ace-graph-dsl-ui
      - run: npm publish
        working-directory: ace-graph-dsl-ui
        env:
          NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Maven job 在 CI 中还需生成带 `GITHUB_TOKEN` 的 `settings.xml`（或使用官方 Action 封装），本地可参考 [GitHub 文档：Publishing Java packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)。

### 4.4 可选：PR 校验 Workflow

`.github/workflows/ci.yml`：

- `ace-graph-dsl-backend`：`mvn -B clean verify`
- `ace-graph-dsl-ui`：`npm ci && npm run build`
- 按路径触发，避免无关改动全量构建

### 4.5 阶段三完成标准

- [ ] `.github/workflows/publish.yml` 已入库
- [ ] （可选）`.github/workflows/ci.yml` 已入库
- [ ] 打 tag 后 Actions 可成功跑通

---

## 5. 阶段四：首次发布

### 5.1 本地手动发布（调试）

```powershell
# Maven
cd ace-graph-dsl-backend
mvn -B clean deploy -DskipTests

# npm
cd ace-graph-dsl-ui
npm ci
npm run build
npm publish
```

### 5.2 通过 Tag 触发 CI

```powershell
git tag v1.0.0
git push origin v1.0.0
```

或独立发版：

```powershell
git tag backend-v1.0.1
git push origin backend-v1.0.1

git tag ui-v1.0.2
git push origin ui-v1.0.2
```

### 5.3 将 Package 设为公开

仓库 Public 不等于 Package Public。首次发布后：

1. 打开 GitHub → **Your organizations** → **acelance** → **Packages**
2. 进入每个包 → **Package settings** → **Change visibility** → **Public**

需分别处理 Maven 包（core / persistence / starter）与 npm 包（`graph-dsl-ui`）。

### 5.4 创建 GitHub Release（建议）

在 **Releases** 创建 `v1.0.0`，说明：

- Maven：`io.acelance:ace-graph-dsl-spring-boot-starter:1.0.0`
- npm：`@acelance/graph-dsl-ui@1.0.0`
- 变更摘要

### 5.5 阶段四完成标准

- [ ] GitHub Packages 可见已发布版本
- [ ] 所有包 visibility 为 **Public**
- [ ] （可选）GitHub Release 已创建

---

## 6. 阶段五：消费方接入

### 6.1 Maven（Spring Boot 业务项目）

**依赖：**

```xml
<dependency>
  <groupId>io.acelance</groupId>
  <artifactId>ace-graph-dsl-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

**仓库（`pom.xml` 或父 POM）：**

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/acelance/ace-graph-dsl</url>
  </repository>
</repositories>
```

**认证（消费方 `~/.m2/settings.xml`）：**

```xml
<server>
  <id>github</id>
  <username>GitHub用户名</username>
  <password>PAT（read:packages）</password>
</server>
```

> GitHub Packages 的 Maven 即使公开，下载通常仍需要认证。

### 6.2 npm（Vue 前端项目）

**依赖：**

```json
{
  "dependencies": {
    "@acelance/graph-dsl-ui": "1.0.0"
  }
}
```

**`.npmrc`：**

```ini
@acelance:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
```

**使用：**

```js
import { GraphDslManager, configureGraphApi } from '@acelance/graph-dsl-ui'
import '@acelance/graph-dsl-ui/style'

configureGraphApi({
  apiPrefix: '/api/graph'   // 对齐后端 ace.graph.dsl.web.base-path
})
```

### 6.3 从本地 `file:` 依赖迁移

Demo 或业务项目若当前为：

```json
"@acelance/graph-dsl-ui": "file:../../../ace-graph-dsl-ui"
```

```xml
<version>1.0.0-SNAPSHOT</version>  <!-- 且无 GitHub 仓库配置 -->
```

迁移步骤：

1. 按 §6.1 / §6.2 配置仓库与认证  
2. 将版本改为已发布的 `1.0.0`  
3. 删除 Vite `resolve.alias` 指向 ui 源码的配置（若存在）  
4. `npm install` / `mvn dependency:resolve` 验证

---

## 7. 阶段六：本地开发（维护者）

### 7.1 后端

```powershell
cd ace-graph-dsl-backend
# 需要 JDK 17+
mvn -B clean install -DskipTests
```

### 7.2 前端

```powershell
cd ace-graph-dsl-ui
npm ci
npm run dev      # 本地演示
npm run build    # 产出 dist/（发布前必跑）
```

### 7.3 联调

Demo 不在本仓库时，可在 demo 项目中继续临时使用：

- `file:../ace-graph-dsl-ui` 或 Vite alias 指向 ui 源码  
- Maven 使用 `mvn install` 安装 starter 到本地仓库  

发布流程与日常开发可并行，互不影响。

---

## 8. 可选后续工作

| 项 | 说明 |
|----|------|
| `docs/PUBLISHING.md` 精简版 | 给维护者的发版速查 |
| Demo 独立仓库 | `acelance/ace-graph-dsl-demo`，依赖 Packages 而非 `file:` |
| `.d.ts` 类型声明 | 改善 npm 消费体验 |
| npm 只发 `dist/` | `package.json` 的 `files` 去掉 `src` 减小包体 |
| 对齐 `FUTURE_OPTIMIZATION_PLAN.md` | 更新 §3.2 / §8.2 / §8.3 进度 |
| Maven Central / npmjs.com | 第二阶段再考虑，认证步骤更多 |

---

## 9. 常见问题

### Q1：只推 backend 和 ui，demo 怎么办？

Demo 可保留在本地或另建仓库；不影响 Packages 发布。对外文档中提供 demo 链接即可。

### Q2：能否只发 UI、不发 backend？

可以。打 `ui-v*` tag 或仅在 ui 目录变更时跑 npm job。

### Q3：SNAPSHOT 能否发到 GitHub Packages？

可以但不推荐作首版。建议首版用 `1.0.0`，后续按 SemVer 递增。

### Q4：组织名不是 acelance 怎么办？

npm 包名 scope 必须等于 GitHub 用户名/组织名；Maven `groupId` 可保持 `io.acelance`，与 GitHub 名无强制关系。

---

## 10. 总 checklist

| # | 阶段 | 工作项 | 状态 |
|---|------|--------|------|
| 1 | 入库 | 合并 monorepo，去掉子目录 `.git` | ☐ |
| 2 | 入库 | 根 `.gitignore`、更新 README | ☐ |
| 3 | 入库 | 推送 backend + ui 到 GitHub | ☐ |
| 4 | 配置 | `pom.xml` distributionManagement + 版本 1.0.0 | ☐ |
| 5 | 配置 | `package.json` repository / publishConfig | ☐ |
| 6 | 配置 | PAT + settings.xml / .npmrc | ☐ |
| 7 | CI | `.github/workflows/publish.yml` | ☐ |
| 8 | CI | （可选）`.github/workflows/ci.yml` | ☐ |
| 9 | 发布 | 首次 deploy / publish 或打 tag | ☐ |
| 10 | 发布 | Packages visibility → Public | ☐ |
| 11 | 文档 | README 消费方接入说明 | ☐ |
| 12 | 发布 | （可选）GitHub Release v1.0.0 | ☐ |

---

## 11. 相关链接

- 仓库：<https://github.com/acelance/ace-graph-dsl>
- GitHub Packages 文档：<https://docs.github.com/en/packages>
- Maven  registry：<https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry>
- npm registry：<https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry>
- 项目规划：[FUTURE_OPTIMIZATION_PLAN.md](../ace-graph-dsl-backend/docs/FUTURE_OPTIMIZATION_PLAN.md)
