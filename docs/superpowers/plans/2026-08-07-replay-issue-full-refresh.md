# Replay Issue First-Sheet Full Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change the temporary replay issue “全量更新” workflow so it imports only the workbook's first Sheet, updates existing rows by `issue_key`, inserts unmatched rows, and preserves all database rows absent from that Sheet.

**Architecture:** Keep the ordinary eight-Sheet `/import` path unchanged. The dedicated full-update parser reads only Sheet index 0 and preserves blank identities; the full-update service allocates collision-free `AUTO-*` values inside the result-database transaction, locks each nonblank/generated `issue_key`, then updates or inserts the current row and appends a corresponding history snapshot. No current rows or history are deleted.

**Tech Stack:** Java 17, Spring Boot 3.1, Spring JDBC/JdbcTemplate, Apache POI 5.2.5, JUnit 5, H2/MySQL-compatible SQL, Vue 3, Vitest, Vue Test Utils.

## Global Constraints

- Preserve existing dirty changes in `/Users/java/axon-link-server`, `/Users/java/axon-link-frontend`, and `/Users/java/obsidian`; do not reset, revert, or stage unrelated work.
- Existing `POST /api/ai/parallel-replay/issues/import` behavior and its eight target Sheets remain unchanged.
- `POST /api/ai/parallel-replay/issues/full-refresh` reads only workbook Sheet index 0; the Sheet name is unrestricted and all later Sheets are ignored.
- Excel values, including blanks, overwrite the matched current row's imported and editable fields; `data_repair_date` and `defect_repair_date` remain null by the previously confirmed rule.
- Database rows absent from the first Sheet and all existing history remain unchanged.
- Blank `issue_id` and blank `issue_key` are handled independently. Each blank field receives its own next available `AUTO-*` sequence value; nonblank values are preserved.
- A source row whose `issue_key` is blank is always inserted after Key generation. A later import can update it only when Excel carries the generated Key.
- Duplicate nonblank `issue_key` values in the first Sheet fail validation before database mutation.
- A matched update keeps the existing database primary key and appends operation `全量基础数据覆盖` with before/incoming/after snapshots.
- An unmatched insert appends operation `全量基础数据导入` with null before snapshot and complete incoming/after snapshots.
- Current-row mutation and history insertion execute in one result-database transaction; any failure rolls back the whole upload.
- The UI warning must describe first-Sheet matching behavior and must not claim that all current rows or history will be cleared.
- Do not create commits unless the user explicitly asks; both repositories and the Obsidian vault contain unrelated dirty changes.

---

### Task 1: Parse Only the First Sheet and Preserve Independent Blank Identities

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueFullRefreshExcelParser.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueFullRefreshExcelParserTest.java`
- Reuse: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`

**Interfaces:**
- Consumes: `MultipartFile` containing any nonempty workbook.
- Produces: `ParsedWorkbook(List<ReplayIssueRow>, Map<String,Integer>, int generatedIdentityRows, int sandboxRows, int nonSandboxRows)` where `rowsBySheet` contains exactly the actual first-Sheet name.
- Produces blank `ReplayIssueRow.issueId` and/or `issueKey` for the service to allocate in Task 2; `generatedIdentityRows` counts rows with either identity blank.

- [ ] **Step 1: Replace old two-Sheet parser assertions with failing first-Sheet tests.**

Add cases whose first Sheet is named `任意批次` and second Sheet is `0803`. Assert only the first row is returned, `rowsBySheet` equals `Map.of("任意批次", 1)`, and `sourceSheet` equals `任意批次`. Add the inverse data arrangement to prove Sheet index, not Sheet name, controls parsing.

- [ ] **Step 2: Add failing independent blank-field tests.**

Use three first-Sheet rows: blank ID with `KEY-A`, `ID-B` with blank Key, and both blank. Assert the parser preserves those blanks and returns `generatedIdentityRows == 3`; a fourth fully identified row stays unchanged. Keep a duplicate nonblank Key test and assert both Excel row locations appear in the validation error.

- [ ] **Step 3: Run the parser test and confirm RED.**

```bash
mvn -q -Dtest=ReplayIssueFullRefreshExcelParserTest test
```

Expected: failures showing both Sheets are still parsed, target names are still required, and partially blank identities are rejected.

- [ ] **Step 4: Implement the minimal parser change.**

Remove `TARGET_SHEETS` and `validateTargetSheets`. Reject `workbook.getNumberOfSheets() == 0`; otherwise parse only `workbook.getSheetAt(0)`. Do not generate identifiers in the parser. Count a row once in `generatedIdentityRows` when either trimmed identity is blank, validate duplicate nonblank keys within the parsed Sheet, and preserve the existing header/status/sandbox/data-repair behavior.

- [ ] **Step 5: Run the focused parser tests and confirm GREEN.**

```bash
mvn -q -Dtest=ReplayIssueFullRefreshExcelParserTest test
```

Expected: all parser tests pass.

### Task 2: Allocate Identities and Upsert by Issue Key in One Transaction

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueFullRefreshService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueFullRefreshServiceTest.java`
- Modify when DAO coverage needs it: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueDao.findGeneratedIdentitySequences(): GeneratedIdentitySequences` by reading existing `AUTO-*` identity values and taking the largest trailing numeric suffix separately for IDs and Keys.
- Reuses: `findCurrentByIssueKeyForUpdate(String)`, `insertCurrent(ReplayIssueRow)`, `updateCurrent(ReplayIssueRow)`, and `insertHistory(...)`.
- `fullRefresh(MultipartFile, ReplayIssueOperator)` keeps its public signature and result DTO.

