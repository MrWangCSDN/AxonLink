# Replay Missing Active Issue Auto-Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a formal Excel import omits an issue whose current status is open, reopened, deferred, or pending verification, mark it fixed while preserving all existing content and append a complete round-linked history path.

**Architecture:** Extend the existing locked DAO query for missing auto-repair candidates from two statuses to four, then reuse the current merge transaction's `withStatusAndDefectDate`, issue-round insertion, and history snapshot insertion. No API, frontend, or database schema changes are required because round tracking already renders the issue-round status transition and associated history snapshots.

**Tech Stack:** Java 17, Spring JDBC, Spring MVC, H2 test database, JUnit 5, MockMvc, Maven.

## Global Constraints

- Apply only to formal eight-sheet Excel imports; the temporary first-sheet full refresh remains unchanged.
- Auto-repair missing rows only when the current status is `OPEN`, `REOPENED`, `DEFERRED`, or `PENDING_VERIFICATION`.
- Preserve every base field and all five user-owned fields; change only `issue_status` to `FIXED` and `defect_repair_date` to the latest valid registered date in the formal workbook, falling back to the import date.
- Insert one issue-round row with `appeared=false` and `action_type=自动修复`.
- Insert one history row with `operation_type=问题自动修复`, complete before/after snapshots, null incoming snapshot, and the current `context_round_id`.
- Do not process already fixed rows or legacy analyzing rows, and do not create duplicate history for them.
- Reuse `autoRepairedRows`; do not add or alter database tables, columns, response fields, or frontend components.
- Preserve unrelated dirty-worktree changes. Do not create a Git commit unless the user explicitly asks.

---

### Task 1: Define Four-State Missing Candidate Contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java:207`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`

**Interfaces:**
- Produces: `List<ReplayIssueRow> findAutoRepairCandidatesMissing(Set<String> incomingKeys)`.
- Guarantees: returned rows are locked and have status `OPEN`, `REOPENED`, `DEFERRED`, or `PENDING_VERIFICATION`; incoming, fixed, and analyzing rows are excluded.

- [ ] **Step 1: Write the failing DAO test**

Insert one row for each `ReplayIssueStatus`, pass an incoming-key set containing one otherwise eligible row, and assert that only the other four eligible missing rows are returned:

```java
@Test
void findsOnlyMissingActiveStatusesForAutoRepair() {
    for (ReplayIssueStatus status : ReplayIssueStatus.values()) {
        dao.insertCurrent(withStatus(row("K-" + status.name()), status));
    }

    List<ReplayIssueRow> candidates = dao.findAutoRepairCandidatesMissing(Set.of("K-OPEN"));

    assertEquals(Set.of("K-REOPENED", "K-DEFERRED", "K-PENDING_VERIFICATION"),
            candidates.stream().map(ReplayIssueRow::issueKey).collect(Collectors.toSet()));
}
```

The fixture must create unique issue keys and preserve each requested status. Add only the imports needed for `Set` and `Collectors`.

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
mvn test -q -Dtest=ReplayIssueDaoTest#findsOnlyMissingActiveStatusesForAutoRepair
```

Expected: compilation failure because `findAutoRepairCandidatesMissing` does not exist.

- [ ] **Step 3: Replace the narrow DAO method**

Rename `findDeferredOrPendingVerificationMissing` to `findAutoRepairCandidatesMissing` and use four status placeholders:

```java
public List<ReplayIssueRow> findAutoRepairCandidatesMissing(Set<String> incomingKeys) {
    String sql = "SELECT * FROM dii_replay_issue WHERE issue_status IN (?, ?, ?, ?)"
            + (incomingKeys.isEmpty() ? "" : " AND issue_key NOT IN ("
            + "?,".repeat(incomingKeys.size()).replaceAll(",$", "") + ")")
            + " FOR UPDATE";
    List<Object> args = new ArrayList<>();
    args.add(ReplayIssueStatus.OPEN.displayValue());
    args.add(ReplayIssueStatus.REOPENED.displayValue());
    args.add(ReplayIssueStatus.DEFERRED.displayValue());
    args.add(ReplayIssueStatus.PENDING_VERIFICATION.displayValue());
    args.addAll(incomingKeys);
    return jdbc.query(sql, this::mapRow, args.toArray());
}
```

- [ ] **Step 4: Run the DAO test and confirm GREEN**

Run:

```bash
mvn test -q -Dtest=ReplayIssueDaoTest#findsOnlyMissingActiveStatusesForAutoRepair
```

Expected: pass, proving incoming keys and ineligible statuses are excluded.

### Task 2: Preserve Content And Record Auto-Repair History

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java:121`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueDao.findAutoRepairCandidatesMissing(Set<String>)`.
- Produces: fixed current projection, `autoRepairedRows`, one `appeared=false` issue-round row, and one round-linked `问题自动修复` history row per eligible missing issue.

- [ ] **Step 1: Write a failing parameterized behavior test**

For each of `OPEN`, `REOPENED`, `DEFERRED`, and `PENDING_VERIFICATION`, seed a missing row with non-empty issue type, analysis, solution, collaborator, and remark. Import a different key, then assert:

```java
assertEquals("已修复", current.get("issue_status"));
assertEquals("代码问题", current.get("issue_type"));
assertEquals("人工分析", current.get("initial_analysis"));
assertEquals("人工方案", current.get("final_solution"));
assertEquals("alice", current.get("cooperation_person_username"));
assertEquals("人工备注", current.get("remark"));
assertEquals("2026-08-05", current.get("defect_repair_date").toString());
assertEquals(1, result.autoRepairedRows());

