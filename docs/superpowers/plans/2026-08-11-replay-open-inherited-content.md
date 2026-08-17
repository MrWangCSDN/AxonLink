# Replay Open Inherited Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an open replay issue appears in a later formal Excel import, refresh its Excel-owned fields, retain all five user-owned fields, append a round-linked history event, and show the inherited values in the round tracking drawer.

**Architecture:** Keep the existing `issue_key` merge transaction and history snapshot schema. Change only the `OPEN` branch from ignored to updated, project its dedicated history operation into a new `inheritedEvents` collection on `ReplayIssueRoundTrackingGroup`, and render that collection separately from manual edits.

**Tech Stack:** Java 17, Spring MVC, JdbcTemplate, Jackson, JUnit 5, Vue 3, Vitest.

## Global Constraints

- Do not add or alter database tables or columns; reuse `before_snapshot`, `after_snapshot`, `incoming_snapshot`, and `context_round_id`.
- Preserve `issueStatus=OPEN`, `issueType`, `initialAnalysis`, `finalSolution`, `cooperationPersonUsername`, `cooperationPersonRealName`, and `remark` from the current row.
- Refresh Excel-owned fields from the incoming row and count the row in `updatedRows`, not `ignoredRows`.
- Use history operation type `基础数据覆盖，人工内容继承` and issue-round action type `覆盖并继承人工内容`.
- `inheritedEvents` must not increase `manualChangeCount` or alter `manualEvents` semantics.
- Preserve unrelated dirty-worktree changes. Do not create a Git commit unless the user explicitly asks.

---

### Task 1: Open-Issue Merge And History

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueMergeService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueMergeServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueDao.insertIssueRound(...)` and `insertHistoryForRound(...)`.
- Produces: one updated current row, one `dii_replay_issue_round` row with action `覆盖并继承人工内容`, and one round-linked history row with operation `基础数据覆盖，人工内容继承` for every matching open issue.

- [ ] **Step 1: Replace the old ignored-open test with a failing inheritance test**

Create an open current row whose source description and five manual fields differ from the incoming row. Assert the merged current row uses the new source description while retaining the current issue type, analysis, solution, collaborator, and remark. Assert `updatedRows=1`, `ignoredRows=0`, history count is one, and history snapshots contain both old/new source values and retained manual content.

```java
@Test
void openIssueRefreshesSourceFieldsAndRecordsInheritedManualContent() {
    ReplayIssueRow seed = withRemark(
            lifecycle(row("OPEN", "old source"), ReplayIssueStatus.OPEN,
                    "代码问题", "人工分析", "人工方案", "alice"),
            "人工备注");
    long id = dao.insertCurrent(seed);

    ReplayIssueImportResult result = merge.merge(workbook(row("OPEN", "new source")),
            LocalDate.of(2026, 8, 11), ReplayIssueOperator.system(), "20260811-001");

    ReplayIssueQuery query = new ReplayIssueQuery(50, 0, null, null, null, null,
            null, null, null, null, null, null, null, null, "20260811-001");
    Map<String, Object> current = dao.list(query).get(0);
    assertEquals("new source", current.get("issue_description"));
    assertEquals("打开", current.get("issue_status"));
    assertEquals("代码问题", current.get("issue_type"));
    assertEquals("人工分析", current.get("initial_analysis"));
    assertEquals("人工方案", current.get("final_solution"));
    assertEquals("alice", current.get("cooperation_person_username"));
    assertEquals("人工备注", current.get("remark"));
    assertEquals(1, result.updatedRows());
    assertEquals(0, result.ignoredRows());

    ReplayIssueHistoryEntry event = dao.findHistoryByIssueId(id, 10).get(0);
    assertEquals("基础数据覆盖，人工内容继承", event.operationType());
    assertTrue(event.beforeSnapshot().contains("old source"));
    assertTrue(event.afterSnapshot().contains("new source"));
    assertTrue(event.afterSnapshot().contains("人工备注"));
    assertTrue(event.incomingSnapshot().contains("new source"));
    assertEquals("覆盖并继承人工内容", dao.findIssueRounds(id).get(0).actionType());
}
```

Add a local `withRemark(...)` test helper that reconstructs `ReplayIssueRow` exactly like `lifecycle(...)` but replaces only `remark`; import `ReplayIssueImportResult`, `ReplayIssueHistoryEntry`, and static `assertTrue`.

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
mvn test -q -Dtest=ReplayIssueMergeServiceTest#openIssueRefreshesSourceFieldsAndRecordsInheritedManualContent
```

Expected: fail because the current `OPEN` branch reports the row as ignored, keeps the old source fields, and writes no history.

- [ ] **Step 3: Implement the open update branch**

Before the existing `ANALYZING/DEFERRED` ignored branch, refresh and persist an open row:

