# Batch-Date Defect Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive automatic-fix `defect_repair_date` from the normalized RPT/DZ batch number and reject invalid or mixed-date workbooks before any database mutation.

**Architecture:** Add one strict batch-date parser inside `ReplayIssueMergeService`, invoked after key validation and before opening the DAO transaction. The parsed `LocalDate` becomes the sole date passed to the existing auto-repair projection; approval-driven no-action dates and fixed-to-new clearing remain independent existing paths.

**Tech Stack:** Java 17, Spring Boot 3.1, Spring JDBC, H2, JUnit 5, Maven.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Accepted normalized batch formats are `RPTyyyyMMdd-...` and `DZyyyyMMdd-...` only.
- `yyyyMMdd` must be a real calendar date under strict parsing.
- Every valid detail row in one workbook must have the same parsed batch date.
- Validation runs before `dao.inTransaction(...)`; rejection leaves current rows, import rounds, issue rounds, history, occurrence batches, and daily reports unchanged.
- Automatic `FIXED` transitions use the parsed batch date, not `registered_date`, import date, or wall-clock date.
- `FIXED` reappearance still clears `defect_repair_date` when the status becomes `NEW`.
- Approved `NO_ACTION` still uses `DATE(reviewed_at)`; leaving approved no-action still clears the date.
- RPT/DZ auto-repair family isolation and the just-implemented missing-no-action stability rule remain unchanged.
- Preserve unrelated dirty-worktree changes; do not commit unless explicitly requested.

---

### Task 1: Specify strict RPT/DZ batch-date behavior

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueMergeService.merge(ParsedWorkbook, LocalDate, ReplayIssueOperator, String)`.
- Produces: behavior-level coverage for batch-derived dates and pre-transaction rejection; no production interface yet.

- [x] **Step 1: Make the default test row use a valid RPT batch**

Change the test fixture's default `batchNo` from `B` to `RPT20260805-000000-0000`. This keeps existing date expectations at `2026-08-05` while satisfying the new production invariant.

```java
private ReplayIssueRow row(String key, String description) {
    return new ReplayIssueRow(null, "公共组", "公共组", false, 1, "公共组", "1",
            "RPT20260805-000000-0000", "6208", "交易", "交易级",
            "2026-08-05", "字段", description, "负责人", "", "", "", "", "", "", "S", "", "", "1", "I", key,
            "0", "", "", LocalDateTime.of(2026, 8, 5, 1, 0), ReplayIssueStatus.OPEN,
            LocalDate.of(2026, 8, 5), null, null, null);
}
```

- [x] **Step 2: Add failing RPT and DZ date-source tests**

```java
@Test
void autoRepairUsesRptBatchDateInsteadOfRegisteredOrImportDate() {
    dao.insertCurrent(lifecycle(
            withBatch(row("RPT-MISSING", "old"), "RPT20260819-100000-0001"),
            ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));

    merge.merge(workbook(withBatch(row("RPT-PRESENT", "new"), "RPT20260820-142055-9860")),
            LocalDate.of(2026, 8, 27), ReplayIssueOperator.system());

    assertEquals(LocalDate.of(2026, 8, 20),
            dao.findCurrentByIssueKeyForUpdate("RPT-MISSING").defectRepairDate());
}

@Test
void autoRepairUsesDzBatchDate() {
    dao.insertCurrent(lifecycle(
            withBatch(row("DZ-MISSING", "old"), "DZ20260819-100000-0001"),
            ReplayIssueStatus.OPEN, "代码问题", "a", "s", null));

    merge.merge(workbook(withBatch(row("DZ-PRESENT", "new"), "DZ20260821-142055-9860")),
            LocalDate.of(2026, 8, 27), ReplayIssueOperator.system());

    assertEquals(LocalDate.of(2026, 8, 21),
            dao.findCurrentByIssueKeyForUpdate("DZ-MISSING").defectRepairDate());
}
```

- [x] **Step 3: Add failing invalid-format, invalid-calendar-date, and mixed-date tests**

```java
@Test
void rejectsInvalidBatchFormatBeforeTransaction() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> merge.merge(workbook(withBatch(row("BAD", "bad"), "BATCH20260820")),
                    LocalDate.of(2026, 8, 27), ReplayIssueOperator.system(), "invalid-format"));
    assertTrue(error.getMessage().contains("批次号日期格式不合法"));
    assertEquals(0, dao.listImportRounds().size());
}

@Test
void rejectsImpossibleBatchDateBeforeTransaction() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> merge.merge(workbook(withBatch(row("BAD-DATE", "bad"), "RPT20260230-100000-0001")),
                    LocalDate.of(2026, 8, 27), ReplayIssueOperator.system(), "invalid-date"));
    assertTrue(error.getMessage().contains("批次号日期格式不合法"));
    assertEquals(0, dao.listImportRounds().size());
}

@Test
void rejectsMultipleBatchDatesBeforeTransaction() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> merge.merge(workbook(
                            withBatch(row("DAY-20", "one"), "RPT20260820-100000-0001"),
                            withBatch(row("DAY-21", "two"), "RPT20260821-100000-0002")),
                    LocalDate.of(2026, 8, 27), ReplayIssueOperator.system(), "mixed-date"));
    assertEquals("同一工作簿存在多个批次日期：2026-08-20、2026-08-21", error.getMessage());
    assertEquals(0, dao.listImportRounds().size());
}
```

- [x] **Step 4: Run the five new tests and verify RED**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q '-Dtest=ReplayIssueMergeServiceTest#autoRepairUsesRptBatchDateInsteadOfRegisteredOrImportDate+autoRepairUsesDzBatchDate+rejectsInvalidBatchFormatBeforeTransaction+rejectsImpossibleBatchDateBeforeTransaction+rejectsMultipleBatchDatesBeforeTransaction' test
```

