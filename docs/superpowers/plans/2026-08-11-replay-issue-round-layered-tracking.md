# Replay Issue Round-Layered Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group each issue's import result and subsequent user operations under that issue's own latest round, backfill deterministically attributable legacy edits, and show every round expanded by default.

**Architecture:** Keep the existing import-round, issue-round, and history tables and the current `ReplayIssueRoundTrackingGroup` response. Replace global-round lookup during manual save with an issue-specific latest-round lookup; add a data-only Flyway migration for old unlinked manual saves; then adjust the Vue drawer hierarchy and expansion defaults without changing the API schema.

**Tech Stack:** Java 17, Spring JDBC, Flyway/MySQL, H2 tests, JUnit 5, Spring MockMvc, Vue 3, Vitest.

## Global Constraints

- A manual save belongs to the latest `dii_replay_issue_round` row for that `replay_issue_id`, ordered by round import time and round ID descending.
- A later global import that does not produce an issue-round row for this issue must not change its edit ownership.
- When the same issue key produces a new issue-round row, all later manual saves belong to that new round.
- Backfill only `operation_type='人工保存' AND context_round_id IS NULL`; choose the latest issue round whose `imported_at <= operation_at`.
- Leave histories with no deterministic preceding issue round unlinked in the base-data group.
- Do not add tables, columns, DTO fields, or API response fields.
- Round groups and user-operation sections default open; complete snapshots remain closed.
- Preserve unrelated dirty-worktree changes in both backend and frontend repositories.
- Do not commit, merge, or push.

---

### Task 1: Associate Future Manual Saves With The Issue's Latest Round

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueEditService.java`
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueEditServiceTest.java`

**Interfaces:**
- Produces: `Long ReplayIssueDao.findLatestIssueRoundId(long replayIssueId)`.
- Consumes: the current issue ID already locked by `ReplayIssueEditService.update(...)`.

- [x] **Step 1: Write the failing service test**

Create two formal rounds. Insert an issue-round row for the edited issue only in the first round, leave the second round as a newer global round, save the issue, and assert the history `contextRoundId` is the first round. Then insert an issue-round row for the same issue in the second round, save again, and assert the new history belongs to the second round.

```java
assertEquals(firstRoundId, historyAfterFirstSave.get(0).contextRoundId());
assertEquals(secondRoundId, historyAfterSecondSave.get(0).contextRoundId());
```

Update the existing multiple-edit test so it inserts an issue-round row for `issueId`; a round master row alone is no longer sufficient.

- [x] **Step 2: Run the focused test and confirm RED**

```bash
mvn test -q -Dtest=ReplayIssueEditServiceTest#associatesManualEditsWithTheLatestRoundForThatIssue
```

Expected: the first save is incorrectly linked to the newer global round by `findLatestImportRoundId()`.

- [x] **Step 3: Add the issue-specific DAO lookup and use it**

Implement:

```java
public Long findLatestIssueRoundId(long replayIssueId) {
    List<Long> ids = jdbc.query("""
            SELECT ir.round_id
              FROM dii_replay_issue_round ir
              JOIN dii_replay_import_round r ON r.id = ir.round_id
             WHERE ir.replay_issue_id = ?
             ORDER BY r.imported_at DESC, r.id DESC
             LIMIT 1
            """, (rs, rowNum) -> rs.getLong(1), replayIssueId);
    return ids.isEmpty() ? null : ids.get(0);
}
```

Pass `currentDao.findLatestIssueRoundId(after.id())` to `insertHistoryForRound(...)`. Remove `findLatestImportRoundId()` only after confirming no remaining callers.

- [x] **Step 4: Run the edit-service suite and confirm GREEN**

```bash
mvn test -q -Dtest=ReplayIssueEditServiceTest
```

Expected: issue-specific ownership, multiple edits in one round, unrounded base data, validation, and rollback all pass.

---

### Task 2: Backfill Deterministically Attributable Legacy Manual History

**Files:**
- Create: `src/main/resources/db/daoindex/V42__replay_manual_history_round_backfill.sql`
- Create: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueRoundBackfillMigrationTest.java`

**Interfaces:**
- Consumes: existing `dii_replay_import_round`, `dii_replay_issue_round`, and `dii_replay_issue_history` rows.
- Produces: `context_round_id` only for previously unlinked manual saves with a preceding issue-specific round.

- [x] **Step 1: Write a migration behavior test**

Using the replay H2 fixture schema, insert two rounds for issue A, a newer unrelated round for issue B, and four histories:

```text
A manual edit between A round 1 and A round 2 -> A round 1
A manual edit after A round 2              -> A round 2
A manual edit before A round 1             -> null
A non-manual history after A round 2        -> null
```

Run `V42__replay_manual_history_round_backfill.sql` through `ResourceDatabasePopulator`, then assert the four outcomes.

- [x] **Step 2: Run the focused migration test and confirm RED**

```bash
mvn test -q -Dtest=ReplayIssueRoundBackfillMigrationTest
```

Expected: failure because the V42 resource does not exist.

- [x] **Step 3: Add the data-only migration**

Use a correlated scalar subquery constrained by issue ID and operation time:

```sql
UPDATE dii_replay_issue_history h
   SET h.context_round_id = (
       SELECT ir.round_id
         FROM dii_replay_issue_round ir
         JOIN dii_replay_import_round r ON r.id = ir.round_id
        WHERE ir.replay_issue_id = h.replay_issue_id
          AND r.imported_at <= h.operation_at
        ORDER BY r.imported_at DESC, r.id DESC
        LIMIT 1
   )
 WHERE h.operation_type = '人工保存'
   AND h.context_round_id IS NULL
   AND EXISTS (
       SELECT 1
         FROM dii_replay_issue_round ir
         JOIN dii_replay_import_round r ON r.id = ir.round_id
        WHERE ir.replay_issue_id = h.replay_issue_id
          AND r.imported_at <= h.operation_at
   );
