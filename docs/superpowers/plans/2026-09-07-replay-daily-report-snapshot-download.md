# Replay Daily Report Snapshot Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist one current Excel snapshot per replay batch so the UI shows “已生成” and subsequent clicks download the saved file, while relevant data changes automatically invalidate all snapshots.

**Architecture:** Add a result-database BLOB table keyed by `batch_no`, then make `ReplayIssueDailyReportService` a cache-aside service: read the snapshot first, otherwise reuse the existing repeatable-read calculation, persist the complete workbook, and return it. Batch listing is enriched with snapshot status; formal import and status/type edits delete all snapshots inside their existing result-database transaction. The frontend and Vite Mock keep the dialog open after first generation and switch between generate and download states.

**Tech Stack:** Java 17, Spring Boot 3.1, Spring JDBC, Flyway, Apache POI, JUnit 5, H2/MySQL-compatible SQL, Vue 3, Vitest, Vite Mock middleware.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “日报生成快照与直接下载（2026-09-07）”; companion data model and API documents in the same directory.

## Global Constraints

- Keep exactly one current snapshot per standard `RPT` or `DZ` batch; do not add report history, preview, async generation, scheduled generation, or a delete API.
- Keep `GET /api/ai/parallel-replay/issues/daily-report` backward-compatible: cache miss generates, stores, and downloads; cache hit directly downloads.
- Keep the existing read-only `REPEATABLE_READ` snapshot for a cache miss; calculation and POI writing remain outside that read transaction.
- Formal replay import invalidates every generated snapshot in the same database transaction as issue and daily-data writes.
- Manual edits invalidate snapshots only when normalized `issueStatus` or `issueType` actually changes.
- Do not change the eleven-Sheet import contract, workbook layout, summary formulas, or `RPT`/`DZ` previous-batch isolation.
- Preserve unrelated dirty and untracked files. Do not create a branch or commit.

---

### Task 1: Snapshot Schema and Persistence API

**Files:**
- Create: `src/main/resources/db/daoindex/V57__dii_replay_daily_report_snapshot.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportSnapshot.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyBatch.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataMigrationTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`

**Interfaces:**
- Produces: `ReplayDailyReportSnapshot(String batchNo, String fileName, String contentType, byte[] content, long fileSize, LocalDateTime generatedAt)`.
- Produces: `Optional<ReplayDailyReportSnapshot> ReplayDailyDataDao.findReportSnapshot(String batchNo)`.
- Produces: `void ReplayDailyDataDao.saveReportSnapshot(ReplayDailyReportSnapshot snapshot)` using MySQL-compatible upsert semantics.
- Produces: `int ReplayDailyDataDao.deleteAllReportSnapshots()`.
- Extends: `ReplayDailyBatch(..., String previousBatchNo, boolean generated, LocalDateTime generatedAt)` while preserving `canGenerate()` as `previousBatchNo != null`.

- [ ] **Step 1: Write failing migration and DAO tests**

Add tests proving the migration creates all six non-null columns, `batch_no` is unique, binary content round-trips unchanged, a second save replaces the first snapshot, and `deleteAllReportSnapshots()` removes every batch. Extend batch-list assertions so an existing snapshot returns `generated=true/generatedAt`, while a batch without a row returns `false/null`.

```java
ReplayDailyReportSnapshot saved = new ReplayDailyReportSnapshot(
        "RPT20260907-02", "RPT20260907-02日报.xlsx", XLSX,
        new byte[]{1, 2, 3}, 3L, LocalDateTime.of(2026, 9, 7, 10, 30));
dao.saveReportSnapshot(saved);
assertThat(dao.findReportSnapshot(saved.batchNo())).get().usingRecursiveComparison().isEqualTo(saved);
```

