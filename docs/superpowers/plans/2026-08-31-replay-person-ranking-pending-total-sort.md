# Replay Developer Ranking Pending-Total Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank developers inside each replay issue group by unresolved issue count from largest to smallest.

**Architecture:** Keep the backend DAO as the single source of truth for ordering and rank numbers. Change the aggregate query ordering to `pending_total_count DESC, total_count DESC, developer ASC`, then assign each group's one-based rank from that ordered result; local Mock data applies the identical comparator while the Vue table continues consuming API order unchanged.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, Vue 3 local Mock, Vite, Vitest.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` section “点击式实时问题汇总模态框（2026-08-11，2026-08-27 更新）”.

## Global Constraints

- Only “各组开发负责人问题排名” changes; “各组问题数” remains unchanged.
- Primary order is `pendingTotalCount DESC`.
- Ties use `totalCount DESC`, then developer name ascending.
- Rank numbers use the same ordering and restart from `1` in every group.
- Existing API fields, grouping modes (`domain` and `issueDomain`), copy behavior, and table columns remain unchanged.
- Frontend must not implement a second production sorting rule; it displays the backend's authoritative order.

---

### Task 1: Make backend ranking authoritative by unresolved count

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`

**Interfaces:**
- Consumes: `ReplayIssueDao.personIssueRankings(String groupBy)` and existing `ReplayIssuePersonRanking` fields.
- Produces: the same `List<ReplayIssuePersonRanking>` contract, ordered and ranked by unresolved totals.

- [ ] **Step 1: Strengthen the DAO test with exact group order and ranks**

In `ReplayIssueDaoTest`, extend `personIssueRankingsKeepDeveloperCombinationsAndRestartRankForEachGroup` with a second fixed Zhao transaction (`L-FIXED-2`). The 贷款组 data then deliberately conflicts: Zhao has three total issues but only one unresolved issue, while the developer combination has two unresolved issues out of two total. Assert:

```java
List<ReplayIssuePersonRanking> loanRankings = rankings.stream()
        .filter(row -> row.groupName().equals("贷款组"))
        .toList();
assertEquals(List.of(
        "张三(c-zhangs3)、李四(c-lisi)",
        "赵六(c-zhaol6)",
        "未匹配负责人"),
        loanRankings.stream().map(ReplayIssuePersonRanking::developer).toList());
assertEquals(List.of(2L, 1L, 1L),
        loanRankings.stream().map(ReplayIssuePersonRanking::pendingTotalCount).toList());
assertEquals(List.of(2L, 3L, 1L),
        loanRankings.stream().map(ReplayIssuePersonRanking::totalCount).toList());
assertEquals(List.of(1, 2, 3),
        loanRankings.stream().map(ReplayIssuePersonRanking::rank).toList());
```

This dataset proves all three levels: unresolved count first, then total count, then stable group-local ranks.

- [ ] **Step 2: Run the DAO test and verify RED**

Run:

```bash
mvn -Dtest=ReplayIssueDaoTest#personIssueRankingsKeepDeveloperCombinationsAndRestartRankForEachGroup test
```

Expected: FAIL because the current SQL orders by `total_count DESC, developer`, which can place a developer with fewer unresolved issues before one with more unresolved issues.

- [ ] **Step 3: Change only the ranking query order**

In `ReplayIssueDao.personIssueRankings(String groupBy)`, replace the final clause with:

```sql
ORDER BY stat_group_name,
         pending_total_count DESC,
         total_count DESC,
         developer ASC
```

Keep the existing Java group loop; because it consumes the authoritative SQL order, it will assign ranks `1..N` using the same comparator.

- [ ] **Step 4: Run the DAO regression and verify GREEN**

Run:

```bash
mvn -Dtest=ReplayIssueDaoTest test
```

Expected: every `ReplayIssueDaoTest` passes, including both `domain` and `issueDomain` projections.

---

### Task 2: Align local Mock ranking order with production

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`

**Interfaces:**
- Consumes: Mock rows with `groupName`, `developer`, `pendingTotalCount`, and `totalCount`.
- Produces: Mock `/person-rankings` results already sorted and group-locally ranked like the backend.

