# Replay Daily Report Source Total Rates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the imported Excel total-row values for “接口成功率” and “比对通过率” in both daily-report sections, including roll-forward, with weighted calculation only as a missing-value fallback.

**Architecture:** Extend `ReplayIssueSummaryParser.ParsedSummary` with an upper/lower pair of static rate totals while keeping its existing three-argument constructor compatible. Parse total rows separately from detail rows, pass the totals through first-import and roll-forward generation, and let only the two transaction-rate total cells prefer source values; all problem-state statistics keep their database-derived formulas.

**Tech Stack:** Java 17, Spring Boot, Apache POI, JUnit 5, H2/MySQL-compatible test schema, Maven.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`（统计口径与兼容解析章节）

## Global Constraints

- Do not change replay issue import, occurrence-batch, history, or database schemas.
- “接口成功率” and “比对通过率” total cells use the corresponding Excel section's total row when parseable.
- First import uses source totals from both imported sections; later imports inherit the previous report's lower totals into the new upper section and use the current Excel lower totals for the new lower section.
- Each missing total value independently falls back to the existing sent-transaction weighted calculation.
- Problem totals, issue-type counts, status counts, inspection progress, fix rate, and resolution progress remain database-derived.
- Preserve the dirty worktree and do not commit unless the user explicitly requests a commit.

---

### Task 1: Parse source total-row rates independently from detail rows

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParser.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParserTest.java`

**Interfaces:**
- Produces: `ReplayIssueSummaryParser.SummaryRateTotals(Double successRate, Double matchPassRate)`.
- Produces: `ParsedSummary.upperTotals()` and `ParsedSummary.lowerTotals()`.
- Preserves: `new ParsedSummary(upperRows, lowerRows, sheetFound)` by delegating to the new canonical constructor with null totals.

- [x] **Step 1: Write a failing parser test using distinct detail and total rates**

Create a horizontal two-section workbook where detail rows imply a different weighted result, but the upper total row contains `63.05% / 39.77%` and the lower total row contains `69.54% / 45.88%`. Assert that detail row counts are unchanged and totals are parsed separately:

```java
assertEquals(63.05, summary.upperTotals().successRate());
assertEquals(39.77, summary.upperTotals().matchPassRate());
assertEquals(69.54, summary.lowerTotals().successRate());
assertEquals(45.88, summary.lowerTotals().matchPassRate());
assertEquals(1, summary.upperRows().size());
assertEquals(1, summary.lowerRows().size());
```

- [x] **Step 2: Run the parser test and verify the expected RED state**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueSummaryParserTest#parsesUpperAndLowerTotalRatesSeparately test
```

Expected: compilation or assertion failure because `ParsedSummary` does not expose `upperTotals`/`lowerTotals`.

- [x] **Step 3: Add the totals value object and compatible parsed-summary constructor**

Implement the public nested records:

```java
public record SummaryRateTotals(Double successRate, Double matchPassRate) {
    public static final SummaryRateTotals EMPTY = new SummaryRateTotals(null, null);
}

public record ParsedSummary(List<ReplayIssueSummaryRow> upperRows,
                            List<ReplayIssueSummaryRow> lowerRows,
                            boolean sheetFound,
                            SummaryRateTotals upperTotals,
                            SummaryRateTotals lowerTotals) {
    public ParsedSummary(List<ReplayIssueSummaryRow> upperRows,
                         List<ReplayIssueSummaryRow> lowerRows,
                         boolean sheetFound) {
        this(upperRows, lowerRows, sheetFound, SummaryRateTotals.EMPTY, SummaryRateTotals.EMPTY);
    }
}
```

- [x] **Step 4: Parse each horizontal section's total row without treating it as detail**

Add a helper that scans only the section's bounded data rows, recognizes `合计`/`总计` from the first-field or domain cell, and reads the mapped `SUCCESS_RATE` and `MATCH_PASS_RATE` cells through the existing `DataFormatter`, `FormulaEvaluator`, `cellAt`, and `parsePercent` paths. Return `SummaryRateTotals.EMPTY` when no total row exists. Keep `extractHorizontalSection` excluding totals from `upperRows`/`lowerRows`.

For vertical layouts, return empty totals unless an explicit total data column is recognized; fallback behavior belongs to the report service.

- [x] **Step 5: Run the parser test and the full parser test class**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueSummaryParserTest test
```

