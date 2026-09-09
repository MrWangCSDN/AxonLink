# Replay Daily Report Permanent Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep every successfully generated replay daily report and its mail status permanently available until an explicit future deletion feature is introduced.

**Architecture:** Treat `dii_replay_daily_report_snapshot` as an immutable batch artifact rather than an invalidatable cache. Remove automatic deletion from import and issue-edit flows, retain existing batch-keyed upsert for generation concurrency, and turn the undeployed destructive V59 script into a no-op.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, H2/MySQL-compatible SQL.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Do not change Excel parsing, daily-data replacement, issue merge, or replay issue state transitions.
- Existing generated Excel bytes and mail status must survive later imports and issue edits.
- Do not add report expiration, scheduled regeneration, or a new regeneration API.
- Do not commit or reset the user's existing working tree changes.

---

### Task 1: Preserve Snapshots Across Imports and Edits

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`

**Interfaces:**
- Consumes: existing `ReplayDailyDataDao.saveReportSnapshot(...)` records.
- Produces: imports and edits that leave all existing snapshot rows unchanged.

- [x] **Step 1: Change tests to require snapshot preservation**

Rename import and edit test cases so they assert existing report snapshots remain present after a formal import, daily-only import, status change, and issue-type change.

- [x] **Step 2: Run focused tests and verify failure**

Run: `mvn -Dtest=ReplayIssueImportServiceTest,ReplayIssueEditServiceTest test`

Expected: FAIL because production code still invokes `deleteAllReportSnapshots()`.

- [x] **Step 3: Remove automatic snapshot deletion**

Delete the import-time and edit-time calls to `ReplayDailyDataDao.deleteAllReportSnapshots()`. Remove edit-service invalidation-only dependencies and helper methods while preserving all edit validation and history behavior.

- [x] **Step 4: Re-run focused tests**

Run: `mvn -Dtest=ReplayIssueImportServiceTest,ReplayIssueEditServiceTest test`

Expected: PASS.

### Task 2: Neutralize Destructive Upgrade SQL

**Files:**
- Modify: `src/main/resources/db/daoindex/V59__invalidate_replay_daily_report_snapshot_for_issue_total.sql`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataMigrationTest.java`

**Interfaces:**
- Consumes: existing snapshot and mail-status rows.
- Produces: an idempotent no-op migration that preserves both rows.

- [x] **Step 1: Change the migration test to require preservation**

Seed one snapshot and one linked mail row, execute V59 twice, and assert both rows still exist.

- [x] **Step 2: Run the migration test and verify failure**

Run: `mvn -Dtest=ReplayDailyDataMigrationTest test`

Expected: FAIL because V59 currently deletes every snapshot and cascades mail deletion.

- [x] **Step 3: Replace V59 body with a safe no-op statement**

Keep the versioned resource path for deployment compatibility, remove `DELETE FROM dii_replay_daily_report_snapshot`, and use `SELECT 1` because the SQL test/deployment executor rejects comment-only resources.

- [x] **Step 4: Re-run focused and full verification**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -Dtest=ReplayDailyDataMigrationTest,ReplayIssueImportServiceTest,ReplayIssueEditServiceTest test
mvn test
mvn -DskipTests package
```

Expected: focused tests, full backend tests, and package all succeed.
