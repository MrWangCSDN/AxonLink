# 重开人员与批次统计实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal** 将重开责任调整台账收敛为人员与批次统计，并提供交易类型约束下的发生批次多选。

**Architecture** Vue 页面仅传递交易类型、发生批次列表和仅看本人。后端扩展现有 Filter，共享参数绑定的多批次聚合条件，沿用永久事件与当前生效责任归属。

**Tech Stack** Vue 3、Vitest、Spring MVC、JdbcTemplate、JUnit 5。

**Spec** `/Users/java/obsidian/01 Engineering/axon-link-server/回放问题重开责任调整-系统设计.md` 及对应数据模型与 API 接口文档。

## Global Constraints

- `ALL` 可选择全部发生批次，`QUERY` 仅选择 `RPT` 开头，`DZ` 仅选择 `DZ` 开头。
- `batchNames` 使用重复查询参数，空列表表示当前交易类型的全部批次，兼容既有 `batchName` 标量参数。
- 页面仅显示人员与批次统计，移除领域、问题 key、导入轮次、事件编号和负责人账号筛选。
- 问题行与导入结果入口均不能附加不可见的 key 或轮次限制。
- 不需要数据库迁移。保留既有计次、责任归属、审批和审计能力。

## Review Focus

- 切换类型时清除不兼容的已选批次，候选列表的旧响应不得覆盖新响应。
- 未选择批次与选择多个批次的汇总范围准确，所有人员和批次聚合共用相同条件。
- 前端加载失败或新请求等待时不得将旧统计显示为新范围的结果。
- 旧 API 构造器、单批次调用和仅看本人授权行为继续工作。
- 从问题行或导入结果进入时，统计仅依赖可见筛选条件。

### Task 1 后端多批次范围

**Files** `ReplayReopenDtos.java`、`ReplayReopenController.java`、`ReplayReopenService.java`、`ReplayReopenDao.java`、`ReplayReopenWorkflowDao.java` 及对应测试。

- [x] 先增加重复参数、跨批次统计、类型前缀、兼容调用和 onlyMine 的失败回归测试。
- [x] 扩展 Filter 的 `List<String> batchNames`，保留旧构造器，规范化并验证请求。
- [x] 使用绑定参数 `IN` 条件，并同步筛选候选与关联申请查询。
- [x] Java 17 下运行重开模块相关测试，再由主代理运行全套测试对比已知基线。

### Task 2 统计页面与 API 编码

**Files** `ReplayReopenLedgerModal.vue`、`replayReopen.css`、`replayReopens.js` 及对应测试。

- [x] 先覆盖统计默认入口、隐藏旧页、多选、类型切换、全批次、竞态及重复查询参数的失败测试。
- [x] 删除旧子页渲染，保留统计及可见筛选，用复选下拉实现发生批次多选。
- [x] 按类型刷新候选和清理所选，保持请求序列保护及错误展示。
- [x] 运行前端相关测试和全套测试。

### Task 3 父入口与最终验证

**Files** `ReplayIssuePage.vue`、`ReplayIssuePage.spec.js`、后端静态资源目录。

- [x] 先验证入口不能传递隐藏的 `issueKey`、`sourceRoundId`。
- [x] 父入口仅传递交易类型，更新导入结果入口与问题行提示文案。
- [x] 检查前后端 diff，运行测试与生产构建，浏览器验证筛选交互。
- [x] 将已验证代码同步回用户原工作目录，保留其他文件与已有本地制品。
