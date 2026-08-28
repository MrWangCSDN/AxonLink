# 计划完成日期表头多选筛选 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为问题清单“计划完成日期”增加服务端 Excel 式多选筛选，空日期候选显示“空”且置顶，列表仍显示 `-`，分页、计数与导出统一生效。

**Architecture:** 在 `ReplayIssueQuery` 增加多值 `plannedCompletionDates`，由 Controller 的列表、候选和导出接口透传。`ReplayIssueDao` 复用现有空值特殊值语义生成候选，并将普通日期与 `NULL` 组合为同字段 OR 条件；前端只扩展既有 `headerFilterConfig`，继续使用统一浮层和筛选状态机制。

**Tech Stack:** Java 17+、Spring Boot 3、JdbcTemplate、JUnit 5、Vue 3、Vitest

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` 的“计划完成日期与领域编辑权限 / Excel 式多选筛选”，以及对应数据模型、API 接口页

## Global Constraints

- 页面计划完成日期空值继续显示 `-`，筛选候选使用特殊值“空”。
- “空”固定第一位，其余 `yyyy-MM-dd` 日期按正序排列。
- 同一字段多值按 OR，不同字段与顶部条件按 AND。
- 候选查询排除计划完成日期字段自己的已选值，保留其他条件。
- 服务端先筛选再分页，列表总数和导出使用相同条件。
- 不增加日期区间、顶部日期条件、数据库字段或索引。
- 当前工作区有用户既有未提交改动，不提交、不重置、不清理其他文件。

---

### Task 1: DAO 日期候选和多选 SQL

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueQuery.plannedCompletionDates(): List<String>`
- Produces: `headerFilterValues("plannedCompletionDate", query, keyword)`

- [x] **Step 1: 写 DAO 失败测试**

插入三条同组问题：日期为空、`2026-08-26`、`2026-08-27`。断言候选为 `空, 2026-08-26, 2026-08-27`；查询 `plannedCompletionDates=[2026-08-26, 空]` 返回两条；再叠加其他字段后只返回交集。

- [x] **Step 2: 运行 RED**

Run: `mvn -Dtest=ReplayIssueDaoTest test`

Expected: 编译或断言失败，因为查询对象和 DAO 尚不支持计划完成日期多选。

- [x] **Step 3: 最小实现查询模型和 SQL**

在 record 末尾增加 `List<String> plannedCompletionDates`，所有兼容构造器默认传 `List.of()`。候选查询直接读取日期列并统一格式化；筛选方法规范化日期并识别“空”，生成 `(i.planned_completion_date IN (...) OR i.planned_completion_date IS NULL)`。

- [x] **Step 4: 运行 GREEN**

Run: `mvn -Dtest=ReplayIssueDaoTest test`

Expected: DAO 测试通过，Failures=0、Errors=0。

### Task 2: Controller 列表、候选与导出透传

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `plannedCompletionDates` 重复请求参数
- Produces: 三个接口统一构造带日期多选的 `ReplayIssueQuery`

- [x] **Step 1: 写接口失败测试**

准备空日期和两个非空日期，断言 `/header-filter-options?field=plannedCompletionDate` 返回空值置顶；列表传日期与“空”返回两条；导出传单个日期只输出匹配行。

- [x] **Step 2: 运行 RED**

Run: `mvn -Dtest=ReplayIssueControllerTest#plannedCompletionDateHeaderFilterAppliesToListOptionsAndExport test`

Expected: 新用例失败，因为 Controller 尚未接收 `plannedCompletionDates`。

- [x] **Step 3: 实现三处参数透传**

为 `list`、`headerFilterOptions`、`export` 增加 `@RequestParam(required=false) List<String> plannedCompletionDates`，构造查询时统一使用 `safe(plannedCompletionDates)`。

- [x] **Step 4: 运行 GREEN**

Run: `mvn -Dtest=ReplayIssueControllerTest#plannedCompletionDateHeaderFilterAppliesToListOptionsAndExport test`

Expected: 新接口用例通过。

### Task 3: 前端页头筛选交互

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `field=plannedCompletionDate` 候选接口
- Produces: 列表/导出参数 `plannedCompletionDates: string[]`

- [x] **Step 1: 写前端失败测试**

Mock 候选 `['空','2026-08-26','2026-08-27']`，断言计划完成日期表头有筛选按钮、弹层展示空值和日期、多选后列表请求携带 `plannedCompletionDates`，页面空单元格仍显示 `-`。

- [x] **Step 2: 运行 RED**

Run: `npm test -- src/components/replay/ReplayIssuePage.spec.js`

Expected: 找不到计划完成日期表头筛选按钮。

- [x] **Step 3: 最小扩展筛选配置**

在 `headerFilterConfig` 增加 `planned_completion_date: ['plannedCompletionDate','plannedCompletionDates']`。复用现有 `filterParams`、`headerFilterParams`、全选/反选/清空和浮层样式，不复制新组件。

- [x] **Step 4: 运行 GREEN**

Run: `npm test -- src/components/replay/ReplayIssuePage.spec.js`

Expected: 页面测试通过。

### Task 4: 联合验证与生产构建

**Files:**
- Verify: 上述所有实现和测试文件
- Build output: `src/main/resources/static`

**Interfaces:**
- Produces: 后端可直接服务的最新前端生产静态资源

- [x] **Step 1: 运行后端专项回归**

Run: `mvn -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest#plannedCompletionDateHeaderFilterAppliesToListOptionsAndExport+planDatePermissionsAndPatchEnforceAuthenticationPermissionAndValidation test`

Expected: 相关用例通过。

- [x] **Step 2: 运行前端相关测试与构建**

Run: `npm test -- src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js && npm run build`

Expected: 测试和 Vite 构建通过，产物写入后端 `static`。

- [x] **Step 3: 后端打包与补丁检查**

Run: `mvn -DskipTests package && git diff --check`

Expected: `BUILD SUCCESS`，补丁检查无输出。
