# Replay Plan-Date Owner Permission and History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow enabled bank-employee developers to edit plan validation dates for their own transactions, and show an unlimited, newest-first count/history of every real plan-date change made after rollout.

**Architecture:** Extend the existing plan-date permission projection with transaction codes derived by exact `username` matching against split `developer_usernames`, while preserving YAML group permissions as an OR path. Persist each real post-rollout plan-date change in a dedicated audit table in the same transaction as the current date and general issue history; list rows expose only a derived count and tooltip details load lazily from a focused endpoint.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, Flyway-style SQL migrations, JUnit 5, Vue 3, Vite, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` sections “计划验证日期与领域编辑权限（2026-08-26）” and “计划验证日期修改次数与明细（2026-08-31）”, with matching data-model and API documents.

## Global Constraints

- Authorization is `YAML group permission OR enabled bank employee owns the transaction as a developer`.
- A bank employee requires `ccbs_ai_sys_user.status = 1` and a non-blank `emp_no`.
- Developer ownership uses the authenticated user's normalized `username`, split-token exact matching against `dii_replay_transaction_person.developer_usernames`; never use substring matching or display-name parsing.
- A non-bank employee can still receive YAML group permission but never automatic developer ownership permission.
- A non-null `defect_repair_date` locks plan-date editing for everyone before all other checks.
- Existing strict `yyyy-MM-dd`, first-occurrence-plus-seven-days validation, import preservation, and general issue tracking remain unchanged.
- Every normalized value change counts once, including initial fill, clear, and refill. Same-value saves count zero.
- Each dedicated history row stores only the resulting plan date; a cleared value is `NULL` and displays as `-`.
- Do not migrate or infer pre-rollout history; counts start at zero when the new table is deployed.
- History is newest first and unlimited; Excel export does not include the count or history.
- Preserve all unrelated dirty-worktree changes. Do not reset, clean, overwrite, or commit unrelated files.

---

### Task 1: Persist plan-date change history and derived counts

**Files:**
- Create: `src/main/resources/db/daoindex/V53__dii_replay_issue_plan_date_change.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlanDateChangeEntry.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces `long countPlanDateChanges(long issueId)`.
- Produces `void insertPlanDateChange(long issueId, String issueKey, LocalDate plannedDate, ReplayIssueOperator operator, LocalDateTime changedAt)`.
- Produces `List<ReplayIssuePlanDateChangeEntry> listPlanDateChanges(long issueId)`.
- List row maps expose `planned_completion_date_change_count` as a number.

- [ ] **Step 1: Write failing DAO tests**

Add tests that insert three changes (`2026-08-05`, `2026-08-07`, `NULL`) and assert count `3`, nullable resulting value, operator fields, and `changedAt DESC, id DESC` order. Add a list projection assertion that a row with no new-table history returns `0` and a row with two records returns `2`.

```java
assertEquals(3L, dao.countPlanDateChanges(issueId));
List<ReplayIssuePlanDateChangeEntry> items = dao.listPlanDateChanges(issueId);
assertNull(items.get(0).plannedCompletionDate());
assertEquals(LocalDate.of(2026, 8, 7), items.get(1).plannedCompletionDate());
assertEquals(2L, ((Number) dao.list(ALL).get(0)
        .get("planned_completion_date_change_count")).longValue());
```

- [ ] **Step 2: Run the DAO test and verify RED**

Run: `mvn -Dtest=ReplayIssueDaoTest test`

Expected: compilation or assertion failure because the migration-backed fixture table, DTO, DAO methods, and projection do not exist.

- [ ] **Step 3: Add the table, DTO, and DAO methods**

Use the exact table contract:

```sql
CREATE TABLE IF NOT EXISTS dii_replay_issue_plan_date_change (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  replay_issue_id BIGINT NOT NULL,
  issue_key VARCHAR(1024) NOT NULL,
  planned_completion_date DATE NULL,
  operator_username VARCHAR(128),
  operator_real_name VARCHAR(128),
  changed_at DATETIME NOT NULL,
  INDEX idx_replay_plan_date_change_issue_time (replay_issue_id, changed_at, id),
  INDEX idx_replay_plan_date_change_key_time (issue_key(191), changed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='并行回放问题计划验证日期修改历史';
```

