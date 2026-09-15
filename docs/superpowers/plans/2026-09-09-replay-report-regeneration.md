# Replay Report Regeneration Implementation Plan

**Status:** Completed and verified on 2026-09-09.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow weekly reports to select any batch with imported daily-report data, and allow generated daily/weekly Excel snapshots to be explicitly regenerated with an operation token while atomically resetting mail status.

**Architecture:** Keep GitHub-download endpoints cache-first and add explicit POST regeneration endpoints. Build workbook bytes from a repeatable-read snapshot before opening a short write transaction that replaces only the existing report snapshot and deletes its latest mail-status row; failures preserve both previous artifacts. Weekly candidate eligibility comes from imported summary data rather than generated daily snapshot state.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, TransactionTemplate, JUnit 5, Mockito, H2/MySQL-compatible SQL, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`, `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Weekly candidates require imported daily summary data; generated daily Excel state is irrelevant.
- RPT and DZ families remain isolated, start must precede end, and an end batch remains unique across weekly reports.
- Existing GET download endpoints remain cache-first and backward compatible.
- Only explicit POST regeneration requires `X-DII-Trigger-Token`.
- Regeneration overwrites the single current snapshot and resets its latest mail projection to `UNSENT`; no history table or migration is added.
- Workbook generation failure or snapshot/mail write failure preserves the previous snapshot and mail status.
- Frontend and backend repositories are changed independently; do not stage existing unrelated untracked backend ZIP/test directories.

---

### Task 1: Weekly Candidate Eligibility Uses Imported Daily Data

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`

**Interfaces:**
- Produces: `ReplayDailyDataDao.findBatchesWithDataInFamilyOrder(): List<ReplayDailyBatch>`.
- Consumes: `ReplayDailyDataDao.findBatchesRecentFirst()` and existing `ReplayDailyBatch.family()/importedAt()/batchNo()`.
- Changes: `ReplayWeeklyReportService.options()` and range validation consume data-backed candidates.

- [x] **Step 1: Write failing DAO test**

Add a test that inserts two valid summary batches, saves a daily snapshot for only one, and expects both batches from `findBatchesWithDataInFamilyOrder()` in family/import order.

```java
@Test
void listsBatchesWithImportedDataRegardlessOfGeneratedSnapshot() {
    insertSummary("RPT20260901-01", LocalDateTime.parse("2026-09-01T10:00:00"));
    insertSummary("RPT20260908-01", LocalDateTime.parse("2026-09-08T10:00:00"));
    dao.saveReportSnapshot(snapshot("RPT20260908-01"));

    assertEquals(List.of("RPT20260901-01", "RPT20260908-01"),
            dao.findBatchesWithDataInFamilyOrder().stream().map(ReplayDailyBatch::batchNo).toList());
}
```

- [x] **Step 2: Run DAO test and verify RED**

Run:

```bash
./mvnw -Dtest=ReplayDailyDataDaoTest#listsBatchesWithImportedDataRegardlessOfGeneratedSnapshot test
```

Expected: compilation failure because `findBatchesWithDataInFamilyOrder()` does not exist.

- [x] **Step 3: Implement the DAO method**

```java
public List<ReplayDailyBatch> findBatchesWithDataInFamilyOrder() {
    return findBatchesRecentFirst().stream()
            .sorted(Comparator.comparing(ReplayDailyBatch::family)
                    .thenComparing(ReplayDailyBatch::importedAt)
                    .thenComparing(ReplayDailyBatch::batchNo))
            .toList();
}
```

Retain `findGeneratedBatchesInFamilyOrder()` only if another caller still uses it; otherwise delete it after `rg` confirms no references.

- [x] **Step 4: Write failing service tests**

Update weekly service tests so an ungenerated-but-imported start batch is returned by `options()` and accepted by `generate()`. Replace `DailyReportNotGeneratedException` expectations with `BatchDataNotFoundException` carrying `所选批次没有日报数据` when a requested batch is absent from the data-backed candidate list.

- [x] **Step 5: Run service tests and verify RED**

```bash
./mvnw -Dtest=ReplayWeeklyReportServiceTest test
```

Expected: failures showing the service still filters by generated snapshots and uses the old exception.

- [x] **Step 6: Implement service eligibility**

Change both `options()` and `validateRange(...)` call sites to use `findBatchesWithDataInFamilyOrder()`. Rename the obsolete exception to `DailyDataNotFoundException` with message `所选批次没有日报数据`, or map the missing candidate directly to the existing `BatchDataNotFoundException` with that message; use one type consistently in controller tests.

- [x] **Step 7: Run focused tests and verify GREEN**

```bash
./mvnw -Dtest=ReplayDailyDataDaoTest,ReplayWeeklyReportServiceTest test
```

Expected: all selected tests pass.

---

### Task 2: Atomic Daily Report Regeneration

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyReportMailDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueDailyReportService.regenerate(String batchNo): byte[]`.
- Produces: `ReplayDailyReportMailDao.delete(String batchNo): int`.
- Preserves: `ReplayIssueDailyReportService.generate(String batchNo)` cache-first behavior.

