# Replay Planned Completion Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在回放问题清单增加基于全量真实计划完成日期的离散时间轴，按领域和页面开发负责人组合统计已修复、延期修复、未完成、延期未完成，并支持问题明细下钻；同时让“无需处理”审核与缺陷修复日期保持一致。

**Architecture:** 后端以 `dii_replay_issue` 当前投影为唯一统计来源，新增专用只读 DAO 与注入 `Clock` 的统计服务，统一日期吸附、分类表达式、领域/负责人聚合和分页下钻。前端把复杂时间轴和右侧抽屉封装为独立 Vue 组件，由问题清单只负责入口；时间轴使用真实日期点和固定列宽实现离散拖动及横向滚动。数据库迁移仅补齐两类确定的历史数据并增加统计索引，不新增业务统计表。

**Tech Stack:** Java 17、Spring Boot 3、Spring JDBC、Flyway SQL、JUnit 5、H2/MySQL、Vue 3、Vite、Vitest、Lucide Vue、浏览器验收

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`、`/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`、`/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- 统计只读取 `planned_completion_date IS NOT NULL` 的当前问题，不继承问题清单筛选、分页、批次、领域、沙箱或表头条件。
- 日期点只来自数据库现有非空计划日期；不补自然日、不显示 0 数量柱，默认选最新 3 个真实日期点。
- “今天”必须由服务端注入的 `Clock` 得出；汇总、分组和下钻使用同一个 `LocalDate today`。
- 分类固定为：实际日期不晚于计划日期=已修复，实际日期晚于计划日期=延期修复，无实际日期且今天不晚于计划日期=未完成，无实际日期且今天晚于计划日期=延期未完成。
- 完成率固定为 `(已修复 + 延期修复) / 计划问题数`，百分比保留两位；分母为 0 时返回 `null`。
- 开发负责人使用页面动态字段 `matched_developer`；多人组合不拆分，空值统一为“未匹配负责人”。
- 待审核无需处理不写缺陷修复日期；审核通过写审核日期；已审核保持无需处理时保留原日期；审核人员改成其他状态时清空审核字段和缺陷修复日期。
- 审核、状态、缺陷修复日期和问题跟踪快照必须在同一事务中完成。
- 当前后端和前端工作区均包含用户既有改动；不 reset、不清理、不覆盖无关文件、不创建混合提交。
- 设计文档只维护在 Obsidian；项目仓库仅保存本实施计划。

---

### Task 1: 无需处理审核与缺陷修复日期生命周期

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueReviewService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueReviewServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: existing `ReplayIssueReviewService.approve(long, ReplayIssueOperator)` and `ReplayIssueEditService.update(...)` transactions.
- Produces: approved no-action rows with `defectRepairDate=reviewedAt.toLocalDate()`; rows leaving approved no-action with review fields and `defectRepairDate` cleared in one saved snapshot.

- [x] **Step 1: Write the failing review-approval tests**

Add focused tests with a fixed clock:

```java
@Test
void approvalWritesReviewDateAsDefectRepairDateInTheSameSnapshot() {
    ReplayIssueRow approved = service.approve(issueId, new ReplayIssueOperator("reviewer", "审核人"));
    assertEquals(LocalDate.of(2026, 8, 27), approved.defectRepairDate());
    ReplayIssueHistoryEntry history = dao.findHistoryByIssueId(approved.id(), 10).get(0);
    assertTrue(history.afterSnapshot().contains("\"defectRepairDate\":\"2026-08-27\""));
    assertTrue(history.afterSnapshot().contains("\"reviewStatus\":\"APPROVED\""));
}
```

Also assert that calling `approve` for an already approved row is idempotent: it preserves the original defect date and does not add a second history row.

- [x] **Step 2: Run the review tests to verify RED**

Run: `mvn -Dtest=ReplayIssueReviewServiceTest test`

Expected: FAIL because `withApprovedReview` currently preserves `row.defectRepairDate()`.

- [x] **Step 3: Implement approval date writing**

Change the approved projection construction to use the same timestamp already written to `reviewed_at`:

```java
row.importDate(), reviewedAt.toLocalDate(), row.cooperationPersonUsername(),
row.cooperationPersonRealName(), row.globalSerialNo(), ReplayIssueReviewStatus.APPROVED,
operator.username(), operator.realName(), reviewedAt, row.plannedCompletionDate()
```

