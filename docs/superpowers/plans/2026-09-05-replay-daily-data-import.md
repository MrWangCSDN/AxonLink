# Replay Daily Workbook Data Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the formal replay workbook import require and atomically persist the three daily-report sheets together with the existing eight issue sheets, using normalized batch replacement semantics.

**Architecture:** Keep the existing eight-sheet issue parser isolated. Add a dedicated daily-workbook parser that validates `汇总信息`, `接口比对明细`, and `回放交易覆盖情况`, produces typed immutable rows, and chooses one normalized canonical batch. Refactor issue merging to expose a transaction-internal entry point so issue state/history and four daily tables are committed or rolled back together; retain the current file-based daily report generation after the database transaction.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, TransactionTemplate, Apache POI, Flyway-style SQL migrations, JUnit 5, H2 MySQL mode.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “回放日报三页签原始数据入库（2026-09-05）”, plus the corresponding data-model and API sections.

## Global Constraints

- The workbook must contain three daily sheets and the existing eight issue sheets; any missing or malformed sheet aborts the import.
- Parse and validate every sheet before opening the result-database transaction.
- `QUERY` preserves `RPT`; `DZ` converts every leading `RPT` batch prefix to `DZ` before consistency checks and persistence.
- `汇总信息` persists lower-section detail rows only and excludes totals.
- `接口比对明细` persists detail rows and its final total row with `row_type=DETAIL|TOTAL`.
- `回放交易覆盖情况` persists upper summary detail/total rows and all lower transaction rows.
- Re-importing the same canonical batch replaces all four daily datasets without retaining an older daily-data version.
- Existing issue-key merge, lifecycle, history, and current daily Excel generation behavior remain unchanged except that database persistence failures now roll back issue changes.
- Do not commit changes unless the user explicitly requests a commit.

---

### Task 1: Add Daily Persistence Schema and Models

**Files:**
- Create: `src/main/resources/db/daoindex/V56__dii_replay_daily_import_data.sql`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyRowType.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailySummaryRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayInterfaceComparisonRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayCoverageSummaryRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayCoverageDetailRow.java`
- Create: `src/main/java/com/axonlink/ai/replay/dto/ReplayDailyWorkbookData.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataMigrationTest.java`

**Interfaces:**
- Produces: `ReplayDailyWorkbookData(String batchNo, List<ReplayDailySummaryRow> summaries, List<ReplayInterfaceComparisonRow> comparisons, List<ReplayCoverageSummaryRow> coverageSummaries, List<ReplayCoverageDetailRow> coverageDetails)`.
- Produces: `ReplayDailyRowType.DETAIL` and `ReplayDailyRowType.TOTAL`.
- Each row record contains `sourceRow` and `rawJson`; interface and coverage-summary rows also contain `rowType`.

- [ ] **Step 1: Write the failing migration test**

Create a MySQL-mode H2 schema, execute V56, and assert that all four tables and key columns exist:

```java
assertColumns("DII_REPLAY_DAILY_SUMMARY", "BATCH_NO", "ISSUE_TOTAL", "SOURCE_ROW");
assertColumns("DII_REPLAY_INTERFACE_COMPARISON", "ROW_TYPE", "C528_AVG_DURATION", "CCBS_AVG_DURATION");
assertColumns("DII_REPLAY_COVERAGE_SUMMARY", "ROW_TYPE", "COVERAGE_RATE", "SOURCE_ROW");
assertColumns("DII_REPLAY_COVERAGE_DETAIL", "RELATED_CODE", "LATEST_TRANSACTION_DATE", "COVERAGE_STATUS");
```

- [ ] **Step 2: Run the migration test and verify RED**

Run: `mvn -q -Dtest=ReplayDailyDataMigrationTest test`

Expected: FAIL because V56 and the four tables do not exist.

- [ ] **Step 3: Add V56 and immutable row records**

V56 creates:

```text
dii_replay_daily_summary
dii_replay_interface_comparison
dii_replay_coverage_summary
dii_replay_coverage_detail
```

Use `BIGINT` for counts, `DECIMAL(12,8)` for rates, `DECIMAL(18,6)` for average durations, `DATE` for latest transaction date, `VARCHAR(16)` for row type, `JSON` for raw row values, and indexes beginning with `batch_no`. Do not add foreign keys to `dii_replay_import_round`, because daily replacement is keyed by the business batch while import rounds remain timestamp-versioned.

- [ ] **Step 4: Run the migration test and verify GREEN**

Run: `mvn -q -Dtest=ReplayDailyDataMigrationTest test`

Expected: PASS.

### Task 2: Persist and Replace Four Daily Datasets

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayDailyDataDaoTest.java`