ReplayIssueHistoryEntry history = localDao.findHistoryByIssueId(id, 10).get(0);
assertEquals("问题自动修复", history.operationType());
assertTrue(history.beforeSnapshot().contains(status.displayValue()));
assertTrue(history.afterSnapshot().contains("已修复"));
assertTrue(history.afterSnapshot().contains("人工备注"));
assertNull(history.incomingSnapshot());
assertNotNull(history.contextRoundId());

ReplayIssueRoundEntry round = localDao.findIssueRounds(id).get(0);
assertEquals(false, round.appeared());
assertEquals(status, round.statusBefore());
assertEquals(ReplayIssueStatus.FIXED, round.statusAfter());
assertEquals("自动修复", round.actionType());
```

Use a fresh schema per status so every case expects exactly one auto-repaired row. Add a second assertion that missing `FIXED` and `ANALYZING` rows remain unchanged and produce no history.

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
mvn test -q -Dtest=ReplayIssueMergeServiceTest#missingActiveStatusesBecomeFixedWithContentAndHistoryPreserved
```

Expected: open and reopened cases remain unchanged under the current two-status DAO query.

- [ ] **Step 3: Switch the merge service to the generalized DAO query**

Replace only the candidate method call:

```java
for (ReplayIssueRow current : currentDao.findAutoRepairCandidatesMissing(incomingKeys)) {
```

Keep the existing `withStatusAndDefectDate(...)`, `insertIssueRound(...)`, and `insertHistoryForRound(...)` block unchanged. Those operations already preserve all fields, write both snapshots, set the round context, and keep `incomingSnapshot` null.

- [ ] **Step 4: Run the merge suite and confirm GREEN**

Run:

```bash
mvn test -q -Dtest=ReplayIssueMergeServiceTest
```

Expected: all merge tests pass, including four-state missing repair, same-key present behavior, fixed reappearance, and rollback behavior.

### Task 3: Verify The Public Round-Tracking Path

**Files:**
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Verifies existing endpoint: `GET /api/ai/parallel-replay/issues/{id}/round-tracking`.
- Preserves existing DTO schema; no production controller changes are expected.

- [ ] **Step 1: Add a controller integration test**

Seed an open issue whose key does not occur in `ReplayIssueTestFixtures.validWorkbook(1)`, perform a formal import, then assert:

```java
mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].appeared").value(false))
        .andExpect(jsonPath("$.data[0].statusBefore").value("打开"))
        .andExpect(jsonPath("$.data[0].statusAfter").value("已修复"))
        .andExpect(jsonPath("$.data[0].actionType").value("自动修复"))
        .andExpect(jsonPath("$.data[0].finalStatus").value("已修复"))
        .andExpect(jsonPath("$.data[0].manualChangeCount").value(0));
```

Also query `dao.findHistoryByIssueId(id, 10)` and assert the latest event is `问题自动修复`, retains the manual values in `afterSnapshot`, has a null incoming snapshot, and references the same round ID returned by the tracking endpoint.

- [ ] **Step 2: Run the controller test**

Run:

```bash
mvn test -q -Dtest=ReplayIssueControllerTest#roundTrackingShowsMissingOpenIssueAsAutoRepaired
```

Expected: pass without controller production changes because the existing endpoint projects issue-round rows.

### Task 4: Regression, Build, And Delivery

**Files:**
- Generated: `target/axon-link-server-1.0.0.jar`
- Generated: `axon-link-server-source-20260811-missing-active-auto-repair.zip`

**Interfaces:**
- Verifies formal import, editing, full refresh, DAO projection, and round tracking remain compatible.

- [ ] **Step 1: Run the replay regression suite**

```bash
mvn test -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest,ReplayIssueMergeServiceTest,ReplayIssueImportServiceTest,ReplayIssueEditServiceTest,ReplayIssueFullRefreshServiceTest
```

Expected: all tests pass.

- [ ] **Step 2: Build the backend artifact containing the existing frontend bundle**

```bash
mvn clean package -q -DskipTests
```

Expected: `target/axon-link-server-1.0.0.jar` exists and contains `BOOT-INF/classes/static/index.html`.

- [ ] **Step 3: Create and verify the source archive**

Archive only `pom.xml`, `src`, `scripts`, `docs`, `specs`, `build.sh`, `start.sh`, `stop.sh`, `compile-and-index.sh`, and `.gitignore`. Exclude `target`, logs, `.git`, editor metadata, and old ZIP files. Verify with:

```bash
unzip -tq axon-link-server-source-20260811-missing-active-auto-repair.zip
```

Expected: `No errors detected in compressed data`.
