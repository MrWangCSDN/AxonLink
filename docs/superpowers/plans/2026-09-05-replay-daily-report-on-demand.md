# Replay Daily Report On-Demand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace import-time snapshot generation with database-driven on-demand generation of a styled three-Sheet replay daily report.

**Architecture:** Formal import remains an atomic eleven-Sheet database write and no longer touches report files. A read-only report pipeline resolves the selected batch and its immediately preceding batch inside the same RPT/DZ family, loads stored daily rows plus batch-scoped issue projections, calculates summary metrics, and writes an in-memory `.xlsx` containing exactly three styled Sheets.

**Tech Stack:** Java 17, Spring Boot MVC, JdbcTemplate, Apache POI XSSF, JUnit 5, H2 tests, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “数据库驱动按需生成日报（2026-09-05）”, plus the linked data-model and API sections.

## Global Constraints

- Use Java 17 for all Maven commands.
- Do not add a database migration; reuse the four V56 daily tables and existing issue/history tables.
- Formal import must still validate all eleven required Sheets and atomically replace issue and daily rows.
- `RPT` batches compare only with `RPT`; `DZ` batches compare only with `DZ`.
- A selected batch without a preceding same-family batch must fail with `没有上批次数据`.
- The generated workbook contains exactly `汇总信息`, `接口比对明细`, `回放交易覆盖情况`, in that order.
- The latter two Sheets read only the selected current batch and retain stored detail/total values in `source_row` order.
- All three Sheets must reproduce the approved fixed template styles; do not emit plain unstyled tables.
- Do not edit minified static assets by hand; modify `/Users/java/axon-link-frontend` and run its Vite build into the backend static directory.
- Preserve unrelated dirty and untracked files in both repositories.
- Do not create a branch or commit unless the user explicitly asks.

---

### Task 1: Daily Batch and Stored-Row Read Model

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyBatch.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`

**Interfaces:**
- Produces: `record ReplayDailyBatch(String batchNo, String family, LocalDateTime importedAt, String previousBatchNo)`.
- Produces: `List<ReplayDailyBatch> findBatchesRecentFirst()` with one row per daily-summary batch and same-family predecessor populated.
- Produces: `boolean batchExists(String batchNo)` and retains existing `findSummaries`, `findComparisons`, `findCoverageSummaries`, `findCoverageDetails` ordered by `source_row,id`.

- [ ] **Step 1: Write failing DAO tests for family-isolated predecessor selection**

```java
@Test
void listsDistinctBatchesWithPreviousBatchInsideTheSameFamily() {
    dao.replaceBatch(data("RPT20260901-01", 1), LocalDateTime.of(2026, 9, 1, 9, 0));
    dao.replaceBatch(data("DZ20260901-01", 2), LocalDateTime.of(2026, 9, 1, 10, 0));
    dao.replaceBatch(data("RPT20260902-01", 3), LocalDateTime.of(2026, 9, 2, 9, 0));
    dao.replaceBatch(data("DZ20260902-01", 4), LocalDateTime.of(2026, 9, 2, 10, 0));

    Map<String, ReplayDailyBatch> batches = dao.findBatchesRecentFirst().stream()
            .collect(Collectors.toMap(ReplayDailyBatch::batchNo, Function.identity()));

    assertEquals("RPT20260901-01", batches.get("RPT20260902-01").previousBatchNo());
    assertEquals("DZ20260901-01", batches.get("DZ20260902-01").previousBatchNo());
    assertNull(batches.get("RPT20260901-01").previousBatchNo());
}
```

- [ ] **Step 2: Run the focused DAO test and verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyDataDaoTest test`

Expected: compilation fails because `ReplayDailyBatch` and `findBatchesRecentFirst()` do not exist.

- [ ] **Step 3: Add the batch record and deterministic batch query**

```java
public record ReplayDailyBatch(
        String batchNo,
        String family,
        LocalDateTime importedAt,
        String previousBatchNo) {
}
```

