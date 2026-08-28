# Replay Daily Report Test Import Retirement Implementation Plan

> **For agentic workers:** Execute inline in the current workspace. Do not commit, reset, clean, or alter unrelated user changes.

**Goal:** Remove the temporary daily-report upload entry point while preserving formal issue import, summary parsing, automatic report generation, report listing, and report download.

**Architecture:** The formal `/import` flow parses issue sheets and the `汇总信息` sheet in one request, then passes `ParsedSummary` directly to `ReplayIssueDailyReportService`. The legacy summary table is not dropped; production stops inserting new rows, and the existing migration/data remain untouched.

**Tech Stack:** Vue 3, Vitest, Spring Boot 3, MockMvc, JUnit 5, Maven, Java 17.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Keep `GET /daily-report/batches` and `GET /daily-report` unchanged.
- Keep automatic report generation in formal `POST /import` unchanged.
- Remove only temporary `POST /daily-report/import` and its front-end UI/API.
- Do not drop `dii_replay_issue_summary` or delete existing rows.
- Do not modify or clean unrelated dirty-worktree changes.

---

### Task 1: Retire the front-end test entry

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Test: existing replay page/API Vitest suites

- [x] Add a source-level regression test that fails while `open-daily-report-import` and `importReplayDailyReport` still exist.
- [x] Run the focused test and confirm the expected failure.
- [x] Remove the toolbar button, modal, reactive state, handlers, import, and API function.
- [x] Run the focused test and existing replay front-end tests.

### Task 2: Retire the back-end test endpoint and summary-table writes

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryImportIntegrationTest.java`

- [x] Change controller tests to expect `POST /daily-report/import` to be unavailable and run them red.
- [x] Change formal-import integration expectations to assert the legacy summary table receives no new rows while the report is still generated; run red.
- [x] Remove the controller endpoint and decouple summary parsing from `ReplayIssueSummaryDao` insertion.
- [x] Run controller and formal-import integration tests green.

### Task 3: Verify the retained production workflow

**Files:** No new production files.

- [x] Run replay summary parser, daily report service, import integration, controller, and import service tests with Java 17.
- [x] Run relevant Vitest suites and the front-end production build.
- [x] Copy the front-end build into back-end static resources only if the source project build succeeds and the existing delivery workflow requires it.
- [x] Run `mvn -DskipTests package` with Java 17 and inspect the final diff/status without touching unrelated files.
