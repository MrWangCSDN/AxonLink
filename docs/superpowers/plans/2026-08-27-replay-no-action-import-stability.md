# No-Action Import Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure Excel imports never convert an existing `NO_ACTION` issue to `FIXED` merely because its `issue_key` is absent, while preserving manual/review data and deriving an approved issue's defect repair date from `reviewed_at` when the key remains present.

**Architecture:** Keep the existing merge state machine and narrow the DAO query that supplies missing-key auto-repair candidates so `NO_ACTION` never enters the auto-repair loop. For a present `NO_ACTION` key, continue using the existing `refreshed(...)` projection but derive `defectRepairDate` from the inherited review state; all other statuses retain their current refresh behavior.

**Tech Stack:** Java 17, Spring Boot 3.1, Spring JDBC, H2, JUnit 5, Maven.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- `NO_ACTION` missing from an import remains `NO_ACTION` and retains review/manual data.
- Missing `NO_ACTION` rows do not create issue-round/history events and do not increment `autoRepairedRows`.
- Present `NO_ACTION` rows refresh Excel source fields but retain issue type, analysis, solution, cooperation person, remark, planned completion date, and review projection.
- Approved `NO_ACTION` rows use `DATE(reviewed_at)` as `defect_repair_date`; pending rows keep it null.
- Missing `NEW`, `OPEN`, `REOPENED`, `DEFERRED`, and `PENDING_VERIFICATION` rows retain the existing auto-fix behavior.
- RPT and DZ batch-family isolation remains unchanged.
- Preserve unrelated dirty-worktree changes; do not commit unless the user explicitly requests a commit.

---

### Task 1: Exclude no-action issues from missing-key auto repair

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java:161-178`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java:259-279`

**Interfaces:**
- Consumes: `ReplayIssueDao.findAutoRepairCandidatesMissing(Set<String>, String)` and `ReplayIssueMergeService.merge(...)`.
- Produces: the existing DAO method returns only `NEW`, `OPEN`, `REOPENED`, `DEFERRED`, and `PENDING_VERIFICATION` candidates; its signature remains unchanged.

- [ ] **Step 1: Replace the old missing-no-action expectation with failing approved and pending tests**

```java
@Test
void missingApprovedNoActionRemainsReviewedAndIsNotAutoRepaired() {
    ReplayIssueRow reviewed = withReviewAndDefectDate(
            lifecycle(row("NO-ACTION-MISSING", "old"), ReplayIssueStatus.NO_ACTION,
                    "合理差异", "人工分析", "人工方案", "alice"),
            ReplayIssueReviewStatus.APPROVED, "reviewer", "审核人",
            LocalDateTime.of(2026, 8, 5, 12, 0), LocalDate.of(2026, 8, 5));
    long id = dao.insertCurrent(reviewed);

    ReplayIssueImportResult result = merge.merge(workbook(row("PRESENT", "new")),
            LocalDate.of(2026, 8, 6), ReplayIssueOperator.system(), "20260806-002");

    ReplayIssueRow current = dao.findCurrentByIdForUpdate(id);
    assertEquals(ReplayIssueStatus.NO_ACTION, current.issueStatus());
    assertEquals(ReplayIssueReviewStatus.APPROVED, current.reviewStatus());
    assertEquals("reviewer", current.reviewerUsername());
    assertEquals(LocalDateTime.of(2026, 8, 5, 12, 0), current.reviewedAt());
    assertEquals(LocalDate.of(2026, 8, 5), current.defectRepairDate());
    assertEquals(0, result.autoRepairedRows());
    assertTrue(dao.findHistoryByIssueId(id, 10).isEmpty());
    assertTrue(dao.findIssueRounds(id).isEmpty());
}

@Test
void missingPendingNoActionRemainsPendingWithoutDefectRepairDate() {
    ReplayIssueRow pending = withReviewAndDefectDate(
            lifecycle(row("NO-ACTION-PENDING", "old"), ReplayIssueStatus.NO_ACTION,
                    "合理差异", "人工分析", "人工方案", "alice"),
            ReplayIssueReviewStatus.PENDING, null, null, null, null);
    long id = dao.insertCurrent(pending);

    ReplayIssueImportResult result = merge.merge(workbook(row("PRESENT", "new")),
            LocalDate.of(2026, 8, 6), ReplayIssueOperator.system(), "20260806-003");

    ReplayIssueRow current = dao.findCurrentByIdForUpdate(id);
    assertEquals(ReplayIssueStatus.NO_ACTION, current.issueStatus());
    assertEquals(ReplayIssueReviewStatus.PENDING, current.reviewStatus());
    assertNull(current.defectRepairDate());
    assertEquals(0, result.autoRepairedRows());
    assertTrue(dao.findHistoryByIssueId(id, 10).isEmpty());
    assertTrue(dao.findIssueRounds(id).isEmpty());
}
```

Add the explicit fixture helper so each test controls both review and repair dates:

```java
private ReplayIssueRow withReviewAndDefectDate(ReplayIssueRow row, ReplayIssueReviewStatus status,
                                                String reviewerUsername, String reviewerRealName,
                                                LocalDateTime reviewedAt, LocalDate defectRepairDate) {
    return new ReplayIssueRow(row.id(), row.sourceSheet(), row.groupName(), row.sandbox(), row.rowOrder(),
            row.domain(), row.sequenceNo(), row.batchNo(), row.transactionCode(), row.transactionName(),
            row.issueLevel(), row.registeredDate(), row.fieldName(), row.issueDescription(), row.transactionOwner(),
            row.issueType(), row.initialAnalysis(), row.finalSolution(), row.resolvedDate(), row.cooperationGroup(),
            row.resolver(), row.serialNo(), row.dataRepairDate(), row.remark(), row.affectedTransactionCount(),
            row.issueId(), row.issueKey(), row.historicalOccurrenceCount(), row.firstOccurrenceDate(),
            row.lastOccurrenceDate(), row.importedAt(), row.issueStatus(), row.importDate(), defectRepairDate,
            row.cooperationPersonUsername(), row.cooperationPersonRealName(), row.globalSerialNo(), status,
            reviewerUsername, reviewerRealName, reviewedAt, row.plannedCompletionDate());
}
```

