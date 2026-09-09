# Replay Daily Report Issue Total Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate every report domain's issue total from unique issues in the occurrence batch and permanently distinguish never-fixed new issues from fixed-then-new issues.

**Architecture:** Keep imported `issue_total` as trace data, but make `ReplayDailyReportCalculator` derive report totals from `ReplayDailyIssueStatisticRow` grouped by normalized domain and sandbox. Change the DAO history flag from report-batch-local to issue-history-wide, then invalidate existing generated snapshots once so downloads cannot reuse the old calculation.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, H2/MySQL-compatible migration SQL, Apache POI report pipeline.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Do not change Excel import persistence or replay issue state transitions.
- Count one issue per unique `issue_id` within its occurrence batch and `(group_name, is_sandbox)` domain.
- A current `新建` issue is `未分析` only when it has never had an `已修复问题重新新建` history event.
- Keep imported `dii_replay_daily_summary.issue_total` unchanged for auditability.
- Do not commit or reset the user's existing working tree changes.

---

### Task 1: Recalculate Domain Issue Totals

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculator.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculatorTest.java`

**Interfaces:**
- Consumes: `List<ReplayDailyIssueStatisticRow>` already loaded for each occurrence batch.
- Produces: `ReplayDailySummaryCalculatedRow.issueTotal()` based on unique issue IDs rather than `ReplayDailySummaryRow.issueTotal()`.

- [x] **Step 1: Write failing tests for recalculated totals**

Add cases where the imported summary says `168` but the domain issue projection contains two unique IDs, and where a duplicate row with the same ID must still count once. Assert the domain total, report total, investigation-progress denominator, and previous-resolution denominator all use the unique count.

- [x] **Step 2: Run the focused calculator tests**

Run: `mvn -Dtest=ReplayDailyReportCalculatorTest test`

Expected: FAIL because current rows still expose imported `summary.issueTotal()`.

- [x] **Step 3: Implement unique counting in the calculator**

Index each display domain with a `LinkedHashMap<Long, ReplayDailyIssueStatisticRow>` so duplicate issue IDs retain one row. Derive `issueTotal` from the resulting list size and pass it to row statistics, unresolved statistics, calculated rows, and total-row aggregation.

- [x] **Step 4: Re-run focused calculator tests**

Run: `mvn -Dtest=ReplayDailyReportCalculatorTest test`

Expected: PASS.

### Task 2: Make Fixed-Then-New History Permanent

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyIssueStatisticRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculator.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculatorTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Replace: `findDailyReportIssueStatistics(String occurrenceBatchNo, String reportBatchNo)`.
- With: `findDailyReportIssueStatistics(String occurrenceBatchNo)`.
- Rename: `reopenedAfterFixedInReportBatch()` to `reopenedAfterFixed()`.

- [x] **Step 1: Write failing DAO and calculator tests**

Insert an `已修复问题重新新建` history event from an earlier batch, query a later occurrence batch, and assert `reopenedAfterFixed()` remains true. Assert a new issue without that history is unanalysed while the historical reopened issue is analysed and not fully fixed.

- [x] **Step 2: Run focused history tests**

Run: `mvn -Dtest=ReplayIssueDaoTest,ReplayDailyReportCalculatorTest,ReplayIssueDailyReportServiceTest test`

Expected: FAIL because the current SQL restricts the history event to `reportBatchNo`.

- [x] **Step 3: Implement history-wide detection**

Remove the `h.occurrence_batch_name=?` predicate from the `EXISTS` query, simplify the DAO method to one batch argument, rename the DTO boolean, and update service/calculator callers.

- [x] **Step 4: Re-run focused history tests**

Run: `mvn -Dtest=ReplayIssueDaoTest,ReplayDailyReportCalculatorTest,ReplayIssueDailyReportServiceTest test`

Expected: PASS.

### Task 3: Invalidate Old Generated Reports and Verify Regression Safety

**Files:**
- Create: `src/main/resources/db/daoindex/V59__invalidate_replay_daily_report_snapshot_for_issue_total.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataMigrationTest.java`

**Interfaces:**
- Consumes: existing `dii_replay_daily_report_snapshot` rows and cascading mail-status foreign key.
- Produces: no stale generated report snapshot after applying V59; no schema changes.

- [x] **Step 1: Write a failing migration test**

Create snapshot and mail-status rows, execute V59, then assert both tables contain zero rows. Execute V59 again and assert it remains successful.

- [x] **Step 2: Add the one-time invalidation SQL**

Use exactly:

```sql
DELETE FROM dii_replay_daily_report_snapshot;
```

The existing `ON DELETE CASCADE` removes linked mail state.

- [x] **Step 3: Run focused report tests**

Run: `mvn -Dtest=ReplayDailyReportCalculatorTest,ReplayIssueDaoTest,ReplayIssueDailyReportServiceTest,ReplayDailyReportWorkbookWriterTest,ReplayDailyDataMigrationTest test`

Expected: PASS.

- [x] **Step 4: Run full backend verification**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn test
mvn -DskipTests package
```

Expected: all tests pass and the application JAR is produced successfully.

- [x] **Step 5: Record implementation evidence**

Append one `[IMPL]` entry to `/Users/java/obsidian/log.md` with focused/full test counts, package result, and confirmation that import/state-machine files were not changed for this task.
