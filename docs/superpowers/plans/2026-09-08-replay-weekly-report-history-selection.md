# Replay Weekly Report History Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the ending daily batch the unique weekly-report batch, expose every generated weekly report in a history selector, and bind range display, download, and email actions to the selected historical report.

**Architecture:** Keep the existing `(startBatchNo, endBatchNo)` request contract and composite keys so current download/mail APIs remain compatible. Add a database uniqueness constraint and service-level conflict check on `endBatchNo`; in the Vue modal, separate “history mode” from “new report mode” and derive all generated-result actions from the selected `ReplayWeeklyReportOption`.

**Tech Stack:** Java 17, Spring Boot, `JdbcTemplate`, Flyway-style SQL migrations, JUnit 5/Mockito/H2, Vue 3 Composition API, Vitest, Vue Test Utils.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` (section “页面交互” and “快照与邮件”), with matching data-model and API sections in the same directory.

## Global Constraints

- `endBatchNo` is the displayed weekly-report batch and is globally unique across weekly snapshots.
- Existing generated files remain permanent and are never overwritten or recalculated.
- Download and mail endpoints keep both `startBatchNo` and `endBatchNo` parameters.
- `RPT` and `DZ` remain isolated; start must be earlier than end and both daily reports must already be generated.
- The modal mask must not close either the weekly-report or mail dialog.
- Do not alter replay issue import, issue status transitions, daily-report generation, or daily-report snapshots.
- Do not commit or create a branch unless the user explicitly requests it.

---

### Task 1: Enforce One Weekly Report Per Ending Batch

**Files:**
- Create: `src/main/resources/db/daoindex/V61__unique_replay_weekly_report_end_batch.sql`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `ReplayWeeklyReportDao.findSnapshotByEndBatchNo(String): Optional<ReplayWeeklyReportSnapshot>`.
- Produces: `ReplayWeeklyReportService.EndBatchAlreadyGeneratedException` with message `结束批次周报已生成`.
- Preserves: `generate(String startBatchNo, String endBatchNo)` returns the exact cached pair or creates one new permanent snapshot.

- [ ] **Step 1: Write failing DAO and migration tests**

Add a test that saves `RPT20260901-01 -> RPT20260908-01`, then asserts `findSnapshotByEndBatchNo("RPT20260908-01")` returns it and a second insert with start `RPT20260903-01` fails because `end_batch_no` is unique. Load `V60` followed by `V61` in setup so the test exercises the real migration sequence.

- [ ] **Step 2: Run the DAO test and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -Dtest=ReplayWeeklyReportDaoTest test
```

Expected: compilation failure for missing `findSnapshotByEndBatchNo` or assertion failure because two different starts currently accept the same end.

- [ ] **Step 3: Add the database constraint and DAO lookup**

Create `V61__unique_replay_weekly_report_end_batch.sql`:

```sql
ALTER TABLE dii_replay_weekly_report_snapshot
    ADD CONSTRAINT uk_replay_weekly_report_end UNIQUE (end_batch_no);
```

Implement `findSnapshotByEndBatchNo` with `WHERE end_batch_no=?`. Keep `findSnapshot(start,end)` for exact cache, download, and mail behavior. Change `saveSnapshot` to a plain `INSERT`: the current `ON DUPLICATE KEY UPDATE` would otherwise let an ending-batch collision overwrite a permanent workbook. Replace the now-redundant non-unique end index only if the target database rejects duplicate indexing; otherwise leave it untouched for a minimal migration.

- [ ] **Step 4: Run the DAO test and verify GREEN**

Run the Task 1 DAO command again. Expected: PASS and only one record for each ending batch.

- [ ] **Step 5: Write failing service and controller conflict tests**

Service case: exact pair lookup is empty, `findSnapshotByEndBatchNo(end)` returns a snapshot with another start, and `generate` throws `EndBatchAlreadyGeneratedException` before reading daily candidates or report data. Controller case: map the exception to HTTP `409` and JSON message `结束批次周报已生成`.

- [ ] **Step 6: Run service/controller tests and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw \
  -Dtest=ReplayWeeklyReportServiceTest,ReplayIssueControllerTest test
