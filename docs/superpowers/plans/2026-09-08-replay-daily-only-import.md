# Replay Daily-Only Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow workbooks containing only the three required daily-report sheets to import daily data without changing replay issue data or status.

**Architecture:** Preserve sheet presence in `ReplayIssueExcelParser.ParsedWorkbook` so the import service can distinguish an absent eight-sheet set from present-but-empty issue sheets. The formal import transaction skips `ReplayIssueMergeService` only when all eight issue sheets are absent, while retaining current complete-snapshot behavior whenever any issue sheet exists.

**Tech Stack:** Java 17, Spring JDBC transactions, Apache POI, JUnit 5, H2, Maven.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- `汇总信息`, `接口比对明细`, and `回放交易覆盖情况` remain required and strictly validated.
- All eight issue sheets absent means daily-only import and must not write any issue-related table.
- Any issue sheet present retains complete-snapshot merge and auto-repair semantics, even when all present issue sheets contain zero rows.
- `QUERY` keeps `RPT`; `DZ` converts imported daily batch values from `RPT` to `DZ`.
- Daily replacement and report-snapshot invalidation remain atomic; any failure rolls back the import.
- Do not add database migrations or change the public import endpoint.

---

### Task 1: Preserve Issue Sheet Presence

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueExcelParser.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueExcelParserTest.java`

**Interfaces:**
- Produces: `ParsedWorkbook.hasIssueSheets(): boolean`.
- Preserves: four-argument `ParsedWorkbook` constructor for merge-service tests, defaulting to issue-snapshot semantics.

- [x] **Step 1: Write failing parser assertions**

Assert that a workbook with all target sheets removed returns `hasIssueSheets() == false`, while a workbook containing target sheets with zero data rows returns `hasIssueSheets() == true`.

- [x] **Step 2: Run the parser tests and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueExcelParserTest test`

Expected: compilation or assertion failure because `hasIssueSheets()` does not exist.

- [x] **Step 3: Add the sheet-presence component**

Track whether `workbook.getSheet(metadata.name())` returns non-null for any target sheet and pass the result into `ParsedWorkbook`. Add a four-argument compatibility constructor that delegates with `hasIssueSheets=true`.

- [x] **Step 4: Run parser tests and verify GREEN**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueExcelParserTest test`

Expected: all parser tests pass.

### Task 2: Isolate Daily-Only Imports

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`

**Interfaces:**
- Consumes: `ParsedWorkbook.hasIssueSheets()` from Task 1.
- Produces: an existing `ReplayIssueImportResult` with zero issue counters for daily-only imports.

- [x] **Step 1: Replace the old all-missing-sheet expectation with failing isolation tests**

Add a `QUERY` test that seeds an active issue, imports a three-sheet workbook, and asserts unchanged current issue content/status plus zero rows in import-round, history, occurrence-batch, and issue-round tables. Assert daily rows are saved and report snapshots are invalidated.

Add a `DZ` test using the same three-sheet workbook and assert data is stored under the converted `DZ...` batch, no `RPT...` daily batch is written, and issue-related tables remain unchanged.

Add or retain a test where at least one valid issue sheet exists but contains no rows, asserting the existing same-family automatic repair still runs.

- [x] **Step 2: Run service tests and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueImportServiceTest test`

Expected: the daily-only tests fail because the current service calls `mergeWithinTransaction` and auto-repairs the seeded issue.

- [x] **Step 3: Implement the minimal transaction branch**

Inside the existing formal-import transaction, call `mergeWithinTransaction` only when `parsed.hasIssueSheets()` is true. Otherwise create a zero-count `ReplayIssueImportResult` using `parsed.rowsBySheet()`, `importedAt`, and `coverageRound`, then execute the unchanged `replaceBatch` and `deleteAllReportSnapshots` calls.

- [x] **Step 4: Run service tests and verify GREEN**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueImportServiceTest test`

Expected: all service tests pass, including QUERY, DZ, rollback, and present-empty snapshot cases.

### Task 3: Regression Verification

**Files:**
- Verify only; no new production files.

**Interfaces:**
- Confirms parser, merge state machine, formal import, controller contract, and daily workbook parsing remain compatible.

- [x] **Step 1: Run focused replay import regression tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueExcelParserTest,ReplayIssueImportServiceTest,ReplayIssueMergeServiceTest,ReplayDailyWorkbookParserTest,ReplayIssueSummaryImportIntegrationTest,ReplayIssueControllerTest test`

Expected: zero failures and zero errors.

- [x] **Step 2: Check patch formatting**

Run: `git diff --check`

Expected: no output and exit code 0.

- [x] **Step 3: Build the backend package**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -DskipTests package`

Expected: exit code 0.