Define the entry record exactly as:

```java
public record ReplayIssuePlanDateChangeEntry(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate plannedCompletionDate,
        String operatorUsername,
        String operatorRealName,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime changedAt) {}
```

Add a correlated count projection beside `issue_domain_transfer_count`, and normalize SQL `DATE` / `TIMESTAMP` values in the row mapper.

- [ ] **Step 4: Re-run the DAO test and verify GREEN**

Run: `mvn -Dtest=ReplayIssueDaoTest test`

Expected: all `ReplayIssueDaoTest` tests pass.

---

### Task 2: Add exact developer-username transaction ownership

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayTransactionPersonDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayTransactionPersonDaoTest.java`

**Interfaces:**
- Produces `List<String> findTransactionCodesByDeveloperUsername(String username)`.
- Matching delimiters are exactly `[、,，;；]`, with trimming, blank removal, distinct transaction codes, and exact token equality.

- [ ] **Step 1: Write failing exact-match tests**

Cover one username owning multiple transaction codes, repeated tokens, Chinese/English delimiters, blanks, and the critical non-match `c-wang` versus `c-wang1`.

```java
assertEquals(List.of("6208", "6210"),
        dao.findTransactionCodesByDeveloperUsername(" c-zhangs "));
assertTrue(dao.findTransactionCodesByDeveloperUsername("c-wang").isEmpty());
```

- [ ] **Step 2: Run the persistence test and verify RED**

Run: `mvn -Dtest=ReplayTransactionPersonDaoTest test`

Expected: compilation failure because `findTransactionCodesByDeveloperUsername` is absent.

- [ ] **Step 3: Implement token-exact ownership lookup**

Read only rows with non-blank `developer_usernames`, split with the existing personnel delimiter family, compare each normalized token with `String.equals`, then trim/filter/distinct transaction codes. Do not write a SQL `LIKE '%username%'` predicate.

```java
public List<String> findTransactionCodesByDeveloperUsername(String username) {
    if (username == null || username.isBlank()) return List.of();
    String expected = username.trim();
    return /* query rows */.stream()
            .filter(row -> splitEmployeeNumbers(row[1]).contains(expected))
            .map(row -> row[0]).filter(Objects::nonNull)
            .map(String::trim).filter(code -> !code.isBlank()).distinct().toList();
}
```

- [ ] **Step 4: Re-run the persistence test and verify GREEN**

Run: `mvn -Dtest=ReplayTransactionPersonDaoTest test`

Expected: all targeted tests pass, including the prefix non-match.

---

### Task 3: Extend plan-date permissions and enforce the same rule on PATCH

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlanDatePermissions.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `ReplayIssuePlanDatePermissions(List<String> editableGroups, List<String> editableTransactionCodes)`.
- Service constructor receives `ReplayTransactionPersonDao`.
- `canEdit` evaluates YAML group permission OR enabled/non-blank-emp-no user ownership of the row's transaction code.

- [ ] **Step 1: Write failing permission projection tests**

Add users for: enabled employee owner, enabled employee non-owner, enabled user without emp-no, disabled employee, and a YAML-only user. Assert the response union and deduped transaction codes.

```java
ReplayIssuePlanDatePermissions permissions = service.permissions(employeeOwner);
assertEquals(List.of("公共组"), permissions.editableGroups());
assertEquals(List.of("6208", "6210"), permissions.editableTransactionCodes());
assertTrue(service.permissions(noEmpNoOwner).editableTransactionCodes().isEmpty());
```

- [ ] **Step 2: Write failing PATCH authorization tests**

Assert an employee owner can update only an owned transaction, cannot update another transaction, a YAML editor can still update its group, a disabled employee is rejected, and every path is rejected when `defect_repair_date` is non-null.

```java
assertEquals(LocalDate.of(2026, 8, 26),
        service.update(ownedIssueId, "2026-08-26", employeeOwner)
                .plannedCompletionDate());