**Interfaces:**
- Consumes: `ReplayDailyWorkbookData` from Task 1.
- Produces: `void replaceBatch(ReplayDailyWorkbookData data, LocalDateTime importedAt)`.
- Produces test/audit readers: `findSummaries(batchNo)`, `findComparisons(batchNo)`, `findCoverageSummaries(batchNo)`, and `findCoverageDetails(batchNo)`.

- [ ] **Step 1: Write failing DAO tests**

Cover all field mappings, `DETAIL`/`TOTAL`, source-row ordering, and same-batch replacement:

```java
dao.replaceBatch(firstData, IMPORTED_AT);
dao.replaceBatch(secondData, IMPORTED_AT.plusMinutes(1));

assertEquals(secondData.summaries(), dao.findSummaries(BATCH));
assertEquals(secondData.comparisons(), dao.findComparisons(BATCH));
assertEquals(secondData.coverageSummaries(), dao.findCoverageSummaries(BATCH));
assertEquals(secondData.coverageDetails(), dao.findCoverageDetails(BATCH));
```

Also insert another batch and prove it is not deleted.

- [ ] **Step 2: Run DAO tests and verify RED**

Run: `mvn -q -Dtest=ReplayDailyDataDaoTest test`

Expected: FAIL because `ReplayDailyDataDao` does not exist.

- [ ] **Step 3: Implement delete-then-batch-insert persistence**

`replaceBatch` must reject a blank batch, execute four `DELETE ... WHERE batch_no=?` statements, then batch insert every list in deterministic `source_row` order. It must not open its own transaction; callers provide the transaction so all JdbcTemplate statements join the issue-import transaction.

- [ ] **Step 4: Run DAO tests and verify GREEN**

Run: `mvn -q -Dtest=ReplayDailyDataDaoTest test`

Expected: PASS.

### Task 3: Parse Summary and Interface Comparison Sheets

**Files:**
- Create: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParser.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParserTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/ReplayIssueTestFixtures.java`

**Interfaces:**
- Produces: `ReplayDailyWorkbookData parse(MultipartFile file, ReplayIssueImportMode mode)`.
- Uses header-name lookup rather than fixed column indexes and reports sheet name, row, and field in validation errors.
- Uses `ReplayIssueImportMode.normalizeBatch` for both summary and interface rows.

- [ ] **Step 1: Build a workbook fixture with eleven required sheets**

Extend the shared fixture with realistic compound headers and formulas for all three daily sheets while preserving the eight existing issue sheets. Include summary upper/lower sections, an interface detail row, and an interface `合计` row.

- [ ] **Step 2: Write failing summary/interface parser tests**

Assert:

```java
assertEquals("RPT20260904-094201-5355", data.batchNo());
assertEquals(4, data.summaries().size());
assertTrue(data.summaries().stream().noneMatch(row -> "合计".equals(row.domain())));
assertEquals(ReplayDailyRowType.DETAIL, data.comparisons().get(0).rowType());
assertEquals(ReplayDailyRowType.TOTAL, data.comparisons().getLast().rowType());
```

Add tests for missing `汇总信息`, missing `接口比对明细`, malformed numeric/rate/duration values, no lower summary data, multiple lower-summary batches, an interface batch mismatch, and `DZ` normalization of detail plus total rows.

- [ ] **Step 3: Run parser tests and verify RED**

Run: `mvn -q -Dtest=ReplayDailyWorkbookParserTest test`

Expected: FAIL because the parser does not exist.

- [ ] **Step 4: Implement shared POI header/value helpers and the two sheet parsers**

Support merged cells and two-level headers by resolving the nearest nonblank merged parent plus child header. Parse displayed values with `DataFormatter + FormulaEvaluator`; convert counts, percentages, and durations with strict blank/format validation. The interface total row is identified by a visible `合计` cell and keeps blank dimensions while retaining every numeric value.

- [ ] **Step 5: Run parser tests and verify GREEN**

Run: `mvn -q -Dtest=ReplayDailyWorkbookParserTest test`

Expected: summary and interface cases PASS; coverage cases remain for Task 4.

### Task 4: Parse Coverage Summary and Detail Regions

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParser.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyWorkbookParserTest.java`

**Interfaces:**
- Extends the Task 3 parser to populate `coverageSummaries` and `coverageDetails` under the canonical batch.
- Coverage summary rows retain `DETAIL` and `TOTAL`; transaction rows are always detail rows.

- [ ] **Step 1: Write failing coverage parser tests**

Cover the upper summary table and lower transaction table in the same sheet. Verify field mapping, source rows, persisted total values, date conversion, blank optional values, and rejection of an absent header region or illegal date/count/rate.

```java
assertEquals(ReplayDailyRowType.TOTAL, data.coverageSummaries().getLast().rowType());
assertEquals(714L, data.coverageSummaries().getLast().fullTransactionCount());
assertEquals(LocalDate.of(2026, 8, 2), data.coverageDetails().get(0).latestTransactionDate());
```