Query distinct summary batches with `MAX(imported_at)`, reject non-`RPT`/`DZ` values from the generated list, sort ascending per family to assign the predecessor, then return the final list by `importedAt DESC, batchNo DESC`. Re-importing a batch replaces rows and therefore still creates one list entry.

- [ ] **Step 4: Run DAO tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyDataDaoTest test`

Expected: PASS, including existing stored detail/total ordering assertions.

### Task 2: Batch-Aware Issue Statistics Projection

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyIssueStatisticRow.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `record ReplayDailyIssueStatisticRow(long issueId, String groupName, boolean sandbox, String issueType, String issueLevel, String fieldName, String issueStatus, long affectedTransactionCount, boolean reopenedAfterFixedInReportBatch)`.
- Produces: `List<ReplayDailyIssueStatisticRow> findDailyReportIssueStatistics(String occurrenceBatchNo, String reportBatchNo)`.
- Consumes: `dii_replay_issue_occurrence_batch` for membership, `dii_replay_issue` for current fields, and `dii_replay_issue_history.operation_type='已修复问题重新新建'` plus `occurrence_batch_name=reportBatchNo` for the special marker.

- [ ] **Step 1: Write failing projection tests**

```java
@Test
void dailyReportProjectionUsesOccurrenceMembershipAndCurrentValues() {
    insertIssueWithOccurrence(1L, "RPT20260901-01", "公共组", false,
            "代码问题", "交易级", "528成功ccbs失败", "打开", 7L);

    ReplayDailyIssueStatisticRow row = dao.findDailyReportIssueStatistics(
            "RPT20260901-01", "RPT20260902-01").get(0);

    assertEquals(7L, row.affectedTransactionCount());
    assertEquals("528成功ccbs失败", row.fieldName());
    assertFalse(row.reopenedAfterFixedInReportBatch());
}

@Test
void dailyReportProjectionMarksFixedToNewInTheSelectedReportBatch() {
    insertIssueWithOccurrence(2L, "RPT20260901-01", "公共组", false,
            "代码问题", "交易级", "字段", "新建", 3L);
    insertHistory(2L, "已修复问题重新新建", "RPT20260902-01");

    assertTrue(dao.findDailyReportIssueStatistics(
            "RPT20260901-01", "RPT20260902-01").get(0).reopenedAfterFixedInReportBatch());
}
```

- [ ] **Step 2: Run focused projection tests and verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest#dailyReportProjection* test`

Expected: compilation fails because the projection API does not exist.

- [ ] **Step 3: Implement one read-only projection query**

```sql
SELECT i.id, i.group_name, i.is_sandbox, i.issue_type, i.issue_level,
       i.field_name, i.issue_status,
       COALESCE(i.affected_transaction_count, 0) AS affected_transaction_count,
       CASE WHEN EXISTS (
           SELECT 1 FROM dii_replay_issue_history h
            WHERE h.replay_issue_id=i.id
              AND h.operation_type='已修复问题重新新建'
              AND h.occurrence_batch_name=?
       ) THEN 1 ELSE 0 END AS reopened_after_fixed
  FROM dii_replay_issue_occurrence_batch ob
  JOIN dii_replay_issue i ON i.id=ob.replay_issue_id
 WHERE ob.batch_name=?
 ORDER BY i.id
```

Pass `reportBatchNo` first and `occurrenceBatchNo` second. Reject a negative affected count with `IllegalStateException` rather than producing an invalid report.

- [ ] **Step 4: Run focused DAO tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDaoTest#dailyReportProjection* test`

Expected: PASS.