Do not introduce a second clock read. Keep the existing `updateCurrent + insertHistoryForRound` sequence inside `issueDao.inTransaction`.

- [x] **Step 4: Write the failing edit-transition tests**

Cover all four paths:

```java
@Test void ordinaryUserSelectingNoActionLeavesDefectDateNull() { /* PENDING + null */ }
@Test void reviewerSelectingNoActionAutoApprovesAndWritesOperationDate() { /* APPROVED + 2026-08-27 */ }
@Test void editingApprovedNoActionWithoutChangingStatusPreservesOriginalDefectDate() { /* keep 2026-08-25 */ }
@Test void reviewerLeavingApprovedNoActionClearsReviewAndDefectDateInOneHistorySnapshot() { /* all null */ }
```

For the last test, assert both the returned row and `after_snapshot` contain null review fields and null `defectRepairDate`.

- [x] **Step 5: Run the edit tests to verify RED**

Run: `mvn -Dtest=ReplayIssueEditServiceTest test`

Expected: FAIL on auto-approval date and leaving-approved date clearing.

- [x] **Step 6: Implement one-path date derivation in `edited`**

Derive the saved value before constructing `ReplayIssueRow`:

```java
LocalDate defectRepairDate = row.defectRepairDate();
if (issueStatus == ReplayIssueStatus.NO_ACTION) {
    if (row.issueStatus() == ReplayIssueStatus.NO_ACTION
            && row.reviewStatus() == ReplayIssueReviewStatus.APPROVED) {
        defectRepairDate = row.defectRepairDate();
    } else if (reviewer) {
        defectRepairDate = operationAt.toLocalDate();
    } else {
        defectRepairDate = null;
    }
} else if (row.issueStatus() == ReplayIssueStatus.NO_ACTION
        && row.reviewStatus() == ReplayIssueReviewStatus.APPROVED) {
    defectRepairDate = null;
}
```

Pass `defectRepairDate` into the returned row. Existing authorization already guarantees that only an eligible reviewer can edit an approved no-action problem.

- [x] **Step 7: Run the lifecycle tests GREEN**

Run: `mvn -Dtest=ReplayIssueReviewServiceTest,ReplayIssueEditServiceTest test`

Expected: PASS; no duplicate approval history and no refreshed date for content-only edits.

---

### Task 2: 历史治理迁移与统计数据模型

**Files:**
- Create: `src/main/resources/db/daoindex/V51__dii_replay_issue_completion_statistics.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionCategory.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionDatePoint.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionDatePointsResponse.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionCounts.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionDeveloperRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionGroupRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionDashboard.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionIssueItem.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionIssuePage.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`

**Interfaces:**
- Produces: enum constants `ON_TIME_FIXED`, `LATE_FIXED`, `UNFINISHED`, `OVERDUE_UNFINISHED`.
- Produces: immutable response records using `LocalDate`, counts using `long`, and `BigDecimal completionRate` with scale 2 or null.
- Produces: migrated historical rows and index `idx_replay_planned_completion_stats`.

- [x] **Step 1: Add the migration SQL**

Use the exact approved governance rules:

```sql
UPDATE dii_replay_issue
SET planned_completion_date = defect_repair_date
WHERE issue_status = '已修复'
  AND planned_completion_date IS NULL
  AND defect_repair_date IS NOT NULL;

UPDATE dii_replay_issue
SET defect_repair_date = DATE(reviewed_at)
WHERE issue_status = '无需处理'
  AND review_status = 'APPROVED'
  AND defect_repair_date IS NULL
  AND reviewed_at IS NOT NULL;

CREATE INDEX idx_replay_planned_completion_stats
    ON dii_replay_issue (planned_completion_date, group_name, issue_status);
```

Do not insert synthetic history rows and do not add a trigger that copies actual dates to future plan dates.

- [x] **Step 2: Define the category and count records**

`ReplayIssueCompletionCounts` exposes a single factory for rate calculation:

```java
public record ReplayIssueCompletionCounts(
        long plannedTotal, long onTimeFixedCount, long lateFixedCount,
        long unfinishedCount, long overdueUnfinishedCount,
        BigDecimal completionRate) {
    public static ReplayIssueCompletionCounts of(long onTime, long late, long unfinished, long overdue) {
        long total = onTime + late + unfinished + overdue;
        BigDecimal rate = total == 0 ? null : BigDecimal.valueOf(onTime + late)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new ReplayIssueCompletionCounts(total, onTime, late, unfinished, overdue, rate);
    }
}
```

