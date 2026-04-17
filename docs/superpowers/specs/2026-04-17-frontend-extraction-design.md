# Frontend Extraction Design

## Background

当前仓库 `axon-link-server` 同时承载：

- Spring Boot 后端源码
- Vue/Vite 前端源码工程 `frontend/`
- 前端编译产物 `src/main/resources/static/`

这会带来两个直接问题：

- 后端源码 zip 会把前端源码工程一并打进去，体积和压缩耗时都偏大
- 前后端源码边界不清晰，后端仓库同时承担运行时静态资源和前端开发工程两类职责

本次目标不是拆分运行时部署，而是只把前端源码工程移出后端仓库。后端继续保留前端编译后的静态资源，并继续按当前方式打包成 Spring Boot jar。

## Goals

- 将前端源码工程从 `axon-link-server` 仓库中抽离
- 新前端目录固定为 `/Users/wangshanhe/Desktop/myproject/axon-link-frontend`
- 前端构建完成后，静态资源仍输出到 `axon-link-server/src/main/resources/static/`
- 后端现有静态资源访问方式和 jar 打包结果保持不变
- 后端源码 zip 默认不再包含前端源码工程

## Non-Goals

- 不改造为前后端分离部署
- 不修改后端运行时的静态资源类路径
- 不引入 Maven 前端插件或新的构建系统
- 不在本次迁移中批量修正文档里所有历史 `frontend/...` 路径引用

## Options Considered

### Option A: Sibling frontend workspace

将 `frontend/` 迁移到后端仓库外的兄弟目录 `/Users/wangshanhe/Desktop/myproject/axon-link-frontend`。

前端 `vite build` 继续直接输出到后端仓库的 `src/main/resources/static/`。

优点：

- 对后端运行时影响最小
- 不需要改动 `WebMvcConfig` 和 Spring Boot 静态资源规则
- 后端源码 zip 立即瘦身

缺点：

- 前端和后端之间存在跨目录构建依赖
- 需要更新构建脚本和前端 `vite.config.js`

### Option B: Frontend builds to its own dist, backend copies on demand

前端先输出到自己的 `dist/`，后端脚本再复制到 `src/main/resources/static/`。

优点：

- 前端构建产物与后端源码边界更清晰

缺点：

- 多一步同步动作
- 更容易出现“前端已改但未同步到后端静态资源”的状态

### Option C: Separate repository or submodule

将前端彻底拆成独立仓库或 submodule。

优点：

- 边界最清晰

缺点：

- 管理和迁移成本明显更高
- 超出本次只想“抽离源码工程”的目标

## Decision

采用 Option A。

这是最小改动方案，能直接解决“后端源码 zip 太大”和“前端源码不应留在后端仓库”两个问题，同时保持当前部署和访问方式不变。

## Target Layout

迁移后目录关系如下：

```text
/Users/wangshanhe/Desktop/myproject/
├── axon-link-server/
│   ├── src/main/resources/static/
│   ├── pom.xml
│   ├── build.sh
│   └── ...
└── axon-link-frontend/
    ├── package.json
    ├── vite.config.js
    ├── src/
    ├── public/
    └── ...
```

后端仓库中不再保留 `frontend/` 源码目录。

## Required Changes

### 1. Move frontend source tree

将以下内容迁移到新目录 `/Users/wangshanhe/Desktop/myproject/axon-link-frontend`：

- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/vite.config.js`
- `frontend/index.html`
- `frontend/public/`
- `frontend/src/`

不迁移：

- `frontend/node_modules/`
- `frontend/.vite/`

### 2. Update frontend build output path

新前端目录中的 `vite.config.js` 需要把 `outDir` 改为：

- `/Users/wangshanhe/Desktop/myproject/axon-link-server/src/main/resources/static`

实现方式固定为 `path.resolve(__dirname, '../axon-link-server/src/main/resources/static')`。

这样前端源码工程移走后，构建产物仍会直接刷新后端静态资源目录。

### 3. Update backend build script

后端 `build.sh` 需要从固定的 `cd frontend` 改为进入兄弟目录：

- `/Users/wangshanhe/Desktop/myproject/axon-link-frontend`

脚本仍保持原有构建顺序：

1. 前端 `npm install`
2. 前端 `npm run build`
3. 后端 `mvn clean package -DskipTests`

### 4. Update ignore rules

后端仓库 `.gitignore` 中与旧前端目录绑定的规则需要清理：

- 删除 `frontend/node_modules/`
- 删除 `frontend/.vite/`

`src/main/resources/static/` 继续保留忽略规则，因为它仍然是构建产物目录。

如果新前端目录也需要忽略规则，应在新前端目录单独维护自己的 `.gitignore`。

### 5. Keep backend runtime unchanged

以下后端行为保持不变：

- `application.yml` 继续使用 `classpath:/static/`
- `WebMvcConfig` 继续从 `classpath:/static/` 提供 SPA 资源
- `pom.xml` 继续打包 `src/main/resources/static/` 进入 jar

本次迁移不修改后端资源映射实现。

## Migration Sequence

1. 在兄弟目录创建 `axon-link-frontend`
2. 迁移前端源码文件到新目录
3. 在新目录补齐 `.gitignore`
4. 修改新前端目录的 `vite.config.js`
5. 修改后端 `build.sh`
6. 清理后端仓库中的旧 `frontend/` 源码目录
7. 更新后端 `.gitignore`
8. 执行前端构建，确认 `src/main/resources/static/` 被刷新
9. 执行后端打包，确认 jar 中仍包含前端静态资源

## Risks

### Relative path risk

前端迁移后，`vite.config.js` 的相对路径如果写错，会导致构建输出到错误目录。

控制方式：

- 使用固定 sibling path
- 构建后立即核对 `axon-link-server/src/main/resources/static/index.html`

### Script path drift

后端 `build.sh` 以后依赖外部目录。如果前端目录被重命名或移动，脚本会失败。

控制方式：

- 在脚本中使用明确的绝对路径或基于脚本目录的 sibling 路径
- 脚本开头对前端目录存在性做显式检查

### Historical docs becoming stale

历史设计文档和任务文档中大量引用 `frontend/...` 路径，迁移后会失效。

控制方式：

- 本次不做全量文档重写
- 仅保证构建链路和当前开发路径正确

## Validation

### Frontend validation

在新前端目录执行：

```bash
npm install
npm run build
```

期望结果：

- `axon-link-server/src/main/resources/static/index.html` 被刷新
- `axon-link-server/src/main/resources/static/assets/` 被刷新

### Backend validation

在后端目录执行：

```bash
mvn clean package -DskipTests
```

期望结果：

- 生成 `target/axon-link-server-1.0.0.jar`
- `jar tf target/axon-link-server-1.0.0.jar` 中存在：
  - `BOOT-INF/classes/static/index.html`
  - `BOOT-INF/classes/static/assets/...`

### Zip validation

重新生成后端源码 zip，期望：

- 不再包含前端源码工程
- zip 体积和压缩时间下降

## Implementation Scope

本次实施仅包括：

- 前端源码迁移
- 构建路径更新
- 后端仓库清理旧前端源码目录
- 构建验证

不包括：

- 前端独立部署
- CI/CD 重构
- 文档大规模路径修复
