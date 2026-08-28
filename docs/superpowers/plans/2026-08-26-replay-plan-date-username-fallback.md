# 计划完成日期无工号用户授权 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保持现有 `emp-nos` YAML 不变，使启用用户有工号时按 `emp_no` 授权、无工号时按 `username` 授权计划完成日期编辑。

**Architecture:** 权限判断继续集中在 `ReplayIssuePlanDateService`。服务先通过登录名读取启用用户，再选择唯一身份键：非空 `emp_no` 优先，否则使用去空白后的 `username`；该身份键与领域 `emp-nos` 名单做精确匹配，权限查询和写接口共用同一规则。

**Tech Stack:** Java 17+、Spring Boot 3、JdbcTemplate、JUnit 5、Maven

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md` 的“计划完成日期模型”，以及 `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md` 的“计划完成日期接口”

## Global Constraints

- 不修改现有 YAML 层级和 `emp-nos` 属性名。
- 只允许 `ccbs_ai_sys_user.status=1` 的用户获得权限。
- `emp_no` 非空时只按 `emp_no` 匹配；只有 `emp_no` 为空时才回退 `username`。
- 所有匹配均去除首尾空白后精确匹配，不使用模糊匹配。
- 不修改前端接口、数据库结构或既有人工日期数据。
- 当前工作区包含用户的既有未提交改动，不执行 Git 提交或清理。

---

### Task 1: 用行为测试锁定身份回退规则

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssuePlanDateService.permissions(ReplayIssueOperator)` 和 `update(long, String, ReplayIssueOperator)`
- Produces: 对无工号 username 回退、工号优先和未匹配拒绝的回归保护

- [x] **Step 1: 写入失败测试**

在测试用户表中增加启用且 `emp_no=NULL` 的 `username-editor`，把 `username-editor` 配到公共组 `emp-nos`，断言权限查询包含“公共组”且能保存日期；同时把已有有工号用户的 username 放到未授权领域，断言不能通过 username 绕过工号优先规则。

- [x] **Step 2: 运行测试确认 RED**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`

Expected: 新增的无工号 username 用例失败，表现为 `editableGroups` 为空或更新抛出 `ReplayIssuePlanDateForbiddenException`；既有测试保持通过。

### Task 2: 最小化实现统一身份匹配

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`

**Interfaces:**
- Consumes: `SysUser.getEmpNo()`、`SysUser.getUsername()`、`EditorGroup.getEmpNos()`
- Produces: `private static String permissionIdentity(SysUser user)`，供权限投影和保存鉴权复用

- [x] **Step 1: 实现身份选择**

新增一个私有方法：用户为空返回空；`emp_no` 非空返回去空白工号；否则返回去空白 username。将 `permissions` 和 `canEdit` 都改为使用该身份，避免两个入口规则漂移。

- [x] **Step 2: 运行专项测试确认 GREEN**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest test`

Expected: 本测试类全部通过，Failures=0、Errors=0。

- [x] **Step 3: 运行计划日期接口与持久化回归测试**

Run: `mvn '-Dtest=ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest#planDatePermissionsAndPatchEnforceAuthenticationPermissionAndValidation+plannedCompletionDatePatchReturnsNotFoundForMissingIssue+exportsAllRowsMatchingQueryFilters,ReplayIssueDaoTest#plannedCompletionDateRoundTripsAndCanBeUpdatedIndependently,ReplayIssueMergeServiceTest#reimportPreservesPlannedCompletionDate' test`

Expected: 所有选中用例通过，Failures=0、Errors=0。

### Task 3: 构建与交付检查

**Files:**
- Verify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`

**Interfaces:**
- Consumes: Task 1–2 的实现与测试
- Produces: 可打包的后端源码和明确的 YAML 使用说明

- [x] **Step 1: 编译后端**

Run: `mvn -DskipTests package`

Expected: `BUILD SUCCESS`。

- [x] **Step 2: 检查补丁质量**

Run: `git diff --check`

Expected: 无输出且退出码为 0。

- [x] **Step 3: 核对配置口径**

确认配置示例无需新增字段：有工号填写 `emp_no`，没有工号填写 `username`，两者都放在对应领域现有 `emp-nos` 列表中。