```

The migration must not update import histories, temporary full-refresh histories, already linked histories, or edits before the first known issue round.

- [x] **Step 4: Run the migration test and confirm GREEN**

```bash
mvn test -q -Dtest=ReplayIssueRoundBackfillMigrationTest
```

Expected: all deterministic histories are linked and all excluded histories remain null.

---

### Task 3: Verify Round Tracking Switches Manual Events After Reappearance

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Verifies existing endpoint: `GET /api/ai/parallel-replay/issues/{id}/round-tracking`.
- Preserves existing `ReplayIssueRoundTrackingGroup` response schema.

- [x] **Step 1: Add the controller integration test**

Import an issue in round 1, manually save it once, import the same key in round 2, and manually save it again. Query round tracking and assert:

```java
.andExpect(jsonPath("$.data.length()").value(2))
.andExpect(jsonPath("$.data[0].manualChangeCount").value(1))
.andExpect(jsonPath("$.data[1].manualChangeCount").value(1));
```

Also assert the newest group's `roundCode` differs from the older group and each group's sole manual event has the expected remark or analysis content.

- [x] **Step 2: Run the focused controller test**

```bash
mvn test -q -Dtest=ReplayIssueControllerTest#roundTrackingMovesLaterManualEditsToTheNewIssueRound
```

Expected: pass after Task 1, proving the public grouping path uses the issue-specific round.

- [x] **Step 3: Run backend replay regression**

```bash
mvn test -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest,ReplayIssueMergeServiceTest,ReplayIssueImportServiceTest,ReplayIssueEditServiceTest,ReplayIssueFullRefreshServiceTest,ReplayIssueRoundBackfillMigrationTest
```

Expected: all replay tests pass.

---

### Task 4: Render Round Layers Expanded By Default

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: existing `roundCode`, `inheritedEvents`, and `manualEvents` fields.
- Produces: round `details` and user-operation `details` elements that are open initially; nested snapshot details remain closed.

- [x] **Step 1: Write the failing Vue test**

Return two round groups from `getReplayIssueRoundTracking`, open the drawer, and assert:

```javascript
expect(wrapper.get('[data-testid="tracking-round-2"]').attributes()).toHaveProperty('open')
expect(wrapper.get('[data-testid="manual-events-2"]').attributes()).toHaveProperty('open')
expect(wrapper.get('[data-testid="tracking-round-1"]').attributes()).toHaveProperty('open')
expect(wrapper.get('[data-testid="tracking-drawer"]').text()).toContain('轮次编号 20260811-002')
expect(wrapper.get('[data-testid="tracking-drawer"] details details').attributes()).not.toHaveProperty('open')
```

- [x] **Step 2: Run the focused Vue test and confirm RED**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/ReplayIssuePage.spec.js
```

Expected: round sections are not `details`, manual events are closed, and the explicit “轮次编号” label is absent.

- [x] **Step 3: Implement the selected A layout**

Render each round article as an open outer `details` group with a summary containing `轮次编号 {{ group.roundCode }}` and the import time. Keep the content order:

```text
本轮导入结果
本轮继承内容
本轮用户操作
```

Add `open` to inherited and manual event details, rename “查看人工修改记录” to “本轮用户操作”, add stable test IDs, and leave every per-event “完整快照” details without `open`. Preserve the current newest-first backend order and base-data description.

- [x] **Step 4: Run frontend tests and build**

```bash
cd /Users/java/axon-link-frontend
npm test -- src/components/replay/ReplayIssuePage.spec.js src/api/replayIssues.spec.js
npm run build
```

Expected: tests pass and Vite writes the built frontend to `/Users/java/axon-link-server/src/main/resources/static`.

---

### Task 5: Package And Verify The Integrated Application

**Files:**
- Generated: `target/axon-link-server-1.0.0.jar`
- Generated: `axon-link-server-source-20260811-round-layered-tracking.zip`

**Interfaces:**
- Verifies the backend JAR embeds the newly built frontend and the source archive preserves Java source package directories named `target`.

- [x] **Step 1: Build the backend JAR**

```bash
mvn clean package -q -DskipTests
```

- [x] **Step 2: Verify embedded frontend assets**

```bash
jar tf target/axon-link-server-1.0.0.jar | rg 'BOOT-INF/classes/static/index.html|BOOT-INF/classes/static/assets/index-.*\\.js'
```

- [x] **Step 3: Generate the source archive with the fixed packaging rule**

```bash
scripts/package-source.sh axon-link-server-source-20260811-round-layered-tracking.zip
```

- [x] **Step 4: Verify archive integrity and source-package preservation**

```bash
unzip -tq axon-link-server-source-20260811-round-layered-tracking.zip
unzip -Z1 axon-link-server-source-20260811-round-layered-tracking.zip \
  | rg '^src/main/java/com/axonlink/ai/daoindex/target/TargetDataSourceRegistry.java$'
```

Expected: the archive is valid and includes the Java package named `target`.
