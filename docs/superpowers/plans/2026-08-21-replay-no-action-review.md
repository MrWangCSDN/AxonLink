# Replay No-Action Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the manually selectable “无需处理” status, enforce status/type binding, and add group-scoped reviewer approval, filtering, statistics, export, tracking, and import inheritance.

**Architecture:** Store the current review projection on `dii_replay_issue`, mirror the after-state review fields on the existing history table, and keep the full audit path in the current replay issue timeline. A focused review service resolves YML-configured employee numbers through `ccbs_ai_sys_user`, authorizes by normalized `group_name`, performs idempotent approval, and is reused by editing and controller capability endpoints. Existing merge, query, statistics, export, and Vue flows are extended without creating a second audit subsystem.

**Tech Stack:** Java 17, Spring Boot, Spring JDBC, Jackson, H2/MySQL-compatible migrations, JUnit 5, MockMvc, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Preserve all unrelated dirty-worktree changes; edit only task-scoped files with `apply_patch`.
- Do not reset, clean, checkout, commit, or push unless the user explicitly requests it.
- Historical rows are not backfilled or rewritten based on issue type.
- Review authorization is based on `dii_replay_issue.group_name` and configured `ccbs_ai_sys_user.emp_no`; sandbox does not create a separate reviewer scope.
- “无需处理” must pair with “合理差异”; “延后修复” must pair with “迁移问题”. Both frontend and backend enforce the pair.
- “无需处理” and “修复待验证” never trigger collaboration mail confirmation or sending.
- Backend authorization is authoritative; frontend disabled states are presentation only.
- Existing RPT/DZ import and daily-report family behavior must remain unchanged.

---

### Task 1: Persist Review State and Bind Reviewer Configuration

**Files:**
- Create: `src/main/resources/db/daoindex/V49__dii_replay_issue_review.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueReviewStatus.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueReviewPermissions.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueReviewProperties.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueReviewService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHistoryEntry.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/user/persistence/SysUserDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueReviewServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueReviewStatus { PENDING, APPROVED }`.
- Produces: `ReplayIssueReviewService.isReviewer(String groupName, ReplayIssueOperator operator): boolean`.
- Produces: `ReplayIssueReviewService.permissions(ReplayIssueOperator operator): ReplayIssueReviewPermissions`.
- Produces: review fields appended to `ReplayIssueRow`: `reviewStatus`, `reviewerUsername`, `reviewerRealName`, `reviewedAt`.

- [ ] **Step 1: Write failing persistence and authorization tests**

Add tests proving that current rows and history rows round-trip all four review fields, and authorization uses employee number plus exact normalized group:

```java
assertTrue(service.isReviewer("公共组", new ReplayIssueOperator("zhangsan", "张三")));
assertFalse(service.isReviewer("存款组", new ReplayIssueOperator("zhangsan", "张三")));
assertEquals(List.of("公共组"), service.permissions(operator).reviewableGroups());
assertEquals(List.of("张三", "李四"), service.permissions(operator).reviewersByGroup().get("公共组"));
```

Cover missing users, inactive users, blank employee numbers, unknown groups, and sandbox-independent group reuse.

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
mvn -q -Dtest=ReplayIssueReviewServiceTest,ReplayIssueDaoTest test
```

Expected: compilation/test failure because review types, columns, and service do not exist.

- [ ] **Step 3: Add migration and model fields**

Migration requirements:

```sql
ALTER TABLE dii_replay_issue
  ADD COLUMN review_status VARCHAR(16) NULL COMMENT 'PENDING/APPROVED',
  ADD COLUMN reviewer_username VARCHAR(128) NULL,
  ADD COLUMN reviewer_real_name VARCHAR(128) NULL,
  ADD COLUMN reviewed_at DATETIME NULL,
  ADD INDEX idx_replay_review_status (review_status);