### Task 3: Summary Calculation Engine

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailySummaryCalculatedRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculator.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportCalculatorTest.java`

**Interfaces:**
- Consumes: one `ReplayDailySummaryRow`, its matching `ReplayDailyIssueStatisticRow` list, and a boolean indicating upper/previous or lower/current layout.
- Produces: immutable calculated rows containing stored static values, three recalculated difference counts, no-action count, success rate, match-pass rate, ten fixed issue-type counts, investigation progress, five previous-unresolved counts, unresolved total, and previous resolution rate.
- Produces: `CalculatedReport calculate(List<ReplayDailySummaryRow> previousSummaries, List<ReplayDailyIssueStatisticRow> previousIssues, List<ReplayDailySummaryRow> currentSummaries, List<ReplayDailyIssueStatisticRow> currentIssues)`.

- [ ] **Step 1: Write failing tests for transaction sums and percentages**

```java
@Test
void recalculatesTransactionColumnsByAffectedCountAndExcludesNoAction() {
    CalculatedReport report = calculator.calculate(previousRows(), previousIssues(), currentRows(), currentIssues());
    ReplayDailySummaryCalculatedRow current = report.currentRows().get(0);

    assertEquals(7L, current.c528SuccessCcbsFail());
    assertEquals(5L, current.c528FailCcbsSuccess());
    assertEquals(3L, current.bothFailDiffCode());
    assertEquals(11L, current.noAction());
    assertEquals(new BigDecimal("0.90000000"), current.successRate());
}
```

Include cases for field-level exclusion, status `无需处理` exclusion from the three fields, zero denominator, and match-rate reverse calculation/clamping.

- [ ] **Step 2: Write failing tests for issue classification semantics**

```java
@Test
void fixedToNewCountsAsAnalyzedAndNotFullyFixed() {
    ReplayDailyIssueStatisticRow row = issue("代码问题", "新建", true, 1L);
    CalculatedReport report = calculator.calculate(previousRows(), List.of(row), currentRows(), List.of());

    assertEquals(1L, report.previousRows().get(0).codeIssueCount());
    assertEquals(1L, report.previousUnresolved().notFullyFixed());
    assertEquals(0L, report.previousUnresolved().unanalyzed());
}
```

Cover all ten fixed issue types and mappings `新建/打开/重新打开/延后修复/修复待验证`, plus zero issue-total percentages.

- [ ] **Step 3: Run calculator tests and verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportCalculatorTest test`

Expected: compilation fails because the calculator types do not exist.

- [ ] **Step 4: Implement the calculator without POI dependencies**

Use exact normalized field matching for `528成功ccbs失败`, `528失败ccbs成功`, and `二者都失败响应码不一致`; group by normalized display domain (`沙箱-<group>` for sandbox rows). Use `BigDecimal.divide(..., 8, RoundingMode.HALF_UP)` and return `BigDecimal.ZERO` when the denominator is non-positive.

- [ ] **Step 5: Run calculator tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportCalculatorTest test`

Expected: PASS.

### Task 4: Styled Three-Sheet Workbook Writer

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java`
- Reference: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParser.java`
- Reference: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`

**Interfaces:**
- Consumes: `CalculatedReport`, selected-batch `List<ReplayInterfaceComparisonRow>`, `List<ReplayCoverageSummaryRow>`, and `List<ReplayCoverageDetailRow>`.
- Produces: `byte[] write(CalculatedReport summary, List<ReplayInterfaceComparisonRow> comparisons, List<ReplayCoverageSummaryRow> coverageSummaries, List<ReplayCoverageDetailRow> coverageDetails)`.

- [ ] **Step 1: Write a failing workbook structure test**

```java
@Test
void writesExactlyThreeSheetsInRequiredOrder() throws Exception {
    byte[] bytes = writer.write(summary(), comparisons(), coverageSummaries(), coverageDetails());
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
        assertEquals(3, workbook.getNumberOfSheets());
        assertEquals("汇总信息", workbook.getSheetName(0));
        assertEquals("接口比对明细", workbook.getSheetName(1));
        assertEquals("回放交易覆盖情况", workbook.getSheetName(2));
    }
}
```

- [ ] **Step 2: Write failing style and total-row tests**

Assert the approved green/pink/yellow summary fills, merged parent headers, thin borders, centered wrapped headers, `0.00%` formats, interface TOTAL row output, coverage TOTAL row output, original row ordering, and non-default widths/heights for all three Sheets.

