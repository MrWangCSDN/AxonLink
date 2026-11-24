# 计划完成情况平均延期天数 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for backend implementation and delegate the independent frontend task; review the combined change before delivery.

**Goal:** 在延期修复、延期未完成数量后分别增加对应的平均延期天数。

**Architecture:** 在既有只读聚合中计算两类问题的自然日延期总数，DTO 派生均值，领域与总体重新按总天数和数量计算。Vue 表格和独立 Canvas 快照消费相同的新增响应字段。

**Tech Stack:** Java 17、Spring JDBC、MySQL/H2、Vue 3、Vitest。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/计划完成情况平均延期天数-系统设计.md`

## Global Constraints

- JSON 字段为 `averageLateFixedDays`、`averageOverdueUnfinishedDays`，summary、groups、developers 一致。
- 只在对应延期分类内平均；自然日差不加 1，分母为 0 返回 null；两位小数 HALF_UP，页面和 PNG 的空值显示 `-`。
- 使用同一次请求注入 Clock 的 today；领域/总体先合计原始延期天数再除，不平均已舍入的负责人均值。
- 不增加表字段/迁移，不改变分类、日期范围、交易类型/领域分组、下钻或负责人组合规则。
- 保留已有未知工作区修改；完成后按既有授权将本次改动推送 GitHub main，前端构建至后端 static。

## Review Focus

- 计划日期等于今天的未修复问题不进入延期均值。
- 多负责人样本量不同时，总体均值按问题数量加权。
- 零样本 null 与有效 0、小数展示不会混淆。
- 修复待验证仅为未完成子集，不重复计数；有修复日期仍按日期分类。
- PNG 新增两列后的画布宽度足够，窄屏表格使用内部横向滚动。

## Task 1: 后端日期聚合与派生均值

**Files:** `ReplayIssueCompletionCounts.java`、`ReplayIssueCompletionStatsDao.java`、`ReplayIssueCompletionStatsService.java` 及对应三份测试。

**Interfaces:** 原统计响应增加两项 BigDecimal 均值；Counts 保存两项 @JsonIgnore 总天数供层级聚合，不增加接口参数。

- [x] 编写回归并验证先失败：日期差、今天边界、null 均值、HALF_UP、嵌套 JSON、筛选与不等样本加权。
- [x] 在原分类聚合计算延期总天数；复用服务端 today，不使用数据库 CURRENT_DATE。
- [x] DTO 派生均值，Service 聚合原始总天数；验证三个层级对账。
- [x] 运行三份定向测试并确认通过。

## Task 2: 前端表格与 PNG 同步

**Files:** `ReplayPlannedCompletionModal.vue/.spec.js`、`completionSnapshot.js/.spec.js`。

**Interfaces:** 消费 Task 1 字段，保留两位小数，null/缺失显示 `-`。

- [x] 先写行为回归并确认失败：列顺序、领域/负责人值、零样本、10 列空态与快照完整边界。
- [x] 在对应数量列后增加两列，同步列宽/空态 colspan 与 PNG 画布。
- [x] 运行定向 Vitest 并确认通过。

## Task 3: 集成验证与交付

- [x] 独立审查差异并修正实质问题。
- [x] 完整前端测试、后端 Maven 测试、真实接口生产构建至后端 static。
- [x] 浏览器检查桌面和窄屏及 PNG 数值/布局，Java 17 打包。
- [x] git diff --check，精确提交本次文件，推送前后端 main；记录验证结果并保留未知修改。

## 验证结果

- 后端新增回归先出现3项预期失败（新增JSON字段缺失），实现后定向20项全部通过。完整 Maven 测试175份报告、1606项，0失败、0错误、6跳过。
- 前端新增回归先失败，定向58项通过；最终当前源码完整38文件、764项通过。裸 `npm test` 将两个历史工作树 `.worktrees/replay-db-compare-scope-20260917` 和 `.worktrees/replay-db-comparison-fields` 的旧测试也纳入，出现470项旧测试失败（如 ChainImpactSidebar/TransactionAnalysis）；所有失败路径均属旧工作树。最终使用 `npm test -- --exclude '**/.worktrees/**'` 验证当前项目，未修改旧工作树或扩大任务修改测试配置。
- Playwright 使用真实Vue组件与模拟接口验证桌面10列、领域/负责人两位数、零样本横杠、下钻日期、空态colspan10及PNG完整10列。PNG3820×824；桌面及手机截图保存在 output/playwright/completion-average-days-*。模拟数据仅用于界面验证，未连接现场数据库。
- 浏览器390宽复现split-layout被表格撑开的问题，补充min-width:0后验证容器内部横向滚动；独立复核通过。
- 最终生产前端构建至后端static，Java17 Maven package成功，已逐字节验证后端包内全部前端资源。static依照仓库既有规则为忽略的构建产物，保留在本地与后端包内，不强行添加到源码仓库。
- 后端、前端独立审查均无可操作问题；本次仅精确提交对应源码、测试及本计划，保留此前已有plan修改和其他未知文件。Obsidian设计已推送main：987ae7b63ff3f85ea36c0d7377a20ed2658674c3。