- [x] **Step 3: Define hierarchy and page records with exact field names**

```java
public record ReplayIssueCompletionDeveloperRow(String matchedDeveloper,
        @JsonUnwrapped ReplayIssueCompletionCounts counts) {}

public record ReplayIssueCompletionGroupRow(String groupName,
        @JsonUnwrapped ReplayIssueCompletionCounts counts,
        List<ReplayIssueCompletionDeveloperRow> developers) {}

public record ReplayIssueCompletionDashboard(LocalDate effectiveStartDate,
        LocalDate effectiveEndDate, LocalDate today,
        ReplayIssueCompletionCounts summary,
        List<ReplayIssueCompletionGroupRow> groups) {}
```

Use `@JsonUnwrapped` exactly as shown so group/developer JSON exposes `plannedTotal`, the four category counts and `completionRate` at the same level as `groupName` or `matchedDeveloper`, matching the approved API contract.

- [x] **Step 4: Extend the H2 fixture schema**

Ensure the test schema contains `planned_completion_date`, `defect_repair_date`, review fields and a unique/one-row transaction-person mapping by `old_transaction_code`, so DAO tests can exercise the same join as production.

- [x] **Step 5: Compile the new types**

Run: `mvn -DskipTests compile`

Expected: BUILD SUCCESS with no raw collections or date strings in the backend model.

---

### Task 3: 专用统计 DAO 与统一分类 SQL

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDao.java`
- Create: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDaoTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`, `LocalDate today`, normalized inclusive `[startDate, endDate]`.
- Produces: `List<ReplayIssueCompletionDatePoint> findDatePoints()`.
- Produces: `List<CompletionAggregateRow> aggregate(LocalDate startDate, LocalDate endDate, LocalDate today)` grouped by `group_name + matched_developer`.
- Produces: `ReplayIssueCompletionIssuePage findIssues(LocalDate startDate, LocalDate endDate, LocalDate today, String groupName, String matchedDeveloper, ReplayIssueCompletionCategory category, int limit, int offset)`.

- [x] **Step 1: Write date-point tests RED**

Insert rows with plan dates `2026-08-20`, `2026-08-20`, `2026-08-22`, null and `2026-08-28`. Assert:

```java
assertEquals(List.of(
    new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 20), 2),
    new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 22), 1),
    new ReplayIssueCompletionDatePoint(LocalDate.of(2026, 8, 28), 1)
), dao.findDatePoints());
```

No missing date and no zero-count point may appear.

- [x] **Step 2: Write four-category and grouping tests RED**

With `today=2026-08-27`, create rows for all boundary cases, including same-day repair, late repair, today-equals-plan unfinished, overdue unfinished, multiple groups, blank developer and developer combination `张三、李四`. Assert each issue is counted exactly once, blank maps to “未匹配负责人”, the combination remains one key, and each group equals the sum of its developer rows.

- [x] **Step 3: Write drill-down tests RED**

Assert category filtering, optional full developer-combination filtering, stable sort by `planned_completion_date, id`, correct `total`, limit/offset, and returned fields `issueId/transactionCode/transactionName/issueStatus/plannedCompletionDate/defectRepairDate/matchedDeveloper/issueKey`.

- [x] **Step 4: Run the DAO test to verify RED**

Run: `mvn -Dtest=ReplayIssueCompletionStatsDaoTest test`

Expected: FAIL because the DAO does not exist.

- [x] **Step 5: Implement a single reusable classification fragment**

Use one SQL builder method for aggregate and detail predicates:

```sql
CASE
  WHEN i.defect_repair_date IS NOT NULL
       AND i.defect_repair_date <= i.planned_completion_date THEN 'ON_TIME_FIXED'
  WHEN i.defect_repair_date IS NOT NULL
       AND i.defect_repair_date > i.planned_completion_date THEN 'LATE_FIXED'
  WHEN i.defect_repair_date IS NULL
       AND ? <= i.planned_completion_date THEN 'UNFINISHED'
  ELSE 'OVERDUE_UNFINISHED'
END
```

The base source must be:

```sql
FROM dii_replay_issue i
LEFT JOIN dii_replay_transaction_person tp
  ON tp.old_transaction_code = i.transaction_code
WHERE i.planned_completion_date IS NOT NULL
  AND i.planned_completion_date BETWEEN ? AND ?
```