- [ ] **Step 1: Add a component contract test for API order and group-local ranks**

Update the existing ranking test response with deliberately conflicting totals:

```js
getReplayIssuePersonRankings.mockResolvedValue([
  { rank: 1, groupName: '存款组', developer: '未修复较多', pendingTotalCount: 8, totalCount: 9 },
  { rank: 2, groupName: '存款组', developer: '总数较多但未修复较少', pendingTotalCount: 3, totalCount: 20 },
  { rank: 1, groupName: '贷款组', developer: '贷款负责人', pendingTotalCount: 5, totalCount: 30 },
])
```

Assert the selected group renders `未修复较多` first and shows ranks `1, 2`. This verifies the Vue table preserves the authoritative API order rather than re-sorting by `totalCount`.

- [ ] **Step 2: Run the focused component test**

Run:

```bash
npm test -- --run src/components/replay/ReplayIssuePage.spec.js
```

Expected: PASS for the existing API-order behavior; this is a characterization test guarding against a future frontend `totalCount` sort.

- [ ] **Step 3: Sort and rank generated Mock rows with the backend comparator**

Inside `replayPersonRankings(groups)`, generate rows first, then return:

```js
return rows
  .sort((left, right) => groups.indexOf(left.groupName) - groups.indexOf(right.groupName)
    || right.pendingTotalCount - left.pendingTotalCount
    || right.totalCount - left.totalCount
    || left.developer.localeCompare(right.developer, 'zh-CN'))
  .map((row, index, sorted) => ({
    ...row,
    rank: sorted.slice(0, index)
      .filter(candidate => candidate.groupName === row.groupName).length + 1,
  }))
```

Do not sort in `filteredPersonRankingRows`; that computed projection continues only filtering the selected group and rendering sequential ranks.

- [ ] **Step 4: Run Mock syntax and frontend regression**

Run:

```bash
node --check mock/daoIndexMockServer.js
npm test -- --run
```

Expected: Mock syntax succeeds and all frontend tests pass.

---

### Task 3: Build, verify, and record implementation evidence

**Files:**
- Generated: `src/main/resources/static/**` through the existing frontend build output.
- Modify: `/Users/java/obsidian/log.md`

**Interfaces:**
- Production frontend build continues targeting the backend static directory.
- No API, database, or Excel contract changes.

- [ ] **Step 1: Run focused backend verification**

Run:

```bash
mvn -Dtest=ReplayIssueDaoTest,ReplayIssueControllerTest#personRankingKeepsDynamicDeveloperWhenGroupedByIssueDomain+personRankingEndpointKeepsDeveloperCombinationAsOneRankingRow test
```

Expected: all selected DAO and controller tests pass.

- [ ] **Step 2: Build frontend into backend static resources**

Run in `/Users/java/axon-link-frontend`:

```bash
npm run build
```

Expected: Vite exits `0` and writes `index.html` plus hashed assets to `/Users/java/axon-link-server/src/main/resources/static`.

- [ ] **Step 3: Check patch hygiene in both repositories**

Run:

```bash
git diff --check
git -C /Users/java/axon-link-frontend diff --check
```

Expected: no whitespace errors. Confirm unrelated untracked intranet copies, backup ZIPs, and generated delivery archives remain untouched.

- [ ] **Step 4: Verify the local Mock UI**

Open `/#replay-issues`, select “各组开发负责人问题排名”, and verify each group shows the largest “未修复总数” first; rows with equal unresolved totals use larger problem total first and rank numbers remain consecutive from `1`.

- [ ] **Step 5: Append implementation evidence to the Obsidian log**

Append one line to `/Users/java/obsidian/log.md` using the actual observed counts:

```text
2026-08-31 [IMPL] 落地开发负责人按未修复总数排名 | 更新后端、Mock、测试、静态资源及实施计划 | 组内按未修复总数降序，同值按问题总数降序和负责人名称升序；后端X项、前端Y项、生产构建及浏览器Mock验收通过
```

Replace `X` and `Y` with real test counts; do not record unobserved results.
