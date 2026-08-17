# Replay Issue Hover Summaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two hover-only, real-time summary tables for group issue counts and per-group developer-combination rankings, with copy-to-Excel support.

**Architecture:** Add two focused aggregate projections to `ReplayIssueDao` and expose them through independent read-only controller endpoints. The Vue page adds two summary entry cards that fetch only on pointer/focus entry, render a headed table while active, and copy the visible table as TSV without a backend export endpoint.

**Tech Stack:** Java 17, Spring MVC, JdbcTemplate, H2/MySQL SQL, Vue 3, Vitest, browser Clipboard API.

## Global Constraints

- Both aggregates exclude rows whose `issue_status` is `已修复`.
- Status columns are exactly `打开`, `延后修复`, `重新打开`, and `修复待验证`; legacy `分析中` contributes only to `totalCount`.
- Grouping uses normalized `dii_replay_issue.group_name`.
- Person ranking groups by the complete `dii_replay_transaction_person.developer` string; do not split multiple people separated by `、`.
- Missing or blank developer values display as `未匹配负责人`.
- Do not request either detail during initial page load. Each new pointer/focus entry triggers a fresh request unless that same request is still running.
- The detail table exists only while its entry is hovered or keyboard-focused; leaving hides it.
- Copy includes the visible column headers and every returned row as tab-separated text suitable for direct paste into Excel.
- Preserve unrelated dirty-worktree changes. Do not create a Git commit unless the user explicitly requests one.

---

### Task 1: Aggregate Query Projections

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueGroupSummary.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssuePersonRanking.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `List<ReplayIssueGroupSummary> groupIssueSummaries()`.
- Produces: `List<ReplayIssuePersonRanking> personIssueRankings()`.
- `ReplayIssuePersonRanking.rank` restarts at 1 for each `groupName`.

- [ ] **Step 1: Add failing DAO tests for status counts and fixed exclusion**

Seed multiple groups containing all six readable statuses and assert a group row shaped like:

```java
assertEquals(new ReplayIssueGroupSummary("贷款组", 2, 1, 1, 1, 6), summaries.get(0));
```

The total includes one legacy `分析中` row and excludes one `已修复` row.

- [ ] **Step 2: Add failing DAO tests for developer-combination ranking**

Seed transaction-person rows for a combination such as `张三(c-zhangs3)、李四(c-lisi)`, a single developer, and an unmatched transaction. Assert that the combination remains one ranking row, blanks become `未匹配负责人`, sorting is by group then count descending then name, and rank restarts per group.

- [ ] **Step 3: Run DAO tests and verify the new cases fail**

Run:

```bash
mvn test -q -Dtest=ReplayIssueDaoTest
```

Expected: compilation or assertion failures because the DTOs and DAO methods do not exist.

- [ ] **Step 4: Add immutable DTO records**

```java
public record ReplayIssueGroupSummary(
        String groupName,
        long openCount,
        long deferredCount,
        long reopenedCount,
        long pendingVerificationCount,
        long totalCount) {}
```

```java
public record ReplayIssuePersonRanking(
        int rank,
        String groupName,
        String developer,
        long openCount,
        long deferredCount,
        long reopenedCount,
        long pendingVerificationCount,
        long totalCount) {}
```

- [ ] **Step 5: Implement parameter-free aggregate queries**

Use parameterized/static SQL with conditional sums and `WHERE i.issue_status <> '已修复'`. The person query uses:

```sql
LEFT JOIN dii_replay_transaction_person p
  ON i.transaction_code = p.old_transaction_code
GROUP BY i.group_name, COALESCE(NULLIF(TRIM(p.developer), ''), '未匹配负责人')
ORDER BY i.group_name, total_count DESC, developer
```

Assign `rank` in Java while iterating the sorted result so the implementation remains compatible with the deployed MySQL version and H2 tests.

- [ ] **Step 6: Run DAO tests and verify they pass**

Run:

```bash
mvn test -q -Dtest=ReplayIssueDaoTest
```

Expected: all `ReplayIssueDaoTest` cases pass.

---

### Task 2: Read-Only Summary Endpoints

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `GET /api/ai/parallel-replay/issues/stats/groups` returning `R<List<ReplayIssueGroupSummary>>`.
- Produces: `GET /api/ai/parallel-replay/issues/stats/person-ranking` returning `R<List<ReplayIssuePersonRanking>>`.

- [ ] **Step 1: Add failing controller response tests**

Seed issues and transaction-person data, then assert exact response fields:

```java
mvc.perform(get("/api/ai/parallel-replay/issues/stats/groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].groupName").value("贷款组"))
        .andExpect(jsonPath("$.data[0].totalCount").value(3));
```

```java
mvc.perform(get("/api/ai/parallel-replay/issues/stats/person-ranking"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].rank").value(1))
        .andExpect(jsonPath("$.data[0].developer").value("张三(c-zhangs3)、李四(c-lisi)"));
```