Expected: all parser tests pass; existing three-argument `ParsedSummary` call sites still compile.

---

### Task 2: Preserve total rates through first import and rolling reports

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Consumes: `ParsedSummary.upperTotals()` and `ParsedSummary.lowerTotals()` from Task 1.
- Preserves: `generateNext(String currentBatch, LocalDateTime importedAt, ParsedSummary excelSummary)`.
- Produces: source-rate-aware upper and lower Excel total rows.

- [x] **Step 1: Write a failing first-import report test**

Pass explicit totals that intentionally differ from the detail-row weighted result:

```java
ParsedSummary summary = new ParsedSummary(
        upperRows, lowerRows, true,
        new SummaryRateTotals(63.05, 39.77),
        new SummaryRateTotals(69.54, 45.88));
```

Generate the report and assert the upper total cells equal `0.6305 / 0.3977` and the lower total cells equal `0.6954 / 0.4588` as numeric Excel percentages.

- [x] **Step 2: Run the first-import test and verify it fails with weighted values**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueDailyReportServiceTest#firstImportUsesSourceTotalRates test
```

Expected: FAIL because `writeStaticTotals` still recomputes both values.

- [x] **Step 3: Thread totals through report generation and prefer them per cell**

In `generateNext`:

```java
SummaryRateTotals upperTotals = previousReport == null
        ? excelSummary.upperTotals()
        : previousParsed.lowerTotals();
SummaryRateTotals lowerTotals = excelSummary.lowerTotals();
```

Pass these values through `writeReport`, `writeUpperPart`, `writeLowerPart`, `writeUpperTotal`, and `writeLowerTotal`. Change `writeStaticTotals` to use each provided source value independently:

```java
double success = totals != null && totals.successRate() != null
        ? totals.successRate()
        : weightedPercent(rows, weight, ReplayIssueSummaryRow::successRate);
double match = totals != null && totals.matchPassRate() != null
        ? totals.matchPassRate()
        : weightedPercent(rows, weight, ReplayIssueSummaryRow::matchPassRate);
```

Do not change the other total columns or problem-statistics formulas.

- [x] **Step 4: Extend historical-report parsing to recover generated lower totals**

When `readHistoricalReport` recognizes the generated upper/lower sections, scan each section's total row with the same total-label rule and return a five-field `ParsedSummary`. This makes the next report's upper section inherit the previous report lower source totals rather than recomputing them.

- [x] **Step 5: Write and run the rolling-inheritance test**

Generate `BATCH-A` with lower totals `69.54 / 45.88`, then generate `BATCH-B`. Assert `BATCH-B` upper total rates are still `69.54 / 45.88`, while its lower total rates use the new Excel values.

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueDailyReportServiceTest#laterImportInheritsPreviousLowerSourceTotalRates test
```

Expected: PASS after implementation.

- [x] **Step 6: Write and run the missing-value fallback test**

Use `SummaryRateTotals.EMPTY` and detail rows with known sent-transaction weights. Assert the total cells equal the existing weighted result and that a partially missing pair falls back only for the missing field.

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest=ReplayIssueDailyReportServiceTest#missingSourceTotalRateFallsBackIndependently test
```

Expected: PASS.

---

### Task 3: Verify integration and package readiness

**Files:**
- Verify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParser.java`
- Verify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParserTest.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java`

**Interfaces:**
- Consumes all outputs from Tasks 1-2.
- Produces verified Java 17 source ready for the user's later packaging request.

- [x] **Step 1: Run all daily-report and summary tests**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -Dtest='ReplayIssueSummaryParserTest,ReplayIssueDailyReportServiceTest,ReplayIssueSummaryImportIntegrationTest,ReplayIssueSummaryDaoTest' test
```

Expected: zero failures and zero errors.

- [x] **Step 2: Run the Java 17 package build**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH \
mvn -q -DskipTests package
```

Expected: exit code 0.

- [x] **Step 3: Review the focused diff and dirty-worktree boundaries**

Run:

```bash
git diff -- src/main/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParser.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueSummaryParserTest.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueDailyReportServiceTest.java
git status --short
```

Expected: only the intended cumulative changes are reported for review; unrelated user changes remain untouched. Do not create a Git commit.