ALTER TABLE dii_replay_issue_history
  ADD COLUMN review_status VARCHAR(16) NULL,
  ADD COLUMN reviewer_username VARCHAR(128) NULL,
  ADD COLUMN reviewer_real_name VARCHAR(128) NULL,
  ADD COLUMN reviewed_at DATETIME NULL;
```

Append the Java fields to records to minimize constructor churn and provide compatibility constructors that default them to `null`. Update fixture schemas, DAO insert/update/bind/map/history projection, and JSON snapshot extraction.

- [ ] **Step 4: Implement configuration and reviewer resolution**

Bind a map shaped as:

```java
@ConfigurationProperties(prefix = "dii.replay.issue-review")
public class ReplayIssueReviewProperties {
    private Map<String, ReviewerGroup> reviewers = new LinkedHashMap<>();
    public static class ReviewerGroup {
        private List<String> empNos = new ArrayList<>();
    }
}
```

Add `SysUserDao.findActiveByEmpNo(String)` and `findActiveByEmpNos(Collection<String>)`. `ReplayIssueReviewService` resolves the current username to an active user, compares its trimmed `empNo` against the configured set for the exact `groupName`, and builds stable reviewer display names in configured employee-number order. Unknown configured employee numbers log a warning and grant no permission.

Add empty default groups to `application.yml` so production must explicitly provide real employee numbers:

```yaml
dii:
  replay:
    issue-review:
      reviewers:
        公共组: { emp-nos: [] }
        存款组: { emp-nos: [] }
        贷款组: { emp-nos: [] }
        结算组: { emp-nos: [] }
```

- [ ] **Step 5: Run tests to verify GREEN**

Run:

```bash
mvn -q -Dtest=ReplayIssueReviewServiceTest,ReplayIssueDaoTest test
```

Expected: all selected tests pass with review-field round trips and group authorization covered.

---

### Task 2: Enforce Status/Type Binding and Approval Workflow

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueStatus.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueReviewService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMailService.java`
- Test: `src/test/java/com/axonlink/ai/replay/dto/ReplayIssueStatusTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueReviewServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMailServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: Task 1 review status, row fields, and reviewer authorization.
- Produces: `ReplayIssueStatus.NO_ACTION("无需处理", true)` immediately after `OPEN`.
- Produces: `ReplayIssueReviewService.approve(long issueId, ReplayIssueOperator operator): ReplayIssueRow`.
- Produces: `GET /review-permissions` and `POST /{id}/review/approve`.

- [ ] **Step 1: Write failing state and edit tests**

Cover these exact cases:

```java
assertEquals(List.of("打开", "无需处理", "延后修复", "修复待验证"),
        ReplayIssueStatus.manuallySelectableValues().stream().map(ReplayIssueStatus::displayValue).toList());
```

- Ordinary user selects `无需处理 + 合理差异` -> `PENDING`, reviewer fields empty.
- Configured reviewer selects the same combination -> `APPROVED`, reviewer and time populated.
- `无需处理 + 代码问题` and `延后修复 + 合理差异` -> HTTP/service 400 validation errors.
- Pending or approved no-action issue edited by ordinary/cross-group user -> 403-style permission exception.
- Same-group reviewer keeps no-action -> `APPROVED`; changes to another status -> all review fields cleared.
- Approved no-action issue remains non-editable for ordinary users.

Name the controller tests `reviewPermissionsExposeConfiguredGroupNames`, `reviewApproveRequiresSameGroupReviewer`, and `reviewApproveIsIdempotent` so the focused command below is deterministic.

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
mvn -q -Dtest=ReplayIssueStatusTest,ReplayIssueEditServiceTest,ReplayIssueReviewServiceTest test
```

Expected: failure because `NO_ACTION`, binding rules, and approval behavior are absent.

- [ ] **Step 3: Implement edit-state rules in one transaction**

In `ReplayIssueEditService`:

```java
if (requested == ReplayIssueStatus.NO_ACTION && !"合理差异".equals(issueType)) {
    throw new IllegalArgumentException("无需处理状态的问题类型必须为合理差异");
}
if (requested == ReplayIssueStatus.DEFERRED && !"迁移问题".equals(issueType)) {
    throw new IllegalArgumentException("延后修复状态的问题类型必须为迁移问题");
}
```

Before editing a current no-action issue, require `reviewService.isReviewer(before.groupName(), operator)`. When entering no-action, assign `APPROVED` immediately for a reviewer, otherwise `PENDING`. When a reviewer leaves no-action, clear all review fields. Preserve approved review data when the reviewer edits content without leaving no-action. Every successful save writes one existing `人工保存` history event with full snapshots.

- [ ] **Step 4: Implement idempotent approval and controller endpoints**

`approve` must lock the issue row. If already approved, return it without updating or writing history. Otherwise require current status `NO_ACTION`, review status `PENDING`, and same-group permission; set reviewer identity/time and insert one `审核通过` history event associated with the issue's latest batch context.

Return HTTP 403 with:

```text
没有权限，请联系张三、李四进行审核
```

Return HTTP 409 only when the locked current state no longer matches the approval request because of a concurrent state change. Add `GET /review-permissions` for UI capabilities and names.

- [ ] **Step 5: Suppress mail for no-action status**

Use one server-side predicate shared by preview/send flow:

```java
private boolean mailSuppressed(ReplayIssueStatus status) {
    return status == ReplayIssueStatus.PENDING_VERIFICATION || status == ReplayIssueStatus.NO_ACTION;
}
```

Direct calls to the mail-send endpoint must also refuse/safely skip no-action issues; frontend suppression alone is insufficient.

- [ ] **Step 6: Run focused tests to verify GREEN**

Run:

```bash
mvn -q -Dtest=ReplayIssueStatusTest,ReplayIssueEditServiceTest,ReplayIssueReviewServiceTest,ReplayIssueMailServiceTest,'ReplayIssueControllerTest#reviewPermissionsExposeConfiguredGroupNames+reviewApproveRequiresSameGroupReviewer+reviewApproveIsIdempotent' test
```

Expected: status order, authorization, approval history, idempotency, and mail suppression pass.

---

### Task 3: Extend Import Merge and Batch Tracking

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRoundEntry.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHistoryEntry.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueHistoryDaoTest.java`

**Interfaces:**
- Consumes: review fields added in Task 1 and `NO_ACTION` added in Task 2.
- Produces: no-action same-key inheritance and missing-key auto-repair with review clearing.

- [ ] **Step 1: Write failing merge tests**

Test four exact paths:

1. New batch, same key, current no-action/pending: Excel base fields update; issue status, five edited fields, and `PENDING` stay unchanged; history action is `基础数据覆盖，人工内容及审核结果继承`.
2. New batch, same key, current no-action/approved: reviewer identity and timestamp are inherited.
3. Same batch re-import: current base fields can refresh, but no duplicate issue-round/history event is inserted.
4. Missing key, current no-action: status becomes fixed, defect date follows existing rule, all review fields become null, and history snapshots show the clearing.

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
mvn -q -Dtest=ReplayIssueMergeServiceTest,ReplayIssueHistoryDaoTest test
```

Expected: no-action is not merged as an active inheriting state and is not an auto-repair candidate.

- [ ] **Step 3: Implement no-action inheritance and auto-repair**

Add `NO_ACTION` to the inheriting branch alongside new/open/deferred, but preserve its review projection in `refreshed`. Add it to `findAutoRepairCandidatesMissing`. Make `withStatusAndDefectDate(... FIXED ...)` clear review fields only when moving out of no-action. Use the specified inheritance action text and include review fields in before/after snapshots and round tracking.

Ensure fixed reappearance still creates `NEW` with no review state. Do not change RPT/DZ batch normalization or same-family daily report logic.

- [ ] **Step 4: Run tests to verify GREEN and import regression safety**

Run:

```bash
mvn -q -Dtest=ReplayIssueMergeServiceTest,ReplayIssueHistoryDaoTest,ReplayIssueImportServiceTest,ReplayIssueSummaryImportIntegrationTest,ReplayIssueDailyReportServiceTest test
```