- [ ] **Step 1: Rewrite the destructive replacement test as a failing merge test.**

Seed `MATCHED-KEY`, `UNTOUCHED-KEY`, and their history. Import first-Sheet rows for `MATCHED-KEY` and `NEW-KEY`. Assert the matched row keeps its `id` but all Excel fields, including empty edited fields, replace old values; the untouched row and both old histories remain; the new row is inserted.

- [ ] **Step 2: Add failing history assertions.**

Assert `MATCHED-KEY` receives one new `全量基础数据覆盖` event whose before snapshot contains the old values and whose incoming/after snapshots contain the Excel values. Assert `NEW-KEY` receives `全量基础数据导入` with null before snapshot. Assert no new history exists for `UNTOUCHED-KEY`.

- [ ] **Step 3: Add failing collision-free identity tests.**

Seed `issue_id=AUTO-000007` and `issue_key=AUTO-KEY-000011`. Import rows with only ID blank, only Key blank, and both blank. Assert generated values continue independently as `AUTO-000008`, `AUTO-KEY-000012`, then `AUTO-000009` and `AUTO-KEY-000013`; existing nonblank counterparts remain unchanged.

- [ ] **Step 4: Run service and DAO tests and confirm RED.**

```bash
mvn -q -Dtest=ReplayIssueFullRefreshServiceTest,ReplayIssueDaoTest test
```

Expected: failures because the current service deletes all rows/history and the DAO has no generated-sequence lookup.

- [ ] **Step 5: Implement generated-sequence discovery.**

Query existing rows whose `issue_id` or `issue_key` starts with `AUTO-`, parse only trailing decimal suffixes in Java, and return separate maxima. This preserves H2/MySQL portability and recognizes both legacy `AUTO-0731-000001` Keys and new `AUTO-KEY-000001` Keys without dynamic SQL.

- [ ] **Step 6: Implement transactional upsert and history.**

Inside `dao.inTransaction`, allocate missing identities in workbook order, then call `findCurrentByIssueKeyForUpdate`. For a match, build the normalized row with the existing primary key, capture `snapshot(existing)`, call `updateCurrent`, and insert `全量基础数据覆盖`. For no match, call `insertCurrent` and insert `全量基础数据导入`. Remove calls to `deleteAllHistory()` and `deleteAllCurrent()` from the service; leave the DAO methods untouched if another local change still uses them.

- [ ] **Step 7: Replace the rollback test.**

Make history insertion fail after one update/insert. Assert the matched row has its old values, the new row does not exist, the untouched row remains, and all preexisting history is unchanged.

- [ ] **Step 8: Run focused backend regression.**

```bash
mvn -q -Dtest=ReplayIssueFullRefreshExcelParserTest,ReplayIssueFullRefreshServiceTest,ReplayIssueDaoTest,ReplayIssueControllerTest,ReplayIssueImportServiceTest test
```

Expected: all focused tests pass and ordinary import tests remain unchanged.

### Task 3: Correct the Full-Update UI Contract

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Verify unchanged: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Verify unchanged: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`

**Interfaces:**
- Keeps: `fullRefreshReplayIssues(file, token)` and `confirm=FULL_REFRESH` unchanged.
- Changes only user-facing description and confirmation copy for the existing full-update modal.

- [ ] **Step 1: Write failing copy assertions.**

Assert the modal contains `仅处理 Excel 第一个 Sheet，按 issue_key 覆盖匹配数据；未匹配数据新增，其他已有数据和历史记录保留。` and confirmation text `我确认按 issue_key 覆盖或新增首个 Sheet 数据`. Assert the old “清空当前问题和历史记录” wording is absent.

- [ ] **Step 2: Run focused frontend tests and confirm RED.**

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: page copy assertions fail while the API helper tests remain green.

- [ ] **Step 3: Update the modal copy without changing its controls or API call.**

Replace the destructive warning, file hint, and confirmation label. Preserve file/token/checkbox gating, busy state, result counts, and refresh behavior.

- [ ] **Step 4: Run focused frontend tests and confirm GREEN.**

```bash
cd /Users/java/axon-link-frontend
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: all focused frontend tests pass.

### Task 4: Verify, Build, and Package

**Files:**
- Update generated frontend assets under `src/main/resources/static/` using the frontend production build.
- Create a new source ZIP outside the backend repository after verification; do not reuse earlier full-refresh packages.

- [ ] **Step 1: Run the replay backend suite.**

```bash
mvn -q -Dtest='com.axonlink.ai.replay.**' test
```

Expected: replay tests pass. Record any unrelated preexisting full-suite failures separately.

- [ ] **Step 2: Run the frontend production build into the backend.**

```bash
cd /Users/java/axon-link-frontend
npm run build
```

Expected: build succeeds and refreshes `/Users/java/axon-link-server/src/main/resources/static/`.

- [ ] **Step 3: Run backend packaging without rerunning tests.**

```bash
cd /Users/java/axon-link-server
mvn -q -DskipTests package
```

Expected: Spring Boot package succeeds with the new static assets.

- [ ] **Step 4: Create and verify a fresh backend source ZIP.**

Package source/configuration while excluding `.git`, build outputs, IDE metadata, and prior ZIP files. Verify ZIP integrity and report the absolute path, size, and SHA-256.

- [ ] **Step 5: Report exact modified files and residual risk.**

List backend source/tests, frontend source/tests, generated static assets, design pages, and plan. Explicitly note that blank-Key Excel rows are inserted again on later imports unless the generated Key is copied back into Excel.