Normalize the grouping key with `COALESCE(NULLIF(TRIM(tp.developer), ''), '未匹配负责人')` and never use `transaction_owner`.

- [x] **Step 6: Run DAO tests GREEN**

Run: `mvn -Dtest=ReplayIssueCompletionStatsDaoTest test`

Expected: PASS for date points, exact boundary classifications, reconciliation and pagination.

---

### Task 4: 日期范围服务、HTTP 契约与错误处理

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsService.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionRangeException.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `datePoints(): ReplayIssueCompletionDatePointsResponse` with latest-three defaults.
- Produces: `dashboard(String startDate, String endDate): ReplayIssueCompletionDashboard`.
- Produces: `issues(String startDate, String endDate, String groupName, String matchedDeveloper, String category, int limit, int offset): ReplayIssueCompletionIssuePage`.
- Produces: three GET endpoints under `/api/ai/parallel-replay/issues/stats/planned-completion`.

- [x] **Step 1: Write service range-normalization tests RED**

Cover:

```text
points=[]                         -> defaults null/null
points=[20]                       -> defaults 20/20
points=[20,22,26,28]              -> defaults 22/28
start=21,end=27                   -> effective 22/26
start before first,end after last -> 400 out of range
start=27,end=21                   -> 400 invalid range
invalid yyyy-MM-dd                -> 400 invalid range
```

Use `Clock.fixed(Instant.parse("2026-08-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"))` and assert the DAO receives `today=2026-08-27` exactly once per request path.

- [x] **Step 2: Run service tests RED**

Run: `mvn -Dtest=ReplayIssueCompletionStatsServiceTest test`

Expected: FAIL because the service and response types do not exist.

- [x] **Step 3: Implement strict parsing and snapping**

Use `DateTimeFormatter.ISO_LOCAL_DATE` after a `\d{4}-\d{2}-\d{2}` format check. Normalize with the ordered point list:

```java
LocalDate effectiveStart = points.stream().map(ReplayIssueCompletionDatePoint::date)
        .filter(date -> !date.isBefore(requestedStart)).findFirst().orElseThrow(rangeError);
LocalDate effectiveEnd = points.stream().map(ReplayIssueCompletionDatePoint::date)
        .filter(date -> !date.isAfter(requestedEnd)).reduce((a, b) -> b).orElseThrow(rangeError);
```

Reject `effectiveStart.isAfter(effectiveEnd)`. When both parameters are absent, use latest three. When only one bound is present, normalize missing start to the first real point and missing end to the last real point; lock this behavior in service and controller tests.

- [x] **Step 4: Build group hierarchy and reconciliation checks**

Convert flat DAO aggregate rows into ordered groups and developer rows. Before returning, assert in service code that each group’s four counts equal the sum of its developers and the global summary equals the sum of groups; throw `IllegalStateException("计划完成情况统计口径不一致")` on internal drift.

- [x] **Step 5: Write controller tests RED**

Add MockMvc tests for:

```http
GET /stats/planned-completion/date-points
GET /stats/planned-completion?startDate=2026-08-22&endDate=2026-08-28
GET /stats/planned-completion/issues?startDate=...&endDate=...&groupName=公共组&category=OVERDUE_UNFINISHED&limit=20&offset=0
```

Assert the existing `R<T>` wrapper (`$.code=200`, payload under `$.data`), effective dates, today, flattened count names, exact category validation, invalid range 400, and `limit` in `1..200`, `offset>=0`.

- [x] **Step 6: Run controller tests RED**

Run: `mvn -Dtest=ReplayIssueControllerTest test`

Expected: new routes return 404 or are missing response fields.

- [x] **Step 7: Wire service and controller endpoints**

Add constructor injection for `ReplayIssueCompletionStatsService`. Route methods must not construct `ReplayIssueQuery` and must not accept issue-list filters. Map `ReplayIssueCompletionRangeException` and invalid category/paging arguments to HTTP 400 with the documented Chinese messages.

- [x] **Step 8: Run backend statistics suite GREEN**

Run: `mvn -Dtest=ReplayIssueCompletionStatsDaoTest,ReplayIssueCompletionStatsServiceTest,ReplayIssueControllerTest test`

Expected: new statistics tests PASS. If the complete controller class still contains previously known unrelated batch-tracking assertion failures, isolate and report them; do not weaken the new assertions.