```java
if (status == ReplayIssueStatus.OPEN) {
    ReplayIssueRow refreshed = refreshed(current, incoming, ReplayIssueStatus.OPEN, current.importDate());
    currentDao.updateCurrent(refreshed);
    currentDao.updateCoverageRound(current.id(), coverageRound);
    currentDao.insertIssueRound(roundId, current.id(), key, true, status, ReplayIssueStatus.OPEN,
            "覆盖并继承人工内容", incoming.sourceSheet(), incoming.rowOrder() + 1, operationAt);
    currentDao.insertHistoryForRound(current.id(), key, "基础数据覆盖，人工内容继承",
            operationAt, effectiveOperator, effectiveDate, coverageRound,
            incoming.sourceSheet(), incoming.rowOrder() + 1,
            snapshot(current), snapshot(refreshed), snapshot(incoming), roundId);
    updated++;
    continue;
}
```

Change `refreshed(...)` to use `current.remark()` instead of `incoming.remark()` for existing rows. Change `newRow(...)` to explicitly initialize `remark` to `""`, so new formal imports still start with all five user-owned fields empty.

- [ ] **Step 4: Keep only legacy analyzing and deferred rows in the ignored branch**

```java
if (status == ReplayIssueStatus.ANALYZING || status == ReplayIssueStatus.DEFERRED) {
    // Existing coverage-round and issue-round behavior remains unchanged.
}
```

- [ ] **Step 5: Run the merge service suite and confirm GREEN**

Run:

```bash
mvn test -q -Dtest=ReplayIssueMergeServiceTest
```

Expected: all merge tests pass; the legacy ignored test covers only `ANALYZING` and `DEFERRED`.

### Task 2: Round Tracking Inherited Events

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueRoundTrackingGroup.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `ReplayIssueRoundTrackingGroup.inheritedEvents(): List<ReplayIssueHistoryEntry>`.
- Preserves: `manualEvents()` contains only `人工保存`; `manualChangeCount` equals only the manual event count.

- [ ] **Step 1: Add a failing controller test for inherited event projection**

Import the same issue twice while its current status remains open, then query `/{id}/round-tracking`. Assert the latest round has one inherited event with all five retained fields and three snapshots, while `manualChangeCount=0` and `manualEvents=[]`.

```java
mvc.perform(get("/api/ai/parallel-replay/issues/{id}/round-tracking", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].actionType").value("覆盖并继承人工内容"))
        .andExpect(jsonPath("$.data[0].manualChangeCount").value(0))
        .andExpect(jsonPath("$.data[0].manualEvents.length()").value(0))
        .andExpect(jsonPath("$.data[0].inheritedEvents.length()").value(1))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].operationType")
                .value("基础数据覆盖，人工内容继承"))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].issueType").value("代码问题"))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].initialAnalysis").value("人工分析"))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].finalSolution").value("人工方案"))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].cooperationPersonUsername").value("alice"))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].remark").value("人工备注"))
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].beforeSnapshot").isNotEmpty())
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].afterSnapshot").isNotEmpty())
        .andExpect(jsonPath("$.data[0].inheritedEvents[0].incomingSnapshot").isNotEmpty());
```

- [ ] **Step 2: Run the test and confirm RED**

Run:

```bash
mvn test -q -Dtest=ReplayIssueControllerTest#roundTrackingShowsInheritedContentSeparatelyFromManualChanges
```

Expected: fail because `ReplayIssueRoundTrackingGroup` has no `inheritedEvents` property.

- [ ] **Step 3: Extend the tracking DTO**

Add the collection before `manualEvents` and defensively copy both lists:

```java
List<ReplayIssueHistoryEntry> inheritedEvents,
List<ReplayIssueHistoryEntry> manualEvents) {
    public ReplayIssueRoundTrackingGroup {
        inheritedEvents = List.copyOf(inheritedEvents);
        manualEvents = List.copyOf(manualEvents);
    }
}
```

- [ ] **Step 4: Partition round-linked history in the controller**

Use a dedicated operation constant and two maps:

```java
private static final String INHERITED_CONTENT_OPERATION = "基础数据覆盖，人工内容继承";

Map<Long, List<ReplayIssueHistoryEntry>> inheritedByRound = new LinkedHashMap<>();
Map<Long, List<ReplayIssueHistoryEntry>> manualByRound = new LinkedHashMap<>();
```

Route matching system history into `inheritedByRound`, keep `人工保存` routing unchanged, include both map key sets when building groups, and pass `inheritedEvents` into every `ReplayIssueRoundTrackingGroup` constructor. Base-data groups receive `List.of()` for inherited events.

- [ ] **Step 5: Run controller and merge tests and confirm GREEN**

Run:

```bash
mvn test -q -Dtest=ReplayIssueControllerTest,ReplayIssueMergeServiceTest
```

Expected: both suites pass, existing manual-change counts remain unchanged.

### Task 3: Tracking Drawer Inherited Content

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: `group.inheritedEvents` from `GET /api/ai/parallel-replay/issues/{id}/round-tracking`.
- Produces: a separate “本轮继承内容” expandable section with five visible values and a complete snapshot details block.