- [ ] **Step 2: Run the two tests and verify RED**

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q -Dtest=ReplayIssueMergeServiceTest#missingApprovedNoActionRemainsReviewedAndIsNotAutoRepaired+missingPendingNoActionRemainsPendingWithoutDefectRepairDate test
```

Expected: both tests fail because the current DAO includes `NO_ACTION`, the merge loop changes the state to `FIXED`, clears review fields, writes an auto-repair event, and increments `autoRepairedRows`.

- [ ] **Step 3: Remove `NO_ACTION` from the DAO auto-repair candidate query**

Change the six-status SQL and argument list to five statuses:

```java
String sql = "SELECT * FROM dii_replay_issue WHERE issue_status IN (?, ?, ?, ?, ?)"
        + (batchFamily == null || batchFamily.isBlank() ? "" : " AND UPPER(TRIM(batch_no)) LIKE ?")
        + (incomingKeys.isEmpty() ? "" : " AND issue_key NOT IN (" + "?,".repeat(incomingKeys.size()).replaceAll(",$", "") + ")")
        + " FOR UPDATE";
List<Object> args = new ArrayList<>();
args.add(ReplayIssueStatus.NEW.displayValue());
args.add(ReplayIssueStatus.OPEN.displayValue());
args.add(ReplayIssueStatus.REOPENED.displayValue());
args.add(ReplayIssueStatus.DEFERRED.displayValue());
args.add(ReplayIssueStatus.PENDING_VERIFICATION.displayValue());
```

Do not add a service-level `continue`; the DAO contract itself must represent the auto-repair policy.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Step 2 command again.

Expected: both tests pass; `autoRepairedRows=0`, and the no-action rows have no new round/history records.

---

### Task 2: Preserve the review-derived defect repair date when a no-action key is present

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java:140-159`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java:207-232`

**Interfaces:**
- Consumes: `ReplayIssueMergeService.refreshed(ReplayIssueRow, ReplayIssueRow, ReplayIssueStatus, LocalDate)`.
- Produces: private `inheritedDefectRepairDate(ReplayIssueRow, ReplayIssueStatus): LocalDate`, used only while creating the refreshed current projection.

- [ ] **Step 1: Strengthen the present-key no-action test and verify RED**

Seed an approved no-action row whose `reviewedAt` is `2026-08-05T12:00` and whose current repair date is `2026-08-05`, then set its planned date to `2026-08-26`. Extend `noActionReappearanceRefreshesSourceAndInheritsApprovedReview()` with:

```java
assertEquals(LocalDate.of(2026, 8, 5), current.defectRepairDate());
assertEquals("人工分析", current.initialAnalysis());
assertEquals("人工方案", current.finalSolution());
assertEquals("alice", current.cooperationPersonUsername());
assertEquals(LocalDate.of(2026, 8, 26), current.plannedCompletionDate());
```

Run:

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q -Dtest=ReplayIssueMergeServiceTest#noActionReappearanceRefreshesSourceAndInheritsApprovedReview test
```

Expected: FAIL because the current `refreshed(...)` constructor always writes a null `defectRepairDate`.

- [ ] **Step 2: Derive the inherited no-action repair date from review state**

Add:

```java
private LocalDate inheritedDefectRepairDate(ReplayIssueRow current, ReplayIssueStatus status) {
    if (status != ReplayIssueStatus.NO_ACTION
            || current.reviewStatus() != ReplayIssueReviewStatus.APPROVED
            || current.reviewedAt() == null) {
        return null;
    }
    return current.reviewedAt().toLocalDate();
}
```

Import `ReplayIssueReviewStatus`, then replace the hard-coded null defect date in `refreshed(...)`:

```java
incoming.lastOccurrenceDate(), LocalDateTime.now(clock), status, importDate,
inheritedDefectRepairDate(current, status),
```

This keeps the current behavior for every status except approved `NO_ACTION`; pending `NO_ACTION` remains null.

- [ ] **Step 3: Run the focused present-key test and verify GREEN**

Run the Step 1 command again.

Expected: PASS; source description changes to the incoming value while status, manual content, review projection, planned date, and review-derived defect repair date remain correct.

---

### Task 3: Regression verification

**Files:**
- Verify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`
- Verify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Verify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: the unchanged import API and `ReplayIssueImportResult.autoRepairedRows()` contract.
- Produces: verification evidence only; no additional production interface.

- [ ] **Step 1: Run merge, DAO, review, and controller regression suites**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q -Dtest=ReplayIssueMergeServiceTest,ReplayIssueDaoTest,ReplayIssueReviewServiceTest,ReplayIssueControllerTest test
```

Expected: all selected tests pass, including ordinary missing-key auto repair, RPT/DZ isolation, approval-date persistence, import counters, and history behavior.

- [ ] **Step 2: Compile the production artifact**

```bash
env JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  mvn -q -DskipTests package
```

Expected: exit code 0 and `target/axon-link-server-1.0.0.jar` exists.

- [ ] **Step 3: Review the final diff**

```bash
git diff -- \
  src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java \
  src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java
```

Expected: only the no-action candidate exclusion, review-derived repair-date preservation, and their regression tests are present; unrelated user changes remain untouched.