```

Expected: missing exception/lookup behavior and current controller fallback response.

- [ ] **Step 7: Implement the conflict guard**

In `generate`, preserve this order:

```java
Optional<ReplayWeeklyReportSnapshot> exact = weeklyReportDao.findSnapshot(start, end);
if (exact.isPresent()) return exact.get();
if (weeklyReportDao.findSnapshotByEndBatchNo(end).isPresent()) {
    throw new EndBatchAlreadyGeneratedException();
}
```

Then perform existing range/data validation and generation. Catch the new exception in `downloadWeeklyReport` and return `HttpStatus.CONFLICT` without modifying any snapshot.

- [ ] **Step 8: Run Task 1 tests and verify GREEN**

Run the Step 6 command plus `ReplayWeeklyReportDaoTest`. Expected: all selected tests PASS.

---

### Task 2: Add Historical Weekly-Report Selection to the Modal

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `weeklyReports: ReplayWeeklyReportOption[]` from existing `GET /weekly-report/options`.
- Produces UI state: `weeklyReportMode: 'history' | 'create'` and `weeklyReportSelectedHistoryKey` using `endBatchNo` as the unique value.
- Preserves: existing API functions and mail editor fields.

- [ ] **Step 1: Write failing history-selector tests**

Return five generated entries from `getReplayWeeklyReportOptions`. Assert:

```js
expect(wrapper.get('[data-testid="weekly-report-history-batch"]')
  .findAll('option').map(option => option.attributes('value')))
  .toEqual(['RPT20260929-01', 'RPT20260922-01', 'RPT20260915-01', 'RPT20260908-01', 'RPT20260901-01', ''])
```

The empty value represents “生成新周报”. Selecting `RPT20260915-01` must set the displayed start/end to that entry, disable both daily-batch selects, render `当前周报批次 RPT20260915-01`, generation time and mail status, and expose download/email buttons.

- [ ] **Step 2: Write failing create-mode tests**

Select the empty history option and assert both daily selectors become enabled. Assert ending options exclude every `weeklyReports[].endBatchNo`; generating a new report refreshes options, selects the new ending batch in history mode, and shows the generated-result card.

- [ ] **Step 3: Run focused frontend tests and verify RED**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/ReplayIssuePage.spec.js
```

Expected: missing history selector/result card and occupied ending batches still visible in create mode.

- [ ] **Step 4: Implement explicit history and create modes**

Add a top selector with `data-testid="weekly-report-history-batch"`. Use `endBatchNo` as its value and include a final `生成新周报` option. On history selection, find the option by end batch and assign its `startBatchNo`/`endBatchNo`; render both range selects disabled. On create selection, clear result messages, enable range selects, and initialize the first valid range.

- [ ] **Step 5: Implement the generated-result card**

Render only in history mode:

```text
当前周报批次  <endBatchNo>
已生成 · 邮件：<status> · 生成于 <generatedAt>
```

Keep the existing secondary email button and primary download button in the footer. All attachment names and email dates continue to derive from the selected entry's `endBatchNo`.

- [ ] **Step 6: Filter occupied ending batches in create mode**

Create a computed set from `weeklyReportReports.map(entry => entry.endBatchNo)`. `weeklyReportEndOptions` must require same family, later order, and absence from this set. Start options must retain at least one valid unoccupied end; if none exists, show `没有可生成的新周报范围` and disable generation.

- [ ] **Step 7: Refresh into history mode after generation**

After `downloadReplayWeeklyReport` succeeds for a new report, call the existing options refresh with selection preservation, then set the history selection to `endBatchNo`. Do not close the modal. Exact historical download continues to call the existing API with the stored start and end.

- [ ] **Step 8: Run focused frontend tests and verify GREEN**

Run the Step 3 command. Expected: all `ReplayIssuePage` tests PASS.

---

### Task 3: Mock, Regression, and Delivery Verification

**Files:**
- Modify only if required: `/Users/java/axon-link-frontend/src/mocks/replayIssuesMock.js` or the existing replay mock module located by `rg -n "weeklyReports" src`.
- No production behavior changes beyond Tasks 1 and 2.

**Interfaces:**
- Consumes: existing mock `/weekly-report/options`, download, mail-config, and mail-send handlers.
- Produces: at least five visible historical weekly reports across `RPT` and `DZ`, with distinct generated/mail states.

- [ ] **Step 1: Add or adjust mock fixtures**

Provide at least five unique ending batches; include `UNSENT`, `SENT`, and `FAILED` mail states. Ensure no two mock entries share `endBatchNo` and that each history entry's start/end daily batches exist in `dailyBatches`.

- [ ] **Step 2: Run complete frontend tests and build**

```bash
cd /Users/java/axon-link-frontend
npm test
npm run build
```

Expected: all tests PASS and Vite build completes without errors.

- [ ] **Step 3: Run backend weekly-report regression tests**

```bash
cd /Users/java/axon-link-server
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw \
  -Dtest=ReplayWeeklyReportDaoTest,ReplayWeeklyReportMailDaoTest,ReplayWeeklyReportServiceTest,ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test
```

Expected: all selected tests PASS; existing exact-pair download and mail attachment tests remain green.

- [ ] **Step 4: Run the full backend suite**

```bash
cd /Users/java/axon-link-server
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw test
```

Expected: full suite PASS, allowing only previously documented skips.

- [ ] **Step 5: Perform browser verification**

Open the local mock page, verify five historical batches can each be selected, confirm range fields and generated metadata change together, then verify download and mail dialogs use the selected weekly batch filename. Capture one screenshot of the history selector and one of a selected historical report.

- [ ] **Step 6: Inspect the final diff**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; no unrelated user files are changed or removed.