- [ ] **Step 1: Add a failing component test**

Extend the tracking fixture with one inherited event and assert the drawer displays the exact inherited values separately from the manual history count.

```js
inheritedEvents: [{
  id: 3,
  operationType: '基础数据覆盖，人工内容继承',
  operationAt: '2026-08-08 10:00:00',
  issueType: '代码问题',
  initialAnalysis: '人工分析',
  finalSolution: '人工方案',
  cooperationPersonUsername: 'alice',
  cooperationPersonRealName: '艾丽丝',
  remark: '人工备注',
  beforeSnapshot: '{"issueDescription":"旧基础数据"}',
  afterSnapshot: '{"issueDescription":"新基础数据"}',
  incomingSnapshot: '{"issueDescription":"Excel输入"}',
}],
```

Assertions:

```js
expect(wrapper.get('[data-testid="inherited-events-2"]').text()).toContain('本轮继承内容（1）')
expect(wrapper.get('[data-testid="inherited-events-2"]').text()).toContain('代码问题')
expect(wrapper.get('[data-testid="inherited-events-2"]').text()).toContain('人工分析')
expect(wrapper.get('[data-testid="inherited-events-2"]').text()).toContain('人工方案')
expect(wrapper.get('[data-testid="inherited-events-2"]').text()).toContain('艾丽丝(alice)')
expect(wrapper.get('[data-testid="inherited-events-2"]').text()).toContain('人工备注')
expect(wrapper.text()).toContain('人工修改 2 次')
```

- [ ] **Step 2: Run the component test and confirm RED**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: fail because the inherited section is not rendered.

- [ ] **Step 3: Render inherited events before manual events**

Add a separate details block to each round group:

```vue
<details
  v-if="group.inheritedEvents?.length"
  class="replay-manual-events replay-inherited-events"
  :data-testid="`inherited-events-${group.roundId}`"
>
  <summary>本轮继承内容（{{ group.inheritedEvents.length }}）</summary>
  <ol>
    <li v-for="event in group.inheritedEvents" :key="event.id">
      <div class="replay-event-heading">
        <strong>{{ event.operationType }}</strong><time>{{ event.operationAt }}</time>
      </div>
      <dl>
        <div><dt>问题类型</dt><dd>{{ display(event.issueType) }}</dd></div>
        <div><dt>初步分析</dt><dd>{{ display(event.initialAnalysis) }}</dd></div>
        <div><dt>处理方案</dt><dd>{{ display(event.finalSolution) }}</dd></div>
        <div><dt>需协同人</dt><dd>{{ collaboratorDisplay(event) }}</dd></div>
        <div><dt>备注</dt><dd>{{ display(event.remark) }}</dd></div>
      </dl>
      <details><summary>完整快照</summary><pre>{{ formatSnapshots(event) }}</pre></details>
    </li>
  </ol>
</details>
```

Reuse existing drawer styles; add only a restrained inherited-section accent if the two sections cannot be distinguished during visual verification.

- [ ] **Step 4: Run focused frontend tests and confirm GREEN**

Run:

```bash
npm test -- --run src/api/replayIssues.spec.js src/components/replay/ReplayIssuePage.spec.js
```

Expected: all focused tests pass.

### Task 4: Regression, Build, And Packaging

**Files:**
- Regenerate: `src/main/resources/static/**`
- Regenerate: `target/axon-link-server-1.0.0.jar`
- Regenerate: `axon-link-server-source-20260811-open-inheritance.zip`

- [ ] **Step 1: Run replay backend regression tests**

```bash
mvn test -q -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest,ReplayIssueMergeServiceTest,ReplayIssueImportServiceTest,ReplayIssueEditServiceTest,ReplayIssueFullRefreshServiceTest
```

- [ ] **Step 2: Run the complete frontend test suite**

```bash
npm test -- --run
```

- [ ] **Step 3: Build the frontend into backend static resources**

```bash
npm run build
```

- [ ] **Step 4: Build a clean backend JAR**

```bash
mvn clean package -q -DskipTests
```

- [ ] **Step 5: Verify desktop and 390px tracking drawer layouts**

Open an issue with inherited content. Confirm “本轮继承内容” is visually separate from “人工修改记录”, all five values fit, and the complete snapshot block does not overflow the drawer.

- [ ] **Step 6: Create and verify the source ZIP**

Package `pom.xml`, `src`, `scripts`, `docs`, `specs`, build scripts, and `.gitignore`; exclude `target`, logs, `.git`, old ZIP files, and editor worktrees. Verify the ZIP CRC and verify the JAR contains current `static/index.html` and the current `TransactionAnalysis` chunks.

- [ ] **Step 7: Check changed-file formatting**

```bash
git diff --check
git -C /Users/java/axon-link-frontend diff --check
git -C /Users/java/obsidian diff --check
```
