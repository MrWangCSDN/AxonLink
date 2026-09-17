# Replay Report Mail Summary HTML Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send daily/weekly replay report emails as HTML, appending complete snapshot-consistent lower-summary tables for every selected system report, ordered query before accounting.

**Architecture:** Extract a channel-neutral `ReplayReportSummaryView` from `CalculatedReport`, persist it beside each Excel snapshot, and use the same view for Excel rows and email HTML. Replace daily-only attachment references with typed daily/weekly references while preserving one release of `reportBatchNos` compatibility; local Excel files remain attachment-only.

**Tech Stack:** Java 17, Spring Boot 3.1, Spring JDBC, Jackson, Apache POI 5.2.5, Jakarta Mail, JUnit 5, Mockito, H2, Vue 3, Vite 8, Vitest 4.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section `日报与周报邮件正文汇总表（2026-09-16）`, with API and model contracts in the sibling design files.

## Global Constraints

- Persist summary JSON with every newly generated or regenerated daily/weekly snapshot; never rebuild historical HTML from current business tables.
- Current report and selected generated reports contribute summary tables; locally uploaded Excel files never contribute body content.
- Query (`RPT`) sections and attachments precede accounting (`DZ`); use one shared comparator for both orders.
- Escape the user-authored plain-text body and convert line breaks to `<br>`; never accept arbitrary caller HTML.
- Render every lower-summary column, all domain rows, and the total row; do not recalculate values in the HTML renderer.
- Keep daily/weekly mail endpoints and multipart transport stable; accept legacy `reportBatchNos` for one compatibility release, but the new frontend sends only `generatedReports`.
- A missing or unreadable summary for any selected system report fails the whole request before SMTP; local attachments remain subject to 20MB-per-file and 50MB-total limits.
- Do not alter daily/weekly calculation formulas, Excel sheet names, report filenames, mail state semantics, or SMTP recipient configuration.
- Work in the existing dirty backend and frontend workspaces without reverting unrelated changes; stage only files belonging to the task being committed.

---

### Task 1: Introduce the Shared Summary View

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportPeriod.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportSummaryColumn.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportSummaryRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportSummaryView.java`
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportSummaryViewFactory.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayReportSummaryViewFactoryTest.java`

**Interfaces:**
- Consumes: `ReplayDailyReportCalculator.CalculatedReport` and `ReplayDailySummaryCalculatedRow`.
- Produces: `ReplayReportSummaryView create(ReplayReportPeriod period, String startBatchNo, String endBatchNo, CalculatedReport report)`.

- [ ] **Step 1: Write the failing factory test**

```java
@Test
void createsCompleteLowerSummaryViewWithoutRecalculatingValues() {
    var report = calculatedReportWithCurrentRowAndTotal();
    var view = new ReplayReportSummaryViewFactory().create(
            ReplayReportPeriod.DAILY, null, "RPT20260916-090000", report);

    assertEquals(1, view.schemaVersion());
    assertEquals("RPT", view.family());
    assertEquals("查询日报-20260916", view.reportName());
    assertEquals(21, view.columns().size());
    assertEquals("上一批次未解决问题分类统计", view.columns().get(16).groupLabel());
    assertEquals(report.currentRows().get(0).previousResolutionRate(),
            view.rows().get(0).values().get("previousResolutionRate"));
    assertEquals("TOTAL", view.totalRow().rowType());
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `mvn -Dtest=ReplayReportSummaryViewFactoryTest test`

Expected: compilation fails because the summary types and factory do not exist.

- [ ] **Step 3: Add immutable summary types and the exact lower-table column definition**

```java
public enum ReplayReportPeriod { DAILY, WEEKLY }

public record ReplayReportSummaryColumn(
        String key, String label, String groupLabel, ValueType valueType, int order) {
    public enum ValueType { TEXT, INTEGER, PERCENT }
}

public record ReplayReportSummaryRow(
        String domain, String rowType, Map<String, Object> values) {
    public ReplayReportSummaryRow {
        values = Map.copyOf(values);
    }
}

