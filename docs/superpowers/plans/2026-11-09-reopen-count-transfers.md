# 重开次数直接转移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal** 在人员统计数字上完成授权人员之间的数量转移，人员表只显示负责人和问题数。

**Architecture** 页面以每次重开计 1 展示可累加数量。用户选择来源行、目标开发、数量和原因，服务端自动选取所选类型与批次内的 N 条重开事件，原子修改责任归属并保存审计与幂等请求结果。

**Tech Stack** Vue 3、Vitest、Spring MVC、JdbcTemplate、JUnit 5、MySQL、H2。

**Spec** `/Users/java/obsidian/01 Engineering/axon-link-server/回放问题重开责任调整-系统设计.md` 及对应数据模型与 API 接口文档。

## Global Constraints

- 人员表的“问题数”使用 `currentCount`，每次重开计 1，与总重开次数采用同一计数单位。
- 沿用已应用的交易类型和发生批次多选范围，`ALL` 全部、`QUERY` 对应 RPT、`DZ` 对应 DZ。
- `dii.replay.issue-reopen.count-transfer-emp-nos` 是独立工号白名单，默认空列表，后端根据有效登录用户校验权限。
- `POST /api/ai/parallel-replay/issues/reopens/count-transfers` 接收 UUID 请求标识、类型、批次、来源、目标、数量和必填原因。
- 数量为整数 1 至 10000，原因最多 1000 字符，批次列表最多 200 项，每项最多 128 字符。
- 服务端稳定选取 N 条事件并按 ID 升序加锁，核对版本、来源、范围和待审状态，提交归属与审计。
- 待审事件保留给当前审批流程，表单可转数量采用 `currentCount - pendingCount`。
- 同请求同操作者同内容重试返回原结果，载荷冲突返回 409，越权返回 401 或 403。
- 原发生批次与事件总数构成守恒基础，人员之间转移 N 次后所选批次全体总次数守恒。

## Review Focus

- 多批次筛选草稿和已应用范围分离，表单必须使用打开时的已应用范围。
- 同来源并发转出、重复请求、半途写入失败均保持原子性和幂等性。
- 权限配置管理员、领域领导和转移白名单分别验证。
- 待归属、历史失效来源、相同来源目标、失效目标和超额数量正确处理。
- 请求失败后同内容重试复用 UUID，修改内容后生成新 UUID，关闭期间的响应受到保护。

### Task 1 后端次数转移

**Files** `ReplayReopenDtos.java`、`ReplayReopenWorkflowDtos.java`、`ReplayReopenProperties.java`、`ReplayReopenPermissionService.java`、`ReplayReopenDao.java`、`ReplayReopenService.java`、`ReplayReopenController.java`、V90 SQL、相关测试及既有部署说明。

- [x] 先写权限、数量守恒、范围、幂等、冲突及回滚的失败回归测试并运行。
- [x] 新增独立工号白名单与 `canTransferCounts`，兼容既有权限 DTO 构造器。
- [x] 增加幂等请求表及接口，复用现有事件锁、归属更新和审计写入。
- [x] 覆盖并发转移与已有审批互斥，运行干净编译的重开模块测试。

### Task 2 人员数字与转移表单

**Files** `ReplayReopenStatistics.vue`、`ReplayReopenLedgerModal.vue`、`ReplayReopenCountTransferForm.vue`、`replayReopens.js`、相关测试和共享 CSS。

- [x] 先验证人员表两列、普通用户只读、白名单用户可点击及表单失败回归。
- [x] 数字采用当前次数，表单复用开发人员选择器，提交数量、原因与已应用范围快照。
- [x] 保留幂等请求标识、重复提交保护、权限变化与失败提示，成功重新读取统计。
- [x] 运行相关组件及全套前端测试。

### Task 3 联调与集成

- [x] 独立检查计数单位、接口权限、锁顺序和前端范围一致性。
- [x] 使用隔离测试数据库和真实 Controller、Service、DAO 验证点击数字转移及总数守恒。
- [x] 运行全量后端回归并逐项核对既有失败基线。
- [x] 将验证通过的代码同步到原项目，执行前端生产构建并校验后端静态资源。
