<!-- Sync Impact Report
  Version change: 0.0.0 → 1.0.0 (initial ratification)
  Added principles: 7 (Layered Architecture, Domain Isolation, Interface-Implementation Separation, Webhook-Driven Automation, Spec-First Development, Code Quality, Simplicity)
  Added sections: Technology Standards, Development Workflow
  Templates requiring updates:
    - .specify/templates/plan-template.md ✅ compatible (Constitution Check section exists)
    - .specify/templates/spec-template.md ✅ compatible (user stories structure fits)
    - .specify/templates/tasks-template.md ✅ compatible (task grouping by story fits)
  Follow-up TODOs: none
-->

# Sunline Benchmark 项目宪法

## Core Principles

### I. 元数据驱动的分层架构（NON-NEGOTIABLE）

所有业务功能 MUST 遵循四层分层架构，禁止跨层级直接调用：

- **交易层（flowtrans）**：负责交易接口的输入输出和服务编排，可编排 pcs/pbs/method
- **服务层（pcs/pbs）**：pcs 可调用 pbs；pbs 可调用构件（pbcb/pbcp/pbcc/pbct）
- **构件层（pbcb/pbcp/pbcc/pbct）**：pbcb/pbcp 可调用 pbcc/pbct；pbcc 可调用 pbct；所有构件可调用 bcc
- **表 DAO 层（bcc）**：只能被构件层调用

同层级 MUST NOT 互相调用（如 pbs 调 pbs、pbcc 调 pbcc）。跨层级获取实例统一使用 `SysUtil.getInstance(Xxx.class)` 语法。

### II. 领域隔离与跨域规则

系统划分为四个业务领域，跨域调用 MUST 遵循严格规则：

| 领域 | 代号 | 工程前缀 |
|------|------|---------|
| 公共 | comm | ccbs-comm-* |
| 存款 | dept | ccbs-dept-* |
| 贷款 | loan | ccbs-loan-* |
| 结算 | sett | ccbs-sett-* |

- flowtrans 编排 → **允许**跨域
- pcs → pbs → **允许**跨域
- pbs → pbcb/pbcp → **禁止**跨域（MUST 同领域）
- pbs → pbcc/pbct → **允许**跨域（pbcc/pbct 为公共构件）
- pbcb/pbcp → pbcc/pbct → **允许**跨域

### III. 接口与实现分离

所有 Service 层 MUST 采用接口（interface）+ 实现类（impl）分离模式：

- 接口定义在 `com.sunline.dict.service` 包下
- 实现类定义在 `com.sunline.dict.service.impl` 包下
- Controller MUST 只依赖接口，不直接依赖实现类
- 配置类（如 `BuildConfig`）使用 `@ConfigurationProperties` 绑定复杂配置，禁止用 `@Value` 注入嵌套结构

### IV. Webhook 驱动的自动化

GitLab Push 事件通过统一入口 `/api/webhook/gitlab` 驱动以下自动化链路：

1. **XML 元数据解析**（同步）：解析 flowtrans/pbs/pcs 等 XML → 落库
2. **代码同步**（同步）：git fetch + reset --hard origin/master
3. **调用关系增量扫描**（异步）：从 diff 提取变更 Java 文件 → 增量更新 call_relation 表

每个环节失败不得阻断后续环节。异步任务 MUST 使用 `@Async` 避免阻塞 Webhook 返回。

### V. 规格驱动开发（Spec-Driven Development）

新功能开发 MUST 遵循以下流程：

1. **规格定义**（/speckit.specify）：明确"做什么"和"为什么"，不涉及技术栈
2. **技术方案**（/speckit.plan）：确定技术栈、架构选型、数据模型
3. **任务拆分**（/speckit.tasks）：按用户故事拆分为可独立测试的任务
4. **执行实现**（/speckit.implement）：按任务顺序实现代码

规格文档存放在 `.specify/specs/` 目录下，纳入版本控制。

### VI. 代码质量

- 所有 Controller MUST 有 Javadoc 注释说明接口列表
- Service 接口方法 MUST 有 Javadoc 说明参数和返回值
- 禁止在代码注释中解释"正在做什么修改"，注释只用于解释非显而易见的业务逻辑
- API 路径统一使用 `/api/{module}/...` 格式
- 免鉴权路径统一在 `WebMvcConfig.addInterceptors` 白名单中管理
- 统一响应格式使用 `Result<T>` 包装

### VII. 简洁优先（YAGNI）

- 不提前优化，不过度设计
- 优先选择 Spring Boot 原生能力，避免引入不必要的第三方库
- 前端使用 Vue 3 + axios 内嵌在 `src/main/resources/static/` 中，不使用前后端分离构建工具
- 配置项集中在 `application.yml`，支持多 profile（dev/sit）

## Technology Standards

| 层面 | 技术选型 | 版本 |
|------|---------|------|
| 语言 | Java | JDK 17 |
| 框架 | Spring Boot | 3.1.5 |
| ORM | MyBatis Plus | 3.5.4.1 |
| 数据库 | MySQL | 8.0.33 |
| 前端 | Vue 3 + axios | 内嵌 static |
| 构建 | Maven | 3.8.6+ |
| 编译配置 | 批次串行/并行 | 通过 `build.batches` YAML 配置 |
| 代码管理 | GitLab | Webhook 集成 |

## Development Workflow

1. **功能开发**：遵循 Spec-Driven 流程（宪法原则 V），产出规格 → 方案 → 任务 → 代码
2. **代码提交**：推送到 GitLab master 分支，触发 Webhook 自动化链路
3. **编译部署**：通过 `/api/build/all/async` 触发全量编译（编译前自动 git pull 兜底 + `-U` 强制更新 SNAPSHOT）
4. **关系维护**：代码提交后自动增量更新调用关系图谱，也可手动触发 `/api/relation/scan` 全量重建
5. **违规检测**：调用关系扫描自动检测跨域违规和同层调用违规，通过 `/api/relation/violations` 查看

## Governance

- 本宪法是项目所有开发活动的最高准则，所有代码提交 MUST 遵循其原则
- 修改宪法需要更新版本号（语义化版本：MAJOR.MINOR.PATCH）并记录变更理由
- 每次新增功能前 SHOULD 检查是否与宪法原则一致
- 运行时开发指导参见 `docs/call-relation-design.md` 和 `README.md`

**Version**: 1.0.0 | **Ratified**: 2026-02-27 | **Last Amended**: 2026-02-27