Expected: date-source tests fail with `2026-08-05` instead of the batch dates; validation tests fail because invalid or mixed batches currently enter the merge transaction.

---

### Task 2: Parse one strict batch date before the merge transaction

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`

**Interfaces:**
- Consumes: `List<ReplayIssueRow>` where every row supplies `batchNo`, `sourceSheet`, and `rowOrder`.
- Produces: private `batchDate(List<ReplayIssueRow>): LocalDate`; throws `IllegalArgumentException` before DAO transaction on invalid input.

- [x] **Step 1: Add the strict pattern and parser**

```java
private static final java.util.regex.Pattern BATCH_DATE_PATTERN =
        java.util.regex.Pattern.compile("^(?:RPT|DZ)(\\d{8})-.+$");

private LocalDate batchDate(List<ReplayIssueRow> rows) {
    java.util.SortedSet<LocalDate> dates = new java.util.TreeSet<>();
    for (ReplayIssueRow row : rows) {
        String batchNo = row.batchNo() == null ? "" : row.batchNo().trim();
        java.util.regex.Matcher matcher = BATCH_DATE_PATTERN.matcher(batchNo);
        if (!matcher.matches()) {
            throw invalidBatchDate(row, batchNo);
        }
        try {
            dates.add(LocalDate.parse(matcher.group(1),
                    DateTimeFormatter.BASIC_ISO_DATE.withResolverStyle(java.time.format.ResolverStyle.STRICT)));
        } catch (DateTimeParseException exception) {
            throw invalidBatchDate(row, batchNo);
        }
    }
    if (dates.size() > 1) {
        throw new IllegalArgumentException("同一工作簿存在多个批次日期："
                + dates.stream().map(LocalDate::toString).collect(java.util.stream.Collectors.joining("、")));
    }
    return dates.first();
}

private IllegalArgumentException invalidBatchDate(ReplayIssueRow row, String batchNo) {
    return new IllegalArgumentException("页签“" + row.sourceSheet() + "”第 " + (row.rowOrder() + 1)
            + " 行批次号日期格式不合法：" + (batchNo.isBlank() ? "空" : batchNo)
            + "，正确示例：RPT20260820-142055-9860 或 DZ20260820-142055-9860");
}
```

- [x] **Step 2: Invoke validation before `dao.inTransaction(...)` and use its result for auto repair**

Replace the registration-date calculation near the start of `merge(...)`:

```java
LocalDate effectiveDate = importDate == null ? LocalDate.now(clock) : importDate;
LocalDate repairDate = batchDate(workbook.rows());
```

Then change the auto-repair projection to:

```java
ReplayIssueRow fixed = withStatusAndDefectDate(current, ReplayIssueStatus.FIXED, repairDate);
```

Remove the now-unused `latestRegisteredDate(...)` and `parseRegisteredDate(...)` methods plus their `Comparator` import. Retain `DateTimeParseException` for strict batch parsing.

- [x] **Step 3: Run the five focused tests and verify GREEN**

Run the Task 1 Step 4 command again.

Expected: all five tests pass and invalid input produces no import-round record.

---

### Task 3: Lock the four defect-repair lifecycle rules

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueReviewServiceTest.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Consumes: existing merge, review approval, and edit state-transition services.
- Produces: regression evidence for automatic fix date, fixed reappearance clearing, approval date, and approved-no-action departure clearing.

- [x] **Step 1: Run the focused lifecycle matrix**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q '-Dtest=ReplayIssueMergeServiceTest#autoRepairUsesRptBatchDateInsteadOfRegisteredOrImportDate+autoRepairUsesDzBatchDate+fixedReappearanceRetainsManualFieldsAndClearsDefectDate+missingApprovedNoActionRemainsReviewedAndIsNotAutoRepaired+missingPendingNoActionRemainsPendingWithoutDefectRepairDate,ReplayIssueReviewServiceTest,ReplayIssueEditServiceTest' test
```

Expected: all selected tests pass. Review tests prove `defectRepairDate=DATE(reviewedAt)`; edit tests prove leaving approved no-action clears it.

- [x] **Step 2: Run RPT/DZ family-isolation and ordinary missing-status tests**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q '-Dtest=ReplayIssueMergeServiceTest#missingActiveStatusesBecomeFixedWithContentAndHistoryPreserved+dzImportOnlyAutoRepairsMissingDzIssues+queryImportOnlyAutoRepairsMissingQueryIssues+noActionReappearanceRefreshesSourceAndInheritsApprovedReview' test
```

Expected: all selected tests pass; five ordinary active statuses auto-fix, RPT/DZ do not cross-fix, and no-action retains review/manual data.

---

### Task 4: Build and inspect delivery

**Files:**
- Verify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- Consumes: the unchanged import endpoint and merge service API.
- Produces: compiled `target/axon-link-server-1.0.0.jar` and verification evidence.

- [x] **Step 1: Compile the production artifact**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q -DskipTests package
```

Expected: exit code 0 and `target/axon-link-server-1.0.0.jar` exists.

- [x] **Step 2: Review the scoped diff**

```bash
git diff -- \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java
```

Expected: the new batch-date parser/validation, replacement of registration-date repair logic, valid default test batch, and focused regression cases are present; unrelated worktree changes remain untouched.