- [ ] **Step 3: Run writer tests and verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportWorkbookWriterTest test`

Expected: compilation fails because the writer does not exist.

- [ ] **Step 4: Implement a shared style palette and three focused Sheet methods**

```java
public byte[] write(...) {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        StylePalette styles = StylePalette.create(workbook);
        writeSummarySheet(workbook, styles, summary);
        writeInterfaceComparisonSheet(workbook, styles, comparisons);
        writeCoverageSheet(workbook, styles, coverageSummaries, coverageDetails);
        workbook.write(output);
        return output.toByteArray();
    }
}
```

Use fixed header arrays matching parser field names. Write stored detail and total values directly for interface/coverage Sheets; do not recalculate their totals. Apply template-specific merged titles, header bands, borders, alignment, row heights, column widths, date `yyyy-mm-dd`, percentage `0.00%`, integer `0`, and duration `0.000000` formats.

- [ ] **Step 5: Run writer tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayDailyReportWorkbookWriterTest test`

Expected: PASS.

### Task 5: On-Demand Service, Import Decoupling, and HTTP Contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- `ReplayIssueDailyReportService(ReplayDailyDataDao, ReplayIssueDao, ReplayDailyReportCalculator, ReplayDailyReportWorkbookWriter)`.
- Produces: `List<ReplayDailyBatch> listBatches()`.
- Produces: `byte[] generate(String batchNo)`.
- Controller keeps `GET /daily-report/batches` and `GET /daily-report?batchNo=...` paths but returns the new fields and generated bytes.

- [ ] **Step 1: Write failing service tests for database-only generation**

```java
@Test
void generatesSelectedBatchUsingItsSameFamilyPreviousBatch() {
    seedDailyBatch("RPT20260901-01", firstTime);
    seedDailyBatch("DZ20260901-01", secondTime);
    seedDailyBatch("RPT20260902-01", thirdTime);

    byte[] workbook = service.generate("RPT20260902-01");

    assertSummaryBatches(workbook, "RPT20260901-01", "RPT20260902-01");
}
```

Add tests for missing selected batch, missing previous same-family batch, selected current-batch interface/coverage rows only, and no file creation.

- [ ] **Step 2: Write a failing import test proving report generation is not called**

Construct the import service with a mocked report service, import a valid eleven-Sheet workbook, verify daily rows persist, and `verifyNoInteractions(dailyReportService)`.

- [ ] **Step 3: Write failing controller tests for list/download/errors**

Assert list fields `batchNo/family/importedAt/previousBatchNo/canGenerate`, generated `.xlsx` content type and filename, HTTP 404 `批次数据不存在`, and HTTP 409 `没有上批次数据`.

- [ ] **Step 4: Run focused tests and verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDailyReportServiceTest,ReplayIssueImportServiceTest,ReplayIssueControllerTest test`

Expected: failures still reflect file snapshots and import-time generation.

- [ ] **Step 5: Replace the rolling-file service with orchestration only**

Validate `batchNo` with `^(RPT|DZ).+`, locate the exact `ReplayDailyBatch`, require non-null `previousBatchNo`, load previous/current summaries, call `findDailyReportIssueStatistics(previousBatchNo, batchNo)` and `findDailyReportIssueStatistics(batchNo, batchNo)`, load only current comparison/coverage rows, calculate, and write bytes.

- [ ] **Step 6: Remove import-time report dependencies**

Delete `dailyReportService`, `summaryParser`, `persistDailyReport(...)`, and related constructor parameters from `ReplayIssueImportService`. Keep `ReplayDailyWorkbookParser` and `ReplayDailyDataDao` in the formal-import constructor and retain legacy test constructors without silently weakening eleven-Sheet production validation.

- [ ] **Step 7: Update controller response handling**

Return `R.ok(dailyReportService.listBatches())`; generate bytes inside the download endpoint. Add narrowly scoped exception handling so malformed input is 400, missing batch is 404, missing predecessor is 409, and unexpected failures are 500 without leaking exception text.

- [ ] **Step 8: Run focused backend tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -Dtest=ReplayIssueDailyReportServiceTest,ReplayIssueImportServiceTest,ReplayIssueControllerTest test`