- [x] **Step 1: Write failing mail DAO delete test**

Persist a daily snapshot and `SENT` mail row, call `delete(batchNo)`, and assert the row is absent while the snapshot remains.

- [x] **Step 2: Run DAO test and verify RED**

```bash
./mvnw -Dtest=ReplayDailyReportMailDaoTest#deletesLatestStatusWithoutDeletingSnapshot test
```

Expected: compilation failure because `delete(String)` does not exist.

- [x] **Step 3: Implement mail-state deletion**

```java
public int delete(String batchNo) {
    return jdbc.update("DELETE FROM dii_replay_daily_report_mail WHERE batch_no=?", batchNo);
}
```

- [x] **Step 4: Write failing daily regeneration tests**

Cover all three behaviors:

```java
@Test
void regenerateRebuildsExistingSnapshotAndClearsMailStatus() { /* old bytes -> new bytes, mail absent */ }

@Test
void regenerateRejectsMissingSnapshot() { /* expect SnapshotNotFoundException */ }

@Test
void regenerateFailurePreservesOldSnapshotAndMailStatus() { /* writer or save failure leaves old state */ }
```

Use a real H2-backed `JdbcTemplate` for the atomicity assertion; Mockito-only tests cannot prove rollback.

- [x] **Step 5: Run service tests and verify RED**

```bash
./mvnw -Dtest=ReplayIssueDailyReportServiceTest test
```

Expected: compilation failure because `regenerate` and the regeneration exception do not exist.

- [x] **Step 6: Implement regeneration with a short write transaction**

Add `ReplayDailyReportMailDao` and a write `TransactionTemplate` to the service. Extract workbook construction into a private method used by first generation and regeneration. Implement:

```java
public byte[] regenerate(String batchNo) {
    validateBatchNo(batchNo);
    if (dailyDataDao.findReportSnapshot(batchNo).isEmpty()) {
        throw new SnapshotNotFoundException();
    }
    byte[] bytes = buildWorkbook(batchNo);
    writeTransaction.executeWithoutResult(status -> {
        dailyDataDao.saveReportSnapshot(snapshot(batchNo, bytes));
        dailyReportMailDao.delete(batchNo);
    });
    return bytes;
}
```

The old snapshot check occurs before expensive generation; the transaction performs only snapshot replacement and mail deletion. Keep `generate` cache-first and call the same `buildWorkbook` only on cache miss.

- [x] **Step 7: Run focused tests and verify GREEN**

```bash
./mvnw -Dtest=ReplayDailyReportMailDaoTest,ReplayIssueDailyReportServiceTest test
```

Expected: all selected tests pass, including rollback proof.

---