---

### Task 5: 前端 API 与独立完成情况模态框

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Create: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Produces: `getReplayCompletionDatePoints()`.
- Produces: `getReplayCompletionDashboard({ startDate, endDate })`.
- Produces: `getReplayCompletionIssues({ startDate, endDate, groupName, matchedDeveloper, category, limit, offset })`.
- Produces: `<ReplayPlannedCompletionModal :open="boolean" @close="..." />` with no dependency on issue-list filters.

- [x] **Step 1: Write API tests RED**

Assert the exact encoded paths and absence of issue-list parameters:

```js
expect(fetch.mock.calls[0][0]).toBe('/api/ai/parallel-replay/issues/stats/planned-completion/date-points')
expect(fetch.mock.calls[1][0]).toContain('startDate=2026-08-22&endDate=2026-08-28')
expect(fetch.mock.calls[2][0]).toContain('matchedDeveloper=%E5%BC%A0%E4%B8%89%E3%80%81%E6%9D%8E%E5%9B%9B')
```

- [x] **Step 2: Write modal loading/default tests RED**

Mount with four points and assert latest three are selected, date inputs show the effective range, the first bar is low-opacity but remains blue, no zero-count bar exists, and opening the modal requests fresh global statistics without reading parent filters.

- [x] **Step 3: Write discrete timeline tests RED**

Cover:

- each date column has fixed width and `data-date`;
- count is above the bar and date is below it;
- point/handle center uses the same column index as the bar;
- dragging or keyboard movement changes an endpoint by exactly one existing date point;
- start cannot pass end;
- typing a missing start snaps forward and missing end snaps backward after server response;
- “全部日期” selects the first through last point;
- horizontal overflow remains enabled when point count exceeds the viewport.

- [x] **Step 4: Write group/developer and drawer tests RED**

Assert all four counts and completion rate render for each group and developer-combination row. Clicking a count opens one right-side drawer inside the modal, sends the current effective dates plus exact group/developer/category, renders the seven approved detail fields, paginates, and closing the drawer preserves the timeline range.

- [x] **Step 5: Run frontend tests RED**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js`

Workdir: `/Users/java/axon-link-frontend`

Expected: FAIL because API functions and modal do not exist.

- [x] **Step 6: Implement API functions**

Reuse existing `queryString`:

```js
export function getReplayCompletionDatePoints() {
  return request(`${PREFIX}/stats/planned-completion/date-points`)
}
export function getReplayCompletionDashboard(params = {}) {
  const query = queryString(params)
  return request(`${PREFIX}/stats/planned-completion${query ? `?${query}` : ''}`)
}
export function getReplayCompletionIssues(params = {}) {
  const query = queryString(params)
  return request(`${PREFIX}/stats/planned-completion/issues${query ? `?${query}` : ''}`)
}
```

- [x] **Step 7: Implement fixed-column timeline geometry**

Use one shared constant/CSS variable such as `--timeline-step: 88px`. Render bars, labels and point cells from the same `datePoints` array/grid. Keep the slider beneath labels. Custom endpoint handles store array indices, not pixel/date values; pointer movement converts `clientX` to `Math.round((x - firstCenter) / step)` and clamps to `[0, points.length - 1]`. Keyboard ArrowLeft/ArrowRight changes exactly one index. This guarantees points and handles align with bar centers and prevents continuous movement.

- [x] **Step 8: Implement modal, summaries and drawer**

Use a large overlay dialog with one vertically structured content surface. Keep dates in a horizontally scrollable timeline viewport; render totals and two-level table below it. Counts are buttons with category labels and open the internal `<aside>` drawer. Add loading, empty and error states without replacing the last successful range.

- [x] **Step 9: Run modal/API tests GREEN**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js`

Workdir: `/Users/java/axon-link-frontend`

Expected: PASS.

---

### Task 6: 问题清单入口、Mock 数据与真实浏览器验收

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Consumes: `ReplayPlannedCompletionModal`.
- Produces: third summary entry “计划完成情况” beside the two existing entries.
- Produces: local mock endpoints with at least six non-zero date points, four groups, developer combinations and paginated details.

- [x] **Step 1: Write page-integration tests RED**

Assert the three entries are in one row and ordered:

```js
expect(wrapper.findAll('.replay-summary-entry').map(node => node.text())).toEqual([
  '各组问题数', '各组开发负责人问题排名', '计划完成情况'
])
```