Expected: PASS.

### Task 6: Frontend Generate-Daily-Report Interaction

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify if the local mock serves this endpoint: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`

**Interfaces:**
- Consumes batch entries `{ batchNo, family, importedAt, previousBatchNo, canGenerate }`.
- Keeps `getReplayDailyReportBatches()` and `downloadReplayDailyReport(batchNo)` API functions.

- [ ] **Step 1: Write failing component tests for new wording and state**

```javascript
it('presents database-driven daily report generation', async () => {
  api.getReplayDailyReportBatches.mockResolvedValue([
    { batchNo: 'RPT20260902-01', previousBatchNo: 'RPT20260901-01', canGenerate: true },
    { batchNo: 'RPT20260901-01', previousBatchNo: null, canGenerate: false },
  ])
  await wrapper.get('[data-testid="open-daily-report"]').trigger('click')
  expect(wrapper.text()).toContain('生成日报')
  expect(wrapper.text()).not.toContain('导入时的快照')
  expect(wrapper.get('[data-testid="generate-daily-report"]').text()).toContain('生成 Excel')
})
```

Also assert the first `canGenerate` batch is selected, first-family batches remain visible, and a backend `没有上批次数据` message is displayed without closing the modal.

- [ ] **Step 2: Run focused frontend test and verify failure**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayIssuePage.spec.js`

Expected: FAIL on old “下载日报/导入时快照/available” UI.

- [ ] **Step 3: Update API comments and modal behavior**

Change modal title to `生成日报`, description to database real-time generation, empty text to `暂无可生成日报的批次，请先导入 Excel`, `available` checks to `canGenerate`, button test id to `generate-daily-report`, progress text to `生成中…`, and error prefix to `生成失败：`. Keep non-generatable batches visible with `（没有上批次数据）`.

- [ ] **Step 4: Run focused frontend tests**

Run: `cd /Users/java/axon-link-frontend && npm test -- ReplayIssuePage.spec.js`

Expected: PASS.

- [ ] **Step 5: Build frontend into backend static resources**

Run: `cd /Users/java/axon-link-frontend && npm run build`

Expected: Vite succeeds and rewrites `/Users/java/axon-link-server/src/main/resources/static` from source; no minified file is hand-edited.

### Task 7: Full Verification and Documentation Closure

**Files:**
- Modify only if implementation details changed the approved design: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Modify only if contract details changed: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`
- Modify only if contract details changed: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`
- Append implementation result: `/Users/java/obsidian/log.md`

**Interfaces:**
- Validates the complete backend, frontend, static bundle, and documentation state.

- [ ] **Step 1: Run all frontend tests**

Run: `cd /Users/java/axon-link-frontend && npm test`

Expected: PASS; if an unrelated pre-existing failure remains, record the exact test without changing unrelated behavior.

- [ ] **Step 2: Run full Java 17 backend tests**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q test
```

Expected: PASS.

- [ ] **Step 3: Build the backend package**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -DskipTests package
```

Expected: PASS and a packaged artifact under `target/` containing the rebuilt frontend static assets.

- [ ] **Step 4: Inspect one generated workbook end-to-end**

Generate a test workbook from two RPT batches and one interleaved DZ batch, open it with POI, and assert the three Sheet names, previous/current batch labels, current-only interface/coverage rows, totals, formulas-as-values, merged regions, colors, borders, widths, heights, and number formats.

- [ ] **Step 5: Append the implementation log entry**

Append one `[IMPL]` line to `/Users/java/obsidian/log.md` with files/components changed and exact frontend/backend verification results. Do not create a duplicate design spec in the repository.