Expected: no-action merge tests and existing import/daily report tests pass.

---

### Task 4: Add Review Filtering, Statistics, Export, and Tracking Projections

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueQuery.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueFilterOptions.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueGroupSummary.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePersonRanking.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHistoryEntry.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: query fields `reviewStatus`, `reviewStatuses`.
- Produces: stats keys `noActionTotal`, `noActionCount`.
- Produces: list/export/tracking review columns.

- [ ] **Step 1: Write failing DAO and controller tests**

Assert:

- Status options order is `新建, 打开, 无需处理, 延后修复, 修复待验证, 重新打开, 已修复`.
- Review options are `待审核, 已审核`.
- Single review filter, multi-value review header filter, and empty-review special value all filter before pagination.
- `reviewStatus` combines with group/status/weekly-task filters via AND.
- `/stats` returns `noActionTotal`.
- Group and person ranking rows return `noActionCount` between open and reopened/deferred columns.
- Export headers place `审核状态, 审核人, 审核时间` immediately after `问题状态` and export all filtered rows.
- Tracking JSON exposes review state, reviewer, review time, and approval operation.

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
mvn -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest test
```

Expected: missing query fields, counts, and output columns cause failures.

- [ ] **Step 3: Extend parameterized query builders**

Map display filters `待审核 -> PENDING`, `已审核 -> APPROVED`. The empty sentinel must match `review_status IS NULL OR TRIM(review_status)=''`. Add review status to header option SQL while preserving the rule that candidate values depend on large filters and all other active header filters.

Add SQL aggregates:

```sql
SUM(CASE WHEN issue_status = '无需处理' THEN 1 ELSE 0 END) AS no_action_total
```

and `no_action_count` to group/person aggregation. Keep total-count semantics unchanged and continue excluding fixed rows from hover tables.

- [ ] **Step 4: Extend options, export, and tracking payloads**

Return review option labels separately from issue statuses. Export review display labels rather than raw codes, use `姓名(username)` for the reviewer when available, and use a stable date-time format for `reviewed_at`. Add review fields to tracking events without creating a separate review timeline.

- [ ] **Step 5: Run tests to verify GREEN**

Run:

```bash
mvn -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest test
```

Expected: query, aggregation, export, and tracking tests pass except any explicitly documented pre-existing batch-tracking assertions; new review tests must all pass.

---

### Task 5: Implement Vue Status Binding, Review Column, Approval Interaction, and Summary Columns

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: Tasks 2 and 4 controller endpoints and response keys.
- Produces: query/header review filters, approval click flow, reviewer-aware edit disabled state, forced issue type UI, and no-action summary columns.

- [ ] **Step 1: Write failing API tests**

Test:

```js
await getReplayIssueReviewPermissions()
expect(fetch.mock.calls[0][0]).toBe('/api/ai/parallel-replay/issues/review-permissions')