Clicking the third opens the modal. Collapsing/expanding the upper query area must not corrupt the modal; closing the modal must leave `filters`, header selections and page number unchanged.

- [x] **Step 2: Run page test RED**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Workdir: `/Users/java/axon-link-frontend`

Expected: FAIL because the third entry is absent.

- [x] **Step 3: Integrate the modal**

Add a button-like summary entry with a chart icon and explicit click handler; unlike the two hover summaries, it opens the modal. Mount `ReplayPlannedCompletionModal` near the page’s existing modal block and pass only `open`; do not pass `filters`, `page`, `pageSize` or header selections.

- [x] **Step 4: Add mock datasets and endpoints**

Mock dates must be non-contiguous and all non-zero, for example `2026-08-18/20/22/25/27/29`, with counts above bars. Include:

```text
公共组 / 张三、李四 / four categories
存款组 / 王五 / overdue unfinished
贷款组 / 未匹配负责人 / unfinished
结算组 / 赵六 / on-time fixed
```

The detail endpoint must filter by inclusive range, group, exact developer combination and category before applying `limit/offset`. Mock data remains under `mock/` and must not be imported by production source.

- [x] **Step 5: Run all focused frontend tests GREEN**

Run: `npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js src/components/replay/ReplayIssuePage.spec.js`

Workdir: `/Users/java/axon-link-frontend`

Expected: PASS.

- [x] **Step 6: Start local mock preview**

Run the repository’s existing mock server and Vite dev server with mock mode enabled. Open `/#replay-issues`, then verify:

```text
default = latest three real dates
all bars one blue hue
count above / date below
point and handle centers align to bars
drag and keyboard move one date point at a time
horizontal scroll preserves column widths
group/developer counts reconcile
drawer filters and paginates
closing modal preserves issue-list state
```

Capture the tested local URL and any screenshots used for comparison; do not treat the standalone brainstorm HTML as production code.

---

### Task 7: 全量验证、静态资源同步与设计日志

**Files:**
- Generated: `/Users/java/axon-link-frontend/dist/**`
- Replace generated bundle: `/Users/java/axon-link-server/src/main/resources/static/**`
- Modify after successful implementation: `/Users/java/obsidian/log.md`

**Interfaces:**
- Consumes: completed backend and frontend tasks.
- Produces: backend-served production frontend and verified Spring Boot JAR.

- [x] **Step 1: Run backend focused regression**

Run:

```bash
mvn -Dtest=ReplayIssueReviewServiceTest,ReplayIssueEditServiceTest,ReplayIssueCompletionStatsDaoTest,ReplayIssueCompletionStatsServiceTest,ReplayIssueControllerTest test
```

Expected: all new lifecycle/statistics assertions PASS. Record any unrelated pre-existing failures with exact test names rather than changing expectations.

- [x] **Step 2: Run frontend focused regression**

Run:

```bash
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayPlannedCompletionModal.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Workdir: `/Users/java/axon-link-frontend`

Expected: PASS.

- [x] **Step 3: Run production frontend build**

Run: `npm run build`

Workdir: `/Users/java/axon-link-frontend`

Expected: Vite exits 0 and creates `dist/index.html` plus hashed assets.

- [x] **Step 4: Synchronize only the backend static directory**

Replace `/Users/java/axon-link-server/src/main/resources/static` contents with `/Users/java/axon-link-frontend/dist` contents using the existing packaging workflow. Verify every asset referenced by `static/index.html` exists and that the built JS contains `/stats/planned-completion/date-points` and “计划完成情况”.

- [x] **Step 5: Package and inspect the JAR**

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS. Inspect `target/axon-link-server-1.0.0.jar` and confirm it contains the new `index.html`, hashed JS/CSS assets and `V51__dii_replay_issue_completion_statistics.sql`.

- [x] **Step 6: Run final whitespace and scope checks**

Run:

```bash
git diff --check
git status --short
```

Also run the equivalent commands in `/Users/java/axon-link-frontend`. Confirm no mock-only data entered production source and no unrelated dirty files were overwritten.

- [x] **Step 7: Append the implementation log only after verification**

Append one `2026-08-27 [IMPL]` entry to `/Users/java/obsidian/log.md` listing the lifecycle fix, date-point statistics, frontend modal/drawer, focused test results and JAR verification. Do not duplicate the design text or create a repository spec.