### Task 3: Atomic Weekly Report Regeneration

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportMailDaoTest.java`

**Interfaces:**
- Produces: `ReplayWeeklyReportService.regenerate(String startBatchNo, String endBatchNo): ReplayWeeklyReportSnapshot`.
- Produces: `ReplayWeeklyReportDao.replaceSnapshot(ReplayWeeklyReportSnapshot snapshot): int`.
- Produces: `ReplayWeeklyReportMailDao.delete(String startBatchNo, String endBatchNo): int`.
- Preserves: first generation uses insert and end-batch uniqueness.

- [x] **Step 1: Write failing DAO tests**

Assert `replaceSnapshot` updates bytes, size and `generated_at` only for the exact existing range and returns `0` for an absent range. Assert mail `delete(start,end)` removes only that range's latest status.

- [x] **Step 2: Run DAO tests and verify RED**

```bash
./mvnw -Dtest=ReplayWeeklyReportDaoTest,ReplayWeeklyReportMailDaoTest test
```

Expected: compilation failures for missing replacement and deletion methods.

- [x] **Step 3: Implement exact-range replacement methods**

```java
public int replaceSnapshot(ReplayWeeklyReportSnapshot snapshot) {
    return jdbc.update("""
            UPDATE dii_replay_weekly_report_snapshot
               SET file_name=?,content_type=?,file_content=?,file_size=?,generated_at=?
             WHERE start_batch_no=? AND end_batch_no=?
            """, snapshot.fileName(), snapshot.contentType(), snapshot.content(), snapshot.fileSize(),
            Timestamp.valueOf(snapshot.generatedAt()), snapshot.startBatchNo(), snapshot.endBatchNo());
}
```

Add the analogous exact-range mail delete.

- [x] **Step 4: Write failing weekly service tests**

Cover regeneration success, missing exact snapshot, current-data absence, and write rollback. Verify first generation still rejects a different start range that reuses an occupied end batch.

- [x] **Step 5: Run service tests and verify RED**

```bash
./mvnw -Dtest=ReplayWeeklyReportServiceTest test
```

Expected: failures until the explicit regeneration path exists.

- [x] **Step 6: Implement weekly regeneration**

Extract current snapshot calculation/workbook construction into a shared private method. `regenerate(start,end)` must validate standard batch numbers, require the exact weekly snapshot, validate current data-backed range, build bytes, then execute `replaceSnapshot` plus mail delete in one write transaction. If `replaceSnapshot` returns `0`, throw `SnapshotNotFoundException` and roll back.

- [x] **Step 7: Run focused tests and verify GREEN**

```bash
./mvnw -Dtest=ReplayWeeklyReportDaoTest,ReplayWeeklyReportMailDaoTest,ReplayWeeklyReportServiceTest test
```

Expected: all selected tests pass.

---

### Task 4: Regeneration HTTP Contracts and Token Enforcement

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `POST /api/ai/parallel-replay/issues/daily-report/regenerate?batchNo=...`.
- Produces: `POST /api/ai/parallel-replay/issues/weekly-report/regenerate?startBatchNo=...&endBatchNo=...`.
- Consumes: existing `dao-index-analysis.batch-trigger.token` and `X-DII-Trigger-Token`.

- [x] **Step 1: Write failing MVC tests**

Add tests for:

- valid token returns xlsx and expected filename;
- wrong token returns `401 口令错误` and never calls the service;
- missing daily/weekly snapshot returns `404`;
- weekly missing daily data returns `404 所选批次没有日报数据`;
- the existing GET weekly endpoint accepts imported-but-not-generated daily candidates.

- [x] **Step 2: Run controller tests and verify RED**

```bash
./mvnw -Dtest=ReplayIssueControllerTest test
```

Expected: 404/405 or unmet service verification because POST endpoints do not exist.

- [x] **Step 3: Implement controller endpoints**

Reuse one private token predicate to avoid a third copy of token comparison. Each endpoint returns the same xlsx response headers as its GET counterpart. Map malformed batch/range to 400, bad token to 401, missing snapshot/data to 404, occupied end batch to 409 only on first generation, and unexpected regeneration failure to the existing 500 report-generation message.

- [x] **Step 4: Run controller tests and verify GREEN**

```bash
./mvnw -Dtest=ReplayIssueControllerTest test
```

Expected: all controller tests pass.

- [x] **Step 5: Run backend replay-report regression suite**

```bash
./mvnw -Dtest='ReplayDailyDataDaoTest,ReplayDailyReportMailDaoTest,ReplayWeeklyReportDaoTest,ReplayWeeklyReportMailDaoTest,ReplayIssueDailyReportServiceTest,ReplayWeeklyReportServiceTest,ReplayIssueControllerTest' test
```

Expected: all selected tests pass.

---

### Task 5: Frontend API and Regeneration Interaction

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Produces: `regenerateReplayDailyReport(batchNo, token)` download helper.
- Produces: `regenerateReplayWeeklyReport(startBatchNo, endBatchNo, token)` download helper.
- Consumes: backend POST regeneration endpoints and existing weekly options response.

- [x] **Step 1: Write failing API tests**

Assert both helpers issue POST requests, include `X-DII-Trigger-Token`, preserve URL encoding, and use `<batchNo>日报.xlsx` / `<endBatchNo>周报.xlsx` fallback filenames.

- [x] **Step 2: Run API tests and verify RED**

```bash
npm test -- --run src/api/replayIssues.spec.js
```

Expected: import/export failure for the new API helpers.

- [x] **Step 3: Implement API helpers**

Use the existing `download` helper's request-options support. If it cannot send POST headers, minimally extend it without changing existing GET callers.

- [x] **Step 4: Write failing component tests**

Cover:

- weekly modal copy says “存在日报数据” and accepts `generated:false` candidates;
- generated daily selection shows `重新生成`;
- selected historical weekly report shows `重新生成`;
- regeneration modal requires token and closes only by Cancel, Confirm success, or X;
- daily and weekly success refresh generated time and show mail status `未发送`;
- API failure keeps the report modal open and displays the error.

- [x] **Step 5: Run component tests and verify RED**

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: missing buttons/modal/copy and unmet API calls.

- [x] **Step 6: Implement page interaction**

Update the weekly description and empty-state copy from “已生成日报” to “存在日报数据”. Add independent `重新生成` actions beside generated daily and selected weekly history results. Reuse a single confirmation modal state with report kind, batch range, token, loading and error fields; do not close on mask click. On success, download returned bytes, reload options/batches while preserving selection, and project mail state as `UNSENT`.

- [x] **Step 7: Run frontend focused tests and verify GREEN**

```bash
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: all selected tests pass.