- [ ] **Step 2: Run focused controller tests and verify 404 failures**

Run:

```bash
mvn test -q -Dtest=ReplayIssueControllerTest
```

Expected: new endpoint tests fail because mappings are absent.

- [ ] **Step 3: Add controller methods before the `/{id}` mappings**

```java
@GetMapping("/stats/groups")
public R<List<ReplayIssueGroupSummary>> groupSummaries() {
    return R.ok(dao.groupIssueSummaries());
}

@GetMapping("/stats/person-ranking")
public R<List<ReplayIssuePersonRanking>> personRankings() {
    return R.ok(dao.personIssueRankings());
}
```

Keep the existing generic exception handler so database failures return the established HTTP 500 envelope.

- [ ] **Step 4: Run controller and DAO tests**

Run:

```bash
mvn test -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest
```

Expected: all tests pass.

---

### Task 3: Lazy Hover Tables And TSV Copy

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces: `getReplayIssueGroupSummaries()`.
- Produces: `getReplayIssuePersonRankings()`.
- Adds entry test IDs `group-summary-entry`, `person-ranking-entry` and copy IDs `copy-group-summary`, `copy-person-ranking`.

- [ ] **Step 1: Add failing API URL tests**

Assert the two functions request:

```text
/api/ai/parallel-replay/issues/stats/groups
/api/ai/parallel-replay/issues/stats/person-ranking
```

- [ ] **Step 2: Add failing component tests for no initial fetch and hover fetch**

After mounting and flushing initial requests, assert both detail API mocks have zero calls. Trigger `mouseenter` on one entry and assert only its API is called and its headed table appears.

- [ ] **Step 3: Add failing component tests for refresh, hide and in-flight deduplication**

Assert `mouseleave` hides the table, a second `mouseenter` causes a second request, and two entry events while the first promise remains pending cause only one request.

- [ ] **Step 4: Add failing copy tests**

Mock `navigator.clipboard.writeText`, open each table, click its copy button, and assert exact TSV including headers. The person table begins with:

```text
排名\t分组\t负责人\t打开\t延后修复\t重新打开\t修复待验证\t总数
```

- [ ] **Step 5: Run frontend tests and verify the new cases fail**

Run:

```bash
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: failures for missing API functions and summary entries.

- [ ] **Step 6: Implement API functions and isolated hover state**

Add the two request functions. In the component, keep independent `{ open, loading, error, rows }` state per detail and use `mouseenter`/`focusin` to fetch. Return early when that detail is already loading. Use `mouseleave`/`focusout` to close without clearing rows; the next entry still performs a fresh request.

- [ ] **Step 7: Render two compact entries and headed tables**

Append the entries after the existing five lifecycle cards. Render the group columns:

```text
分组 | 打开 | 延后修复 | 重新打开 | 修复待验证 | 总数
```

Render the person columns:

```text
排名 | 分组 | 负责人 | 打开 | 延后修复 | 重新打开 | 修复待验证 | 总数
```

Keep tables in a viewport-constrained floating layer so they do not resize the summary row or overlap incoherently on mobile.

- [ ] **Step 8: Implement shared TSV copy helper**

Build rows from explicit header/value definitions, join cells with `\t`, join rows with `\n`, and write through the existing clipboard-compatible helper pattern. Show `表格已复制` feedback; on clipboard failure show `复制失败` without closing the table.

- [ ] **Step 9: Run focused frontend tests and verify they pass**

Run:

```bash
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: all focused tests pass.

---

### Task 4: Regression, Build And Delivery Verification

**Files:**
- Regenerate: `src/main/resources/static/**` through the frontend production build.
- Verify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Verify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`
- Verify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

**Interfaces:**
- Existing `/stats`, list, import, export, editing and round tracking behavior remain unchanged.

- [ ] **Step 1: Run replay backend regression tests**

```bash
mvn test -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest,ReplayIssueMergeServiceTest,ReplayIssueImportServiceTest,ReplayIssueEditServiceTest,ReplayIssueFullRefreshServiceTest
```

- [ ] **Step 2: Run the complete frontend test suite**

```bash
npm test -- --run
```

- [ ] **Step 3: Build frontend into backend static resources**

From `/Users/java/axon-link-frontend`:

```bash
npm run build
```

- [ ] **Step 4: Package backend and verify the new frontend chunk is included**

```bash
mvn package -q -DskipTests
jar tf target/axon-link-server-1.0.0.jar | rg 'BOOT-INF/classes/static/'
```

- [ ] **Step 5: Run formatting and design consistency checks**

```bash
git diff --check
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/obsidian diff --check
```

Confirm the final implementation still satisfies hover-only loading, fresh query on re-entry, complete developer-combination grouping, fixed exclusion, headed tables, and TSV copy.