assertThrows(ReplayIssuePlanDateForbiddenException.class,
        () -> service.update(otherIssueId, "2026-08-26", employeeOwner));
```

- [ ] **Step 3: Run service/controller tests and verify RED**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test`

Expected: failures because the DTO has one field and ownership is not considered.

- [ ] **Step 4: Implement the permission union**

Resolve one active `SysUser`. Compute YAML groups using the existing identity fallback. Compute transaction codes only when `user.getEmpNo()` is non-blank, using `user.getUsername()` and Task 2's exact-match DAO method.

```java
boolean groupAllowed = yamlGroups(user).contains(row.groupName());
boolean ownedTransaction = isBankEmployee(user)
        && personDao.findTransactionCodesByDeveloperUsername(user.getUsername())
                .contains(row.transactionCode());
return groupAllowed || ownedTransaction;
```

Use identical logic for the permission response and the locked-row PATCH authorization. Keep repair-date lock before permission checks and preserve strict date validation.

- [ ] **Step 5: Re-run service/controller tests and verify GREEN**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test`

Expected: all targeted tests pass.

---

### Task 4: Write change history transactionally and expose its API

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlanDateChanges.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePlanDateUpdateResult.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssuePlanDateServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `ReplayIssuePlanDateUpdateResult(long id, LocalDate plannedCompletionDate, long changeCount)`.
- `ReplayIssuePlanDateChanges(long changeCount, List<ReplayIssuePlanDateChangeEntry> items)`.
- `PATCH /{id}/planned-completion-date` returns the update result.
- `GET /{id}/planned-completion-date-changes` returns newest-first history.

- [ ] **Step 1: Write failing service history tests**

Exercise `null -> date -> another date -> null -> date`. Assert counts `1..4`, exactly one dedicated row per successful change, resulting values only, newest-first order, and no new dedicated/general history for a same-value call.

```java
assertEquals(1L, service.update(issueId, "2026-08-05", operator).changeCount());
assertEquals(2L, service.update(issueId, "2026-08-07", operator).changeCount());
assertEquals(3L, service.update(issueId, null, operator).changeCount());
assertEquals(4L, service.update(issueId, "2026-08-08", operator).changeCount());
assertEquals(4L, service.update(issueId, "2026-08-08", operator).changeCount());
```

- [ ] **Step 2: Write failing endpoint tests**

Assert PATCH returns `plannedCompletionDate` and `changeCount`, GET returns `changeCount/items`, nullable clear values serialize as `null`, unauthenticated requests return 401, and a missing issue returns 404.

- [ ] **Step 3: Run service/controller tests and verify RED**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test`

Expected: compilation/assertion failure because result/history DTOs and the GET endpoint do not exist.

- [ ] **Step 4: Implement atomic write and lazy history read**

Within the existing `issueDao.inTransaction`, after all validation and only when values differ:

```java
dao.updatePlannedCompletionDate(issueId, plannedDate);
LocalDateTime changedAt = LocalDateTime.now(clock);
dao.insertPlanDateChange(issueId, before.issueKey(), plannedDate, operator, changedAt);
// retain existing insertHistoryForRound(...) and latest-batch update
long count = dao.countPlanDateChanges(issueId);
return new ReplayIssuePlanDateUpdateResult(issueId, plannedDate, count);
```

For same values, return the current date and current dedicated count without writes. Implement `changes(issueId)` with an existence check, count, and ordered items. Map forbidden/invalid/missing cases to the existing 403/400/404 conventions.

- [ ] **Step 5: Re-run service/controller tests and verify GREEN**

Run: `mvn -Dtest=ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test`

Expected: all targeted tests pass, including count/history and rollback behavior.

---

### Task 5: Add the combined plan-date cell history UI and Mock behavior

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Frontend API `getReplayIssuePlanDateChanges(id)` calls `/{id}/planned-completion-date-changes`.
- Permission state is `{ editableGroups: [], editableTransactionCodes: [] }`.
- `canEditPlanDate(row)` accepts a group match OR transaction-code match, unless the repair date locks the row.
- Row field `planned_completion_date_change_count` drives the badge.