---

### Task 6: Full Verification and Delivery Assets

**Files:**
- Modify generated backend static assets only through the established frontend production build/copy workflow.
- Modify plan checkboxes in `docs/superpowers/plans/2026-09-09-replay-report-regeneration.md` as tasks complete.

**Interfaces:**
- Produces: verified frontend production bundle embedded in backend resources.
- Produces: backend JAR and source ZIP using the repository's existing delivery commands.

- [x] **Step 1: Run full frontend tests**

```bash
cd /Users/java/axon-link-frontend && npm test -- --run
```

Expected: zero failures.

- [x] **Step 2: Build frontend for backend integration**

Run the repository's existing production build command used by the latest delivery workflow, then copy the generated assets into the backend static-resource directory. Verify the built bundle contains the new regeneration copy and endpoint strings.

- [x] **Step 3: Run full backend tests on Java 17**

```bash
cd /Users/java/axon-link-server && ./mvnw test
```

Expected: zero failures apart from explicitly configured skips.

- [x] **Step 4: Package backend and inspect artifact**

```bash
./mvnw -DskipTests package
```

Expected: package succeeds and the JAR contains the updated static index/assets and replay report classes.

- [x] **Step 5: Verify both repository diffs**

```bash
git -C /Users/java/axon-link-server diff --check
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/axon-link-server status --short
git -C /Users/java/axon-link-frontend status --short
```

Expected: no whitespace errors; only files named in this plan plus generated delivery assets are modified. Existing unrelated untracked backend test directories and ZIP files remain untouched and unstaged.