- [ ] **Step 2: Run the focused tests and verify the schema/API failures**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyDataMigrationTest,ReplayDailyDataDaoTest test`

Expected: FAIL because V57, the snapshot DTO, DAO methods, and batch status fields do not exist.

- [ ] **Step 3: Implement the migration, DTO, and DAO methods**

Create the table with `batch_no VARCHAR(128) PRIMARY KEY`, `file_name VARCHAR(255) NOT NULL`, `content_type VARCHAR(128) NOT NULL`, `file_content LONGBLOB NOT NULL`, `file_size BIGINT NOT NULL`, and `generated_at DATETIME NOT NULL`. Clone the byte array in the record constructor and accessor. In `findBatchesRecentFirst()`, left join or separately map snapshot `generated_at`, then construct the six-field `ReplayDailyBatch` without changing family sorting.

- [ ] **Step 4: Run focused persistence tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyDataMigrationTest,ReplayDailyDataDaoTest test`

Expected: PASS with zero failures and errors.

- [ ] **Step 5: Review checkpoint without committing**

Inspect `git diff --` for the six Task 1 files and confirm no unrelated migration or DAO behavior changed.

### Task 2: Cache-Aside Report Generation

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Consumes: Task 1 snapshot DAO methods and `ReplayDailyReportSnapshot`.
- Keeps: `byte[] ReplayIssueDailyReportService.generate(String batchNo)` so the controller remains source-compatible.
- Produces behavior: snapshot hit returns stored bytes without invoking calculation or workbook writing; miss generates, saves, and returns bytes.

- [ ] **Step 1: Write failing cache hit, cache miss, and failed-generation tests**

Add one test where `findReportSnapshot(batchNo)` returns bytes and verify `findSummaries`, `calculator.calculate`, `workbookWriter.write`, and `saveReportSnapshot` are never called. Add one miss test that verifies generated bytes, filename, MIME type, byte length, and injected-clock `generatedAt` are persisted. Add one writer-failure test that verifies no snapshot save occurs.

```java
when(dailyDataDao.findReportSnapshot(BATCH)).thenReturn(Optional.of(snapshot));
assertThat(service.generate(BATCH)).containsExactly(snapshot.content());
verifyNoInteractions(calculator, workbookWriter);
```

- [ ] **Step 2: Run the service test and verify failures**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDailyReportServiceTest test`

Expected: FAIL because `generate` does not read or save a snapshot.

- [ ] **Step 3: Implement cache-aside generation**

Inject `Clock` through a package-private test constructor while production uses `Clock.systemDefaultZone()`. Validate the batch number first, return a cloned cached byte array when present, otherwise run the existing `loadSnapshot` transaction and calculations unchanged, construct `<batchNo>日报.xlsx`, save the completed bytes, and return them. Do not hold the read-only transaction while writing the BLOB.

- [ ] **Step 4: Run the service test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDailyReportServiceTest test`

Expected: PASS with cache-hit collaborators untouched and cache-miss persistence verified.

- [ ] **Step 5: Review checkpoint without committing**

Confirm `loadSnapshot`, prior-batch selection, formulas, and workbook writer calls are unchanged apart from the cache-aside wrapper.

### Task 3: Transactional Snapshot Invalidation

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: `int ReplayDailyDataDao.deleteAllReportSnapshots()`.
- Import behavior: call invalidation after `replaceBatch` and before the surrounding `ReplayIssueDao.inTransaction` callback returns.
- Edit behavior: compare normalized `before.issueStatus()/issueType()` with `after`; invalidate after current-row/history writes only when either differs.

- [ ] **Step 1: Write failing import invalidation tests**

Verify a successful eleven-Sheet import calls `replaceBatch` and then `deleteAllReportSnapshots`. Force invalidation to throw and prove the import transaction propagates failure rather than returning success. Verify the legacy test path where `dailyData == null` does not call the daily DAO.

- [ ] **Step 2: Write failing edit invalidation tests**

Add cases for status-only change, type-only change, text-only change, and no-op save. Status/type changes must call deletion once inside `dao.inTransaction`; text-only and no-op saves must not delete snapshots.

```java
verify(dailyDataDao).deleteAllReportSnapshots(); // status or type changed
verify(dailyDataDao, never()).deleteAllReportSnapshots(); // only remark changed
```

