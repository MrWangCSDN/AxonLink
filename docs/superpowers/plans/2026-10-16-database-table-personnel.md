# 数据库表人员清单 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 以新数据库表人员清单统一字段登记和数据库问题清单的领域及负责人。

**Architecture:** 结果库新增按规范化表英文名唯一的人员表；Excel导入全量原子替换。字段登记保存从人员表派生，新版本冻结映射；当前列表和问题清单动态关联，不改旧审计及版本。

**Tech Stack:** Java 17, Spring JDBC, Apache POI, MySQL, Vue 3, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/数据库表人员清单-系统设计.md`（独立Wiki分支 `codex/database-table-personnel-20261016`）

## Global Constraints

- Excel表头依次为领域、表英文名称、表中文名称、开发负责人、科技负责人。
- 开发负责人可用账号匹配；科技负责人必须有工号。
- 映射缺失在现有列表和问题清单显示空，不回退旧修订人。
- 保留存量登记、问题历史和旧版本快照。

## Review Focus

- 重复表名（含大小写）应使整个导入失败，原清单仍在。
- 人员错配应返回行号和原因；空人员应允许导入。
- 清单移除一张表后，当前列表/导出显示空，审核权限收回。
- 旧前端仍提交领域和负责人时，不能覆盖清单。
- 生成新版本冻结当前清单，旧版本不变化。

### Task 1: 人员清单数据/API

**Files:** 新增 `V82__replay_database_table_person.sql`、`ReplayDatabaseTablePerson*`；测试对应parser、DAO、controller。

- [ ] 写失败测试：五列表头、重复表、用户匹配、全量导入回滚、搜索导出。
- [ ] 实现人员清单解析、结果表、事务替换和三个API。
- [ ] 运行对应Maven测试确认通过。

### Task 2: 字段登记映射

**Files:** `ReplayDatabaseComparisonService`、`ReplayDatabaseComparisonDao`、`ReplayDatabaseComparisonVersionService`及测试。

- [ ] 写失败测试：保存忽略请求中旧人员值、当前页面读时映射、无清单禁止新登记、版本冻结。
- [ ] 实现保存与读时映射，并覆盖初始化导入及版本链路。
- [ ] 运行字段登记测试。

### Task 3: 数据库问题清单映射与审核

**Files:** `ReplayDatabaseIssueDao`、`ReplayDatabaseIssueService`、`ReplayDatabaseIssueController`及测试。

- [ ] 写失败测试：两个人员从清单显示、无映射留空、筛选导出与审核一致。
- [ ] 把当前登记关联替换为人员清单关联，移除账号未匹配展示。
- [ ] 运行数据库问题清单测试。

### Task 4: 前端页面与联动

**Files:** 新增 `ReplayDatabaseTablePersonPage.vue`、API；修改菜单、路由、字段登记编辑/查看、问题清单；前端测试。

- [ ] 写失败测试：菜单位置、搜索导入导出、移除“4.登记信息”、负责人列名及空值。
- [ ] 实现页面和字段登记/问题清单文本及交互。
- [ ] 运行Vitest和Vite构建到后端static。

### Task 5: 交付验证

- [ ] 运行后端相关测试、前端相关测试、编译、`git diff --check`。
- [ ] 核对V82迁移、旧数据保留和部署说明。