public record ReplayReportSummaryView(
        int schemaVersion,
        ReplayReportPeriod period,
        String family,
        String startBatchNo,
        String endBatchNo,
        String reportName,
        List<ReplayReportSummaryColumn> columns,
        List<ReplayReportSummaryRow> rows,
        ReplayReportSummaryRow totalRow) {
    public ReplayReportSummaryView {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
```

Build the 21 columns in workbook order: batch, domain, covered interface count, sent transactions, seven comparison-category columns, success rate, match pass rate, issue total, previous unresolved total, previous resolution rate, and five unresolved-category columns. Copy values directly from each `ReplayDailySummaryCalculatedRow`; do not sum or derive values in the factory.

- [ ] **Step 4: Run the test and verify it passes**

Run: `mvn -Dtest=ReplayReportSummaryViewFactoryTest test`

Expected: PASS, including null-number and percentage preservation assertions.

- [ ] **Step 5: Commit the shared model**

```bash
git add src/main/java/com/axonlink/ai/replay/dto/ReplayReportPeriod.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayReportSummaryColumn.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayReportSummaryRow.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayReportSummaryView.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayReportSummaryViewFactory.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayReportSummaryViewFactoryTest.java
git commit -m "feat: add replay report summary view"
```

### Task 2: Make Excel Consume the Shared View

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java`

**Interfaces:**
- Consumes: `ReplayReportSummaryViewFactory.create(...)` and the unchanged comparison/coverage inputs.
- Produces: `byte[] write(CalculatedReport report, ..., ReplayReportPeriod period, String startBatchNo, String endBatchNo)` or an equivalent overload retaining existing callers during migration.

- [ ] **Step 1: Add a failing workbook parity test**

Add a test that passes a `ReplayReportSummaryView` into the writer, opens the resulting workbook with POI, locates the lower summary header, and asserts all 21 header/value cells plus the total row match the view. Include one `null` integer and one `BigDecimal("0.875")` percentage.

```java
assertEquals("上一批次问题解决率", sheet.getRow(headerRow).getCell(15).getStringCellValue());
assertEquals(0.875d, sheet.getRow(dataRow).getCell(15).getNumericCellValue(), 0.000001d);
assertEquals("合计", sheet.getRow(totalRow).getCell(1).getStringCellValue());
```

- [ ] **Step 2: Run the focused test and verify the old private path cannot accept the view**

Run: `mvn -Dtest=ReplayDailyReportWorkbookWriterTest test`

Expected: FAIL because `writeLowerSummary` still consumes `CalculatedReport` directly.

- [ ] **Step 3: Refactor only the lower-summary writer**

Change `writeLowerSummary` and `writeLowerSummaryRow` to consume `ReplayReportSummaryView`. Keep upper summary, interface comparison, coverage sheets, styles, row heights, merged regions, and column widths unchanged. The writer may map stable column keys to typed cell writers, but it must not read `CalculatedReport.currentRows()` or `currentTotal()` inside the lower-table method.

- [ ] **Step 4: Run workbook and calculator regressions**

Run: `mvn -Dtest=ReplayDailyReportWorkbookWriterTest,ReplayDailyReportCalculatorTest test`

Expected: PASS with unchanged existing workbook assertions.

- [ ] **Step 5: Commit the Excel refactor**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriter.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportWorkbookWriterTest.java
git commit -m "refactor: share replay summary view with excel"
```

### Task 3: Persist Summary JSON With Report Snapshots

**Files:**
- Create: `src/main/resources/db/daoindex/V69__replay_report_snapshot_summary_view.sql`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportSnapshot.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportSnapshot.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataMigrationTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java`

**Interfaces:**
- Consumes: serialized `ReplayReportSummaryView` JSON.
- Produces: snapshot records with nullable `String summaryViewJson` for legacy rows.

- [ ] **Step 1: Add failing migration and DAO round-trip tests**

```java
@Test
void addsNullableSummaryViewToDailyAndWeeklySnapshots() {
    apply(V57, V60, V69);
    assertColumns(jdbc, "DII_REPLAY_DAILY_REPORT_SNAPSHOT", "SUMMARY_VIEW_JSON");
    assertColumns(jdbc, "DII_REPLAY_WEEKLY_REPORT_SNAPSHOT", "SUMMARY_VIEW_JSON");
}
```

In both DAO tests, save a snapshot with `summaryViewJson="{\"schemaVersion\":1}"`, read it back, and assert exact equality. Insert a legacy row with SQL omitting the column and assert the DTO returns `null`.

- [ ] **Step 2: Run persistence tests and verify they fail**

Run: `mvn -Dtest=ReplayDailyDataMigrationTest,ReplayDailyDataDaoTest,ReplayWeeklyReportDaoTest test`

Expected: FAIL because the column and DTO field do not exist.

- [ ] **Step 3: Add the additive migration and update all SELECT/INSERT/UPDATE statements**

```sql
ALTER TABLE dii_replay_daily_report_snapshot
    ADD COLUMN summary_view_json MEDIUMTEXT NULL AFTER file_size;

ALTER TABLE dii_replay_weekly_report_snapshot
    ADD COLUMN summary_view_json MEDIUMTEXT NULL AFTER file_size;
```

Add `String summaryViewJson` before `generatedAt` in both snapshot records. Include the column in daily single/batch reads and upsert, weekly single reads, insert, and replace. Do not backfill existing rows and do not add a database `NOT NULL` constraint.

- [ ] **Step 4: Run the persistence tests and verify they pass**

Run: `mvn -Dtest=ReplayDailyDataMigrationTest,ReplayDailyDataDaoTest,ReplayWeeklyReportDaoTest test`

Expected: PASS for new and legacy rows.

- [ ] **Step 5: Commit snapshot persistence**

```bash
git add src/main/resources/db/daoindex/V69__replay_report_snapshot_summary_view.sql \
  src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportSnapshot.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportSnapshot.java \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java \
  src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataMigrationTest.java \
  src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java \
  src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java
git commit -m "feat: persist replay report summary snapshots"
```

### Task 4: Generate Excel and Summary JSON Atomically

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportSummaryCodec.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayReportSummaryCodecTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java`

**Interfaces:**
- Consumes: `ReplayReportSummaryViewFactory` and `ReplayReportSummaryCodec.encode/decode`.
- Produces: snapshots whose Excel and summary JSON originate from the same `CalculatedReport` instance.

- [ ] **Step 1: Add failing codec and generation tests**

```java
@Test
void roundTripsSummaryView() {
    String json = codec.encode(view);
    assertEquals(view, codec.decode(json));
}
```

In daily and weekly service tests, capture the snapshot passed to the DAO, decode `summaryViewJson`, and assert its end batch, period, family, first domain, and total row match the workbook input. Add a codec-throws test and assert neither DAO save nor mail-state deletion occurs.

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `mvn -Dtest=ReplayReportSummaryCodecTest,ReplayIssueDailyReportServiceTest,ReplayWeeklyReportServiceTest test`

Expected: FAIL because generation saves only bytes.

- [ ] **Step 3: Implement deterministic codec and one-pass report artifacts**

```java
public final class ReplayReportSummaryCodec {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public String encode(ReplayReportSummaryView view) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(view, "view"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("回放报告汇总序列化失败", exception);
        }
    }

    public ReplayReportSummaryView decode(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("回放报告汇总为空");
        try {
            ReplayReportSummaryView view = objectMapper.readValue(json, ReplayReportSummaryView.class);
            if (view.schemaVersion() != 1) throw new IllegalArgumentException("不支持的回放报告汇总版本");
            return view;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("回放报告汇总格式错误", exception);
        }
    }
}

private record GeneratedReport(byte[] workbook, ReplayReportSummaryView summary) {}
```

For daily, create the view with `period=DAILY`, `startBatchNo=null`, `endBatchNo=batchNo`. For weekly, create it with the exact start/end range. Serialize before entering the snapshot write. Preserve current transaction behavior: regeneration replaces snapshot and deletes mail state in one write transaction; any failure leaves the previous snapshot and state unchanged.

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `mvn -Dtest=ReplayReportSummaryCodecTest,ReplayIssueDailyReportServiceTest,ReplayWeeklyReportServiceTest test`

Expected: PASS, including rollback assertions.

- [ ] **Step 5: Commit artifact generation**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayReportSummaryCodec.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayReportSummaryCodecTest.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportServiceTest.java
git commit -m "feat: snapshot replay excel and summary together"
```

### Task 5: Support Legacy Snapshot Summary Extraction

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayLegacySummaryExtractor.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayLegacySummaryExtractorTest.java`

**Interfaces:**
- Consumes: legacy xlsx bytes plus explicit `period`, `startBatchNo`, and `endBatchNo`.
- Produces: `ReplayReportSummaryView extract(byte[] workbook, ReplayReportPeriod period, String startBatchNo, String endBatchNo)` or throws `LegacySummaryUnavailableException`.

- [ ] **Step 1: Write failing tests against real generated workbooks**

Create an xlsx using `ReplayDailyReportWorkbookWriter`, call the extractor, and compare all column keys, row values, and total values with the factory view. Add failures for empty bytes, missing `汇总信息`, missing lower title row, wrong header order, and absent total row.

- [ ] **Step 2: Run the extractor test and verify it fails**

Run: `mvn -Dtest=ReplayLegacySummaryExtractorTest test`

Expected: compilation failure because the extractor does not exist.

- [ ] **Step 3: Implement strict fixed-template parsing**

Open the workbook with `WorkbookFactory`, find the lower title row by `批次号：...（本批次）`, validate the next two header rows against the 21 known columns, read rows until `领域=合计`, and preserve numeric cell values as `Long`/`BigDecimal`. Never query a DAO and never infer missing cells from current data.

```java
if (!EXPECTED_HEADER.equals(actualHeader)) {
    throw new LegacySummaryUnavailableException("历史报告汇总表结构不兼容");
}
```

- [ ] **Step 4: Run the extractor test and verify it passes**

Run: `mvn -Dtest=ReplayLegacySummaryExtractorTest test`

Expected: PASS for daily and weekly metadata and all malformed-workbook cases.

- [ ] **Step 5: Commit legacy compatibility**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayLegacySummaryExtractor.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayLegacySummaryExtractorTest.java
git commit -m "feat: read summaries from legacy report snapshots"
```

### Task 6: Add Typed Daily/Weekly Report References and Unified Candidates

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayGeneratedReportRef.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailSendRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailSendRequest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayReportAttachmentOption.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentMetadata.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentSource.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java`

**Interfaces:**
- Produces: `ReplayGeneratedReportRef(period, startBatchNo, endBatchNo)` and `GET /report-attachments/options`.
- Compatibility: request DTOs retain `List<String> reportBatchNos`; `effectiveGeneratedReports()` returns new refs when present, otherwise converts legacy values to daily refs.

- [ ] **Step 1: Add failing JSON and endpoint tests**

```java
var request = mapper.readValue("""
  {"generatedReports":[{"period":"WEEKLY","startBatchNo":"RPT20260909-01","endBatchNo":"RPT20260916-01"}],
   "reportBatchNos":["DZ20260916-01"]}
  """, ReplayDailyReportMailSendRequest.class);
assertEquals(1, request.effectiveGeneratedReports().size());
assertEquals(ReplayReportPeriod.WEEKLY, request.effectiveGeneratedReports().get(0).period());
```

Controller tests must assert the unified endpoint accepts `period=ALL|DAILY|WEEKLY`, `family=ALL|RPT|DZ`, returns both periods, and rejects invalid period/family with HTTP 400.

- [ ] **Step 2: Run DTO/controller/DAO tests and verify they fail**

Run: `mvn -Dtest=ReplayIssueControllerTest,ReplayDailyDataDaoTest,ReplayWeeklyReportDaoTest test`

Expected: FAIL because weekly candidates and typed refs do not exist.

- [ ] **Step 3: Implement typed references and candidate union**

```java
public record ReplayGeneratedReportRef(
        ReplayReportPeriod period, String startBatchNo, String endBatchNo) {
    public String businessKey() {
        return period + "|" + Objects.toString(startBatchNo, "") + "|" + endBatchNo;
    }
}
```

Add `GENERATED_WEEKLY`. Expand attachment metadata to `period/startBatchNo/endBatchNo`. Extend attachment options with `period`, `family`, both batch fields, `summaryViewAvailable`, and `summaryViewSource`. Merge paginated daily and weekly candidates at the service layer using a stable `generatedAt DESC, businessKey DESC` order; fetch enough rows from each table to construct the requested page and count totals independently. Keep `/daily-report/attachment-options` as the legacy daily-only endpoint.

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `mvn -Dtest=ReplayIssueControllerTest,ReplayDailyDataDaoTest,ReplayWeeklyReportDaoTest test`

Expected: PASS for daily, weekly, filters, paging, and compatibility JSON.

- [ ] **Step 5: Commit the request and candidate contract**

```bash
git add src/main/java/com/axonlink/ai/replay/dto/ReplayGeneratedReportRef.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayDailyReportMailSendRequest.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayWeeklyReportMailSendRequest.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayReportAttachmentOption.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentMetadata.java \
  src/main/java/com/axonlink/ai/replay/dto/ReplayMailAttachmentSource.java \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDao.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java \
  src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java \
  src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java \
  src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java \
  src/test/java/com/axonlink/ai/replay/persistence/ReplayWeeklyReportDaoTest.java
git commit -m "feat: select generated daily and weekly reports"
```

### Task 7: Resolve Attachments and Summary Views in One Ordered Context

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentServiceTest.java`

**Interfaces:**
- Consumes: current report snapshot/view, `List<ReplayGeneratedReportRef>`, local files.
- Produces: `ResolvedReportMail(List<MailAttachment> mailAttachments, List<ReplayMailAttachmentMetadata> metadata, List<ReplayReportSummaryView> summaries, long totalSize)`.

- [ ] **Step 1: Add failing mixed-report ordering and failure tests**

Cover current DZ weekly report plus selected RPT daily, DZ daily, and RPT weekly references in scrambled order. Assert the resolved system order is both RPT reports followed by both DZ reports, with the current report deduplicated by exact business key and local files last. Assert summaries use JSON when present and legacy extraction only when JSON is null. Assert one unreadable legacy workbook throws a complete-request `SummaryUnavailableException` before local file processing completes.

- [ ] **Step 2: Run the attachment-service test and verify it fails**

Run: `mvn -Dtest=ReplayReportMailAttachmentServiceTest test`

Expected: FAIL because the resolver accepts daily batch strings and returns no summaries.

- [ ] **Step 3: Implement one shared report comparator and typed snapshot loading**

```java
private static final Comparator<ResolvedSystemReport> REPORT_ORDER =
        Comparator.comparingInt(report -> "RPT".equals(report.summary().family()) ? 0 : 1)
                .thenComparing(report -> report.summary().period())
                .thenComparing(report -> report.summary().endBatchNo())
                .thenComparing(report -> Objects.toString(report.summary().startBatchNo(), ""));
```

Load daily snapshots in one DAO batch and weekly snapshots by normalized ranges. Decode `summaryViewJson`; when null, call `ReplayLegacySummaryExtractor`. Use the sorted system-report list to append both `MailAttachment` and `ReplayReportSummaryView`, ensuring body and attachment order cannot diverge. Keep current report first only if it naturally belongs first under query/accounting ordering; the business-approved global rule is query before accounting, so do not hard-code current report as first among system reports.

- [ ] **Step 4: Run the resolver test and verify it passes**

Run: `mvn -Dtest=ReplayReportMailAttachmentServiceTest test`

Expected: PASS for ordering, deduplication, size limits, legacy fallback, and all-or-nothing errors.

- [ ] **Step 5: Commit report resolution**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayReportMailAttachmentServiceTest.java
git commit -m "feat: resolve replay report mail summaries"
```

### Task 8: Render Safe HTML and Send It Synchronously

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayReportMailHtmlRenderer.java`
- Create: `src/test/java/com/axonlink/ai/replay/service/ReplayReportMailHtmlRendererTest.java`
- Modify: `src/main/java/com/axonlink/notification/service/MailService.java`
- Modify: `src/test/java/com/axonlink/notification/service/MailServiceTest.java`

**Interfaces:**
- Produces: `String render(String plainTextBody, List<ReplayReportSummaryView> summaries)`.
- Produces: `void sendHtmlWithAttachmentsSync(List<String> to, List<String> cc, String subject, String htmlBody, List<MailAttachment> attachments)`.

- [ ] **Step 1: Write failing renderer and MIME tests**

```java
String html = renderer.render("第一行<script>alert(1)</script>\n第二行", List.of(dzView, rptView));
assertTrue(html.contains("第一行&lt;script&gt;alert(1)&lt;/script&gt;<br>第二行"));
assertTrue(html.indexOf(">查询<") < html.indexOf(">账务<"));
assertTrue(html.contains("<th"));
assertTrue(html.contains("合计"));
```

In `MailServiceTest`, inspect MIME part zero and assert `text/html`, UTF-8 content, and caller-order attachments. Configure `JavaMailSender.send` to throw and assert the synchronous method propagates `IllegalStateException`.

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `mvn -Dtest=ReplayReportMailHtmlRendererTest,MailServiceTest test`

Expected: FAIL because renderer and sync HTML attachment method do not exist.

- [ ] **Step 3: Implement conservative inline HTML**

Render a wrapper table-safe layout using inline styles only. Escape `&`, `<`, `>`, `"`, and `'` in user text and labels. Format integers with plain decimal text and percentages as `value * 100` with two decimals and `%`. Render null as an empty cell. Add a 24px spacer between query and accounting sections, and include each report name/range above its table.

Implement `sendHtmlWithAttachmentsSync` by sharing the current MIME setup but calling `helper.setText(htmlBody, true)`. Keep `sendTextWithAttachmentsSync` unchanged for other callers.

- [ ] **Step 4: Run renderer and mail tests**

Run: `mvn -Dtest=ReplayReportMailHtmlRendererTest,MailServiceTest test`

Expected: PASS with escaped content, query-first sections, total row, HTML MIME, attachment order, and propagated failure.

- [ ] **Step 5: Commit HTML delivery**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayReportMailHtmlRenderer.java \
  src/main/java/com/axonlink/notification/service/MailService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayReportMailHtmlRendererTest.java \
  src/test/java/com/axonlink/notification/service/MailServiceTest.java
git commit -m "feat: send replay reports with html summaries"
```

### Task 9: Integrate Daily/Weekly Mail Services and Error Mapping

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `effectiveGeneratedReports()`, `ResolvedReportMail`, and `ReplayReportMailHtmlRenderer`.
- Produces: unchanged daily/weekly mail response views and mail-state transitions.

- [ ] **Step 1: Add failing service integration tests**

For each service, capture arguments sent to `MailService.sendHtmlWithAttachmentsSync` and assert:

```java
verify(mailService).sendHtmlWithAttachmentsSync(
        eq(List.of("to@example.com")), eq(List.of("cc@example.com")), eq("标题"),
        contains("<table"), argThat(items -> items.size() == 3));
verify(mailService, never()).sendTextWithAttachmentsSync(any(), any(), any(), any(), any());
```

Assert the mail DAO still stores the raw body, not HTML. Add controller cases for malformed report refs (400), missing report snapshots (404), unavailable historical summary (409), renderer failure (500), and SMTP failure (502). Assert no SMTP call for all pre-send failures.

- [ ] **Step 2: Run focused integration tests and verify they fail**

Run: `mvn -Dtest=ReplayDailyReportMailServiceTest,ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test`

Expected: FAIL because services still send plain text and daily-only refs.

- [ ] **Step 3: Switch both services to resolved HTML mail**

Resolve current plus selected reports, render HTML from `request.body()` and resolved summaries, save `SENDING` with raw body and expanded metadata, then call `sendHtmlWithAttachmentsSync`. Preserve existing `SENT`/`FAILED` updates and exception wrapping. Map `SummaryUnavailableException` to HTTP 409 with the report list and renderer/codec failures to HTTP 500 `邮件汇总表生成失败`.

- [ ] **Step 4: Run mail service and controller tests**

Run: `mvn -Dtest=ReplayDailyReportMailServiceTest,ReplayWeeklyReportMailServiceTest,ReplayIssueControllerTest test`

Expected: PASS for daily and weekly, HTML content, state persistence, error statuses, and legacy request compatibility.

- [ ] **Step 5: Commit backend mail integration**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayDailyReportMailService.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java \
  src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java \
  src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java
git commit -m "feat: append summaries to replay report emails"
```

### Task 10: Upgrade the Frontend Report Picker and Payload

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayReportMailAttachments.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayReportMailAttachments.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `GET /report-attachments/options` and typed options.
- Produces: `generatedReports: [{ period, startBatchNo, endBatchNo }]` in both daily and weekly multipart JSON.

- [ ] **Step 1: Add failing API and component tests**

```javascript
expect(fetch.mock.calls[0][0]).toBe(
  '/api/ai/parallel-replay/issues/report-attachments/options?keyword=202609&period=WEEKLY&family=RPT&page=0&size=20')
```

Mount the attachment component with daily and weekly choices. Assert the button says `添加已生成报告`, selectors contain period options `全部/日报/周报` and family options `全部/查询/账务`, option keys use `period|start|end`, and duplicate/current items are disabled by full business key. In page tests, submit one daily and one weekly selection and assert `generatedReports` is sent and `reportBatchNos` is absent.

- [ ] **Step 2: Run focused frontend tests and verify they fail**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/api/replayIssues.spec.js src/components/replay/ReplayReportMailAttachments.spec.js src/components/replay/ReplayIssuePage.spec.js`

Expected: FAIL because the UI and payload are daily-only.

- [ ] **Step 3: Implement typed selection without adding an HTML editor**

Change the API function to `/report-attachments/options`. Add a `period` ref defaulting to `ALL`; keep `family=ALL`. Use:

```javascript
const reportKey = item => [item?.period, item?.startBatchNo || '', item?.endBatchNo || item?.batchNo || ''].join('|')
const toGeneratedReportRef = item => ({
  period: item.period,
  startBatchNo: item.period === 'WEEKLY' ? item.startBatchNo : null,
  endBatchNo: item.endBatchNo || item.batchNo,
})
```

Display `查询日报`, `账务日报`, `查询周报`, or `账务周报` from period/family. Update empty/loading/error copy from “日报” to “报告”. In `ReplayIssuePage.vue`, build `generatedReports` with `toGeneratedReportRef` for both mail kinds. Keep the existing textarea as plain text; do not add a body preview or editor.

- [ ] **Step 4: Run focused tests and production build**

Run: `cd /Users/java/axon-link-frontend && npm test -- src/api/replayIssues.spec.js src/components/replay/ReplayReportMailAttachments.spec.js src/components/replay/ReplayIssuePage.spec.js`

Run: `cd /Users/java/axon-link-frontend && VITE_USE_MOCK=0 npm run build`

Expected: all focused tests PASS and Vite build succeeds.

- [ ] **Step 5: Commit frontend selection**

```bash
cd /Users/java/axon-link-frontend
git add src/api/replayIssues.js src/api/replayIssues.spec.js \
  src/components/replay/ReplayReportMailAttachments.vue \
  src/components/replay/ReplayReportMailAttachments.spec.js \
  src/components/replay/ReplayIssuePage.vue \
  src/components/replay/ReplayIssuePage.spec.js
git commit -m "feat: select daily and weekly mail reports"
```

### Task 11: Run Full Regression and Package Frontend Assets

**Files:**
- Modify generated frontend assets under: `src/main/resources/static/`
- Verify only: all backend and frontend source files changed above.

**Interfaces:**
- Consumes: completed backend and frontend feature.
- Produces: validated production assets embedded in the backend, without creating an executable JAR or source ZIP unless separately requested.

- [ ] **Step 1: Run the complete frontend suite**

Run: `cd /Users/java/axon-link-frontend && npm test`

Expected: all Vitest tests PASS.

- [ ] **Step 2: Run the complete backend suite**

Run: `cd /Users/java/axon-link-server && mvn test`

Expected: all Maven tests PASS; only pre-existing explicitly skipped tests remain skipped.

- [ ] **Step 3: Build and copy frontend assets into the backend**

Run: `cd /Users/java/axon-link-frontend && VITE_USE_MOCK=0 npm run build`

Run: `cd /Users/java/axon-link-server && rm -rf src/main/resources/static/* && cp -R /Users/java/axon-link-frontend/dist/. src/main/resources/static/`

Expected: `src/main/resources/static/index.html` references the newly built hashed assets and no stale replaced hash remains.

- [ ] **Step 4: Verify embedded assets and working-tree scope**

Run: `cd /Users/java/axon-link-server && git diff --check`

Run: `cd /Users/java/axon-link-frontend && git diff --check`

Run: `rg -n "report-attachments/options|generatedReports" /Users/java/axon-link-server/src/main/resources/static`

Expected: no whitespace errors; embedded assets contain the new endpoint/payload strings; unrelated dirty files remain untouched.

- [ ] **Step 5: Commit embedded production assets**

```bash
cd /Users/java/axon-link-server
git add src/main/resources/static
git commit -m "build: embed replay report mail frontend"
```

- [ ] **Step 6: Record verification evidence**

Capture the exact frontend test count, backend test count/skips, Vite build result, and focused mail scenarios in the final handoff. Explicitly report that no executable JAR or source ZIP was produced in this task.