await approveReplayIssue(42)
expect(fetch.mock.calls[1][0]).toBe('/api/ai/parallel-replay/issues/42/review/approve')
expect(fetch.mock.calls[1][1].method).toBe('POST')
```

Also assert list/export encode `reviewStatus` and `reviewStatuses` and preserve backend 403 messages.

- [ ] **Step 2: Write failing page interaction tests**

Cover:

- “无需处理” appears after “打开” in edit and query status options.
- Selecting no-action sets `issueType` to `合理差异` and disables the type control.
- Selecting deferred sets `issueType` to `迁移问题` and disables the type control.
- Selecting open/pending verification re-enables type selection.
- Review column shows `-`, clickable `待审核`, and `已审核` with reviewer tooltip.
- Unauthorized approval displays the backend contact message.
- Successful approval asks for confirmation, refreshes row/stats/tracking, and renders approved.
- Pending/approved no-action edit is disabled for non-reviewers and enabled for same-group reviewers.
- No-action save does not open the mail confirmation flow.
- Review condition and Excel-style header filters reset with the existing query/reset behavior.
- Top cards, group table, person ranking, and copied TSV include “无需处理”.

- [ ] **Step 3: Run frontend tests to verify RED**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: new API functions, controls, column, and counts are absent.

- [ ] **Step 4: Implement API and reactive state**

Add API functions and query encoding. In the page, load review permissions with metadata, keep `reviewableGroups` as a set, and expose:

```js
const canReview = row => reviewableGroups.value.has(row.group_name)
const reviewLocked = row => row.issue_status === '无需处理' && !canReview(row)
const issueTypeLocked = computed(() => ['无需处理', '延后修复'].includes(editDraft.issueStatus))
```

Watch `editDraft.issueStatus` to force `合理差异` or `迁移问题`. Do not rely only on the watcher; initialize the forced value when opening an existing row.

- [ ] **Step 5: Implement review UI and statistics presentation**

Insert review status immediately after issue status. Pending remains clickable for all users so unauthorized users receive the requested contact message; approval uses the existing modal/notification visual language and a confirmation dialog. Approved tooltip displays reviewer and time. Disable editing of pending/approved no-action rows unless `canReview(row)`.

Add review conditions/header filter, no-action card/count columns, export query fields, tracking review details, and TSV copy order. Suppress mail confirmation whenever saved or draft status is no-action.

- [ ] **Step 6: Run focused frontend tests to verify GREEN**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: all focused API and page tests pass.

---

### Task 6: Full Verification, Production Build, and Documentation Closure

**Files:**
- Modify after successful implementation: `/Users/java/obsidian/log.md`
- Regenerate: `src/main/resources/static/**`
- Regenerate: `target/axon-link-server-1.0.0.jar`

**Interfaces:**
- Consumes: all previous task outputs.
- Produces: verified backend, embedded frontend, executable JAR, and implementation audit entry.

- [ ] **Step 1: Run backend focused regression**

Run:

```bash
mvn -q -Dtest=ReplayIssueStatusTest,ReplayIssueReviewServiceTest,ReplayIssueEditServiceTest,ReplayIssueMergeServiceTest,ReplayIssueMailServiceTest,ReplayIssueDaoTest,ReplayIssueHistoryDaoTest,ReplayIssueImportServiceTest,ReplayIssueSummaryImportIntegrationTest,ReplayIssueDailyReportServiceTest,ReplayIssueControllerTest test
```

Expected: all new no-action/review tests pass. If the three known batch-tracking controller assertions still fail, report their exact names separately and rerun the new controller methods plus all non-controller focused suites to prove this feature independently.

- [ ] **Step 2: Run all frontend tests**

Run:

```bash
cd /Users/java/axon-link-frontend
npm test
```

Expected: all Vitest files pass with zero failed workers.

- [ ] **Step 3: Build frontend into backend and package JAR**

Run:

```bash
cd /Users/java/axon-link-frontend
VITE_USE_MOCK=0 npm run build
cd /Users/java/axon-link-server
mvn -q -DskipTests package
```

Verify:

```bash
jar tf target/axon-link-server-1.0.0.jar \
  | rg 'BOOT-INF/classes/static/index.html|ReplayIssueReviewService.class|V49__dii_replay_issue_review.sql'
```

Expected: all three entries exist.

- [ ] **Step 4: Run residual and formatting checks**

Run:

```bash
rg -n "无需处理|reviewStatus|noActionCount|issue-review" \
  src/main/java src/main/resources src/test/java /Users/java/axon-link-frontend/src
git diff --check
git -C /Users/java/axon-link-frontend diff --check
```

Expected: feature references exist across backend/frontend/tests and both diff checks are empty.

- [ ] **Step 5: Append implementation evidence to Obsidian log**

Append one `[IMPL]` line containing the changed subsystems, exact test counts, production build result, JAR verification, and any pre-existing unrelated failures. Do not claim the full suite passed if it did not.