- [ ] **Step 3: Run focused import/edit tests and verify failures**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueImportServiceTest,ReplayIssueEditServiceTest test`

Expected: FAIL because neither service invalidates snapshots.

- [ ] **Step 4: Implement transactional invalidation with constructor compatibility**

Inject the shared `ReplayDailyDataDao` into production constructors. Preserve package-private test constructors by delegating to a full constructor and permitting a null daily DAO only in tests that do not exercise invalidation. Place deletion inside the existing transaction callback. Compare the fully normalized `after` row, not raw request strings, so forced `延后修复 -> 迁移问题` invalidates correctly and unchanged values do not.

- [ ] **Step 5: Run focused import/edit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueImportServiceTest,ReplayIssueEditServiceTest test`

Expected: PASS, including rollback/error propagation cases.

- [ ] **Step 6: Review checkpoint without committing**

Confirm collaborator/analysis/solution/remark-only edits retain existing snapshots and all formal imports invalidate them.

### Task 4: Controller Contract and Batch JSON

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify only if required by compilation: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyBatch.java`

**Interfaces:**
- Consumes: enriched `ReplayDailyBatch` from Task 1.
- Keeps: the existing daily-report endpoint, content type, filename, and Chinese error mapping.
- Produces JSON: `generated: boolean` and `generatedAt: string|null` on every batch.

- [ ] **Step 1: Write failing controller serialization tests**

Assert an ungenerated batch serializes `generated=false` and `generatedAt=null`; assert a generated batch includes the exact timestamp. Retain existing download and 400/404/409/500 assertions.

- [ ] **Step 2: Run the focused controller test and verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueControllerTest test`

Expected: FAIL until all constructor fixtures and JSON assertions use the enriched DTO.

- [ ] **Step 3: Apply the minimal controller/fixture changes**

Prefer no controller production change: the service still returns bytes and Jackson serializes the DTO fields. Update all test fixtures to pass `generated/generatedAt`; add `@JsonProperty` only if record serialization does not produce the specified property names.