- [ ] **Step 2: Run the coverage tests and verify RED**

Run: `mvn -q -Dtest=ReplayDailyWorkbookParserTest test`

Expected: FAIL because coverage lists are empty or unsupported.

- [ ] **Step 3: Implement two-region coverage parsing**

Locate the summary header by `业务领域汇总` and coverage metrics, and locate the detail header by `交易码`, `交易描述`, and `是否需要回放`. Stop each region at the next header/blank boundary, retain the upper `合计` row as `TOTAL`, reject unrelated explanatory text, and bind every row to the canonical normalized batch.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run: `mvn -q -Dtest=ReplayDailyWorkbookParserTest test`

Expected: PASS.

### Task 5: Make Issue and Daily Writes One Transaction

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueImportService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueImportServiceTest.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryImportIntegrationTest.java`

**Interfaces:**
- `ReplayIssueMergeService.merge(...)` remains for existing callers and delegates to a transaction-internal method.
- Produces: `ReplayIssueImportResult mergeWithinTransaction(ReplayIssueDao currentDao, ParsedWorkbook workbook, LocalDate importDate, ReplayIssueOperator operator, String coverageRound)`.
- `ReplayIssueImportService` injects `ReplayDailyWorkbookParser` and `ReplayDailyDataDao`.

- [ ] **Step 1: Write failing import orchestration tests**

Test that all eleven sheets are parsed before writes, the canonical daily batch equals every issue-row batch, normal import saves issue plus four daily datasets, and `DZ` saves only normalized batch names.

Add a rollback test that forces `ReplayDailyDataDao.replaceBatch` to fail after issue merge and asserts the issue current row, history, import round, occurrence row, and all daily rows remain unchanged.

- [ ] **Step 2: Run orchestration tests and verify RED**

Run: `mvn -q -Dtest='ReplayIssueImportServiceTest,ReplayIssueSummaryImportIntegrationTest' test`

Expected: FAIL because daily persistence is not wired and issue merge commits separately.

- [ ] **Step 3: Extract the transaction-internal merge method**

Move the existing merge transaction callback body into `mergeWithinTransaction` without changing any lifecycle branch. Keep the public `merge` wrappers opening `dao.inTransaction` so existing service and unit-test callers preserve behavior.

- [ ] **Step 4: Parse first, then commit issue and daily data together**

The formal import sequence becomes:

```java
ParsedWorkbook issues = issueParser.parse(file, mode);
ReplayDailyWorkbookData daily = dailyParser.parse(file, mode);
validateCanonicalBatch(issues, daily.batchNo());
ReplayIssueImportResult result = dao.inTransaction(currentDao -> {
    ReplayIssueImportResult merged = mergeService.mergeWithinTransaction(
            currentDao, issues, importedAt.toLocalDate(), ReplayIssueOperator.system(), coverageRound);
    dailyDataDao.replaceBatch(daily, importedAt);
    return merged;
});
persistExistingDailyReport(importedAt, existingSummaryParserResult);
```

All parser calls, including the existing summary parse needed by current file generation, occur before the transaction. The existing file-generation compatibility call remains after commit and keeps its current logging behavior.

- [ ] **Step 5: Run focused service tests and verify GREEN**

Run: `mvn -q -Dtest='ReplayIssueMergeServiceTest,ReplayIssueImportServiceTest,ReplayIssueSummaryImportIntegrationTest' test`

Expected: PASS.

### Task 6: Verify Controller Compatibility and Full Regression

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify if needed: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-数据模型.md`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/_overview.md`
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Existing `POST /api/ai/parallel-replay/issues/import` request and `ReplayIssueImportResult` response remain unchanged.
- Validation failures continue through the existing HTTP 400 mapping with actionable sheet/row/field messages.

- [ ] **Step 1: Add controller regression cases**

Verify an eleven-sheet workbook succeeds; each missing daily sheet returns HTTP 400; malformed daily values return HTTP 400; `replayType=DZ` succeeds; the response JSON remains backward compatible.

- [ ] **Step 2: Run controller and replay module tests**

Run: `mvn -q -Dtest='ReplayIssueControllerTest,com.axonlink.ai.replay.**' test`

Expected: PASS with zero failures and errors.

- [ ] **Step 3: Run the complete backend test suite**

Run: `mvn test`

Expected: BUILD SUCCESS with zero failures and errors; existing intentional skips may remain.

- [ ] **Step 4: Build the backend artifact**

Run: `mvn -q -DskipTests package`

Expected: exit code 0 and a refreshed `target/axon-link-server-1.0.0.jar` containing V56 and all new parser/DAO classes.

- [ ] **Step 5: Reconcile the design source of truth**

Update the Obsidian sections only if implementation details differ from the approved design. Append one `[UPDATE]` entry to `/Users/java/obsidian/log.md`; do not create a duplicate design spec in the repository.