- [ ] **Step 1: Write failing API tests**

```js
await getReplayIssuePlanDateChanges(17)
expect(request).toHaveBeenCalledWith(
  '/ai/parallel-replay/issues/17/planned-completion-date-changes'
)
```

- [ ] **Step 2: Write failing component tests**

Cover: transaction-only permission enables editing; no-emp behavior is represented by an empty transaction-code response; repair date still disables; count zero hides the icon; count greater than zero lazy-loads once; newest item appears first; clear displays `计划时间：-`; successful saves use server `changeCount`; same-value blur does not call PATCH or alter count.

```js
expect(wrapper.get('[data-testid="plan-date-change-count-1"]').text()).toContain('2')
await wrapper.get('[data-testid="plan-date-change-count-1"]').trigger('mouseenter')
expect(getReplayIssuePlanDateChanges).toHaveBeenCalledWith(1)
```

- [ ] **Step 3: Run focused frontend tests and verify RED**

Run in `/Users/java/axon-link-frontend`:

`npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`

Expected: failures for absent API, permissions, badge, and tooltip behavior.

- [ ] **Step 4: Implement frontend API, permissions, and cell UI**

Place the history badge after the editable button/read-only date within the existing plan-date cell. Reuse the domain-history visual classes where safe, but keep separate loading/cache/error maps keyed by issue ID. Render each item as:

```text
计划时间：2026-08-07   张三（c-zhangs）   2026-08-07 17:09:09
```

Render a nullable resulting date as `计划时间：-`. After PATCH, set both `row.planned_completion_date` and `row.planned_completion_date_change_count` from the response, and invalidate only that row's cached history.

- [ ] **Step 5: Extend local Mock data and endpoints**

Mock at least one group-permission row, one transaction-only permission row, histories with one and multiple changes, and a clear record. PATCH must append only when the normalized value differs, return the authoritative count, and continue enforcing repair-date, format, and seven-day locks.

- [ ] **Step 6: Re-run focused frontend tests and verify GREEN**

Run in `/Users/java/axon-link-frontend`:

`npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js`

Expected: all focused tests pass.

---

### Task 6: Full verification, static packaging, and visual acceptance

**Files:**
- Modify generated backend static assets through the existing frontend build output only.
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Production frontend build is copied to the backend's established static resource location by the existing build workflow.
- No Excel export header is added for plan-date change count/history.

- [ ] **Step 1: Run focused backend regression**

Run:

```bash
mvn -Dtest=ReplayTransactionPersonDaoTest,ReplayIssueDaoTest,ReplayIssuePlanDateServiceTest,ReplayIssueControllerTest test
```

Expected: all focused tests pass.

- [ ] **Step 2: Run full frontend regression**

Run in `/Users/java/axon-link-frontend`:

```bash
npm test -- --run
```

Expected: all frontend tests pass.

- [ ] **Step 3: Build frontend into backend**

Run the repository's existing production build command in `/Users/java/axon-link-frontend` and verify the generated backend static files contain the new plan-date history UI and no unrelated artifact deletion.

- [ ] **Step 4: Check patch hygiene**

Run in both repositories:

```bash
git diff --check
```

Expected: no whitespace errors. Review `git status --short` and ensure only task-related paths are newly changed by this implementation; preserve all pre-existing dirty files.

- [ ] **Step 5: Perform browser Mock acceptance**

Open `/#replay-issues` and verify:

1. YAML group permission and transaction-owner permission both enable editing.
2. A repair date disables editing regardless of permission.
3. Initial fill shows count `1`; changing date shows `2`; clearing shows `3`.
4. Hover shows one row per modification, only the resulting value, newest first; clear shows `计划时间：-`.
5. Same-value blur causes no request and no count change.
6. Zero history has no badge or tooltip.

- [ ] **Step 6: Record actual evidence**

Append one `[IMPL]` entry to `/Users/java/obsidian/log.md` containing actual focused/full test counts, build result, browser behaviors checked, and any unrelated baseline failures. Do not claim a check passed unless its command output was observed.