- [ ] **Step 4: Run the focused controller test**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueControllerTest test`

Expected: PASS with backward-compatible download headers.

- [ ] **Step 5: Review checkpoint without committing**

Confirm no new public endpoint exists and prior Chinese errors remain unchanged.

### Task 5: Frontend Generated/Download State

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Test for regression only: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

**Interfaces:**
- Consumes: `generated` and `generatedAt` from the existing batch-list API.
- Keeps: `downloadReplayDailyReport(batchNo)` unchanged.
- Produces: selected entry computed from `dailyReportSelectedBatch`; option suffix `（已生成）`; primary labels `生成 Excel`/`生成中…` or `下载 Excel`/`下载中…`.

- [ ] **Step 1: Replace the obsolete close-on-success test with failing state tests**

Test an initially generated batch displays `已生成` and `下载 Excel`. For an ungenerated batch, mock the first list response as ungenerated and the refresh response as generated; after download resolves, assert the modal remains open, success text is visible, the option shows `已生成`, and the button says `下载 Excel`. Retain the generation-error open-modal test.

- [ ] **Step 2: Run focused frontend tests and verify failure**

Run: `cd /Users/java/axon-link-frontend && npx vitest run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js`

Expected: FAIL because the current modal always says “生成 Excel” and closes on success.

- [ ] **Step 3: Implement the computed state and refresh flow**

Add `dailyReportSelectedEntry = computed(() => dailyReportBatches.value.find(...))` and `dailyReportSuccess`. Extract a `loadDailyReportBatches({ preserveSelection })` helper. On successful download, keep the modal open, reload batches while preserving the selected batch, and set `已生成，可直接下载`; on refresh failure keep the download successful but show a state-refresh error. Update the modal description to explain first generation and later direct download.

- [ ] **Step 4: Run focused frontend tests**

Run: `cd /Users/java/axon-link-frontend && npx vitest run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js`

Expected: PASS with both generated and ungenerated states.

- [ ] **Step 5: Review checkpoint without committing**

Confirm disabled rules still reject batches without a previous batch and prevent double-clicks during generation/download.

### Task 6: Stateful Vite Mock and Local Visual Acceptance

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Produces: in-memory `Map<string, string>` of generated batch timestamps.
- Produces Mock GET `/api/ai/parallel-replay/issues/daily-report/batches` with at least one non-generatable first batch, one ungenerated batch, and one generated batch.
- Produces Mock GET `/api/ai/parallel-replay/issues/daily-report?batchNo=...` that marks a valid batch generated and returns a small xlsx-compatible binary response with attachment headers.
- Edit Mock invalidates only when status/type changes; any successful formal-import Mock path clears all generated entries.

- [ ] **Step 1: Write failing Mock route/state tests**

Call the exported Mock helpers or middleware harness to assert the initial mixed states, first download transition to generated, repeat download retention, and status/type edit invalidation. Keep the unrelated date-sensitive expectation outside the focused command.

- [ ] **Step 2: Run the focused Mock tests and verify failure**

Run: `cd /Users/java/axon-link-frontend && npx vitest run mock/daoIndexMockServer.spec.js -t "daily report snapshot"`

Expected: FAIL because no daily-report Mock routes exist.

- [ ] **Step 3: Implement Mock state and routes before the generic issue PATCH/list handlers**

Match exact `path` values before the generic `/issues` branch returns list data. Set xlsx content type and `Content-Disposition`; return JSON errors for malformed or missing/first batches. Clear the generated map only when the saved issue's normalized status/type changes.

- [ ] **Step 4: Run focused Mock and frontend tests**

Run: `cd /Users/java/axon-link-frontend && npx vitest run mock/daoIndexMockServer.spec.js -t "daily report snapshot" && npx vitest run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js`

Expected: PASS.

- [ ] **Step 5: Open the local Mock and visually verify both states**

Run: `cd /Users/java/axon-link-frontend && npm run dev -- --host 127.0.0.1 --port 5176`

Open `http://127.0.0.1:5176/#replay-issues`, open 日报, verify one option shows `已生成`, select an ungenerated batch, click `生成 Excel`, confirm the modal remains open and switches to `下载 Excel`, then click again and confirm a direct download starts.

- [ ] **Step 6: Review checkpoint without committing**

Confirm Mock state is in-memory only and production API code does not depend on Mock-specific fields.

### Task 7: Full Verification and Packaging

**Files:**
- Rebuild output: `src/main/resources/static/**`
- Verify artifact: `target/axon-link-server-1.0.0.jar`

**Interfaces:**
- Consumes all prior tasks.
- Produces a backend JAR containing the rebuilt frontend with generated/download wording.

- [ ] **Step 1: Run backend focused replay tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest='com.axonlink.ai.replay.**' test`

Expected: PASS with zero failures and errors.

- [ ] **Step 2: Run the full backend suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q test`

Expected: PASS; report the exact test and skipped counts from Surefire XML.

- [ ] **Step 3: Run frontend focused and full suites**

Run: `cd /Users/java/axon-link-frontend && npx vitest run src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js mock/daoIndexMockServer.spec.js -t "daily report snapshot|daily report|database-driven"`

Run: `cd /Users/java/axon-link-frontend && npm test`

Expected: focused tests PASS. For the full suite, distinguish feature failures from the existing date-sensitive `mock/daoIndexMockServer.spec.js:100` expectation if it remains unrelated.

- [ ] **Step 4: Build frontend into backend and package with Java 17**

Run: `cd /Users/java/axon-link-frontend && npm run build`

Run: `cd /Users/java/axon-link-server && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -DskipTests package`

Expected: Vite succeeds, `target/axon-link-server-1.0.0.jar` exists, and JAR static assets contain `已生成` and `下载 Excel`.

- [ ] **Step 5: Final diff and requirement review**

Review only files listed in this plan. Confirm cache hit avoids calculation, cache miss persists complete bytes, invalidation is transactional, the UI stays open after first generation, Mock demonstrates both states, and no branch/commit or unrelated cleanup occurred.
