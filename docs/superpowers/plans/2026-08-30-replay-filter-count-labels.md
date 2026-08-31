# Replay Filter Count Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display `筛选数（候选种类数）　计数（去重问题数）` in every replay issue header-filter panel.

**Architecture:** Extend the counted-options response with `matchedIssueCount`. The backend computes it from the same filtered candidate scope using distinct issue IDs; the frontend only renders the returned value and never sums per-option counts. The Vite Mock implements the identical union-by-ID rule.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, Vue 3, Vite, Vitest

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md` and `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-API接口.md`

## Global Constraints

- Preserve `candidateCount`, `truncated`, `items`, and the legacy `/header-filter-options` response.
- `matchedIssueCount` counts distinct `dii_replay_issue.id` values after all other filters and candidate keyword filtering.
- Multi-value developer, bank-owner, and occurrence-batch candidates count one issue once even when it matches multiple candidates.
- UI wording is exactly `筛选数（n）　计数（n）`; an empty result displays `筛选数（0）　计数（0）`.
- Do not commit, merge, push, or clean unrelated workspace changes.

---

### Task 1: Add backend distinct matched issue count

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueHeaderFilterOptionResult.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Produces: `ReplayIssueHeaderFilterOptionResult(int candidateCount, long matchedIssueCount, boolean truncated, List<ReplayIssueHeaderFilterOption> items)`.

- [ ] Write a DAO regression test where one issue belongs to two occurrence-batch candidates and assert `matchedIssueCount == 1` while both item counts equal 1.
- [ ] Run the focused DAO test and verify RED because the response has no `matchedIssueCount` accessor.
- [ ] Add `matchedIssueCount` to the response record.
- [ ] For SQL-grouped fields, execute `SELECT COUNT(DISTINCT issue_row_id)` over the same filtered inner query before returning the DTO.
- [ ] For split fields, union all candidate issue-ID sets after keyword filtering and use the union size.
- [ ] Update existing DTO constructor calls and Controller JSON assertions to include `matchedIssueCount`.
- [ ] Run `ReplayIssueDaoTest` plus the two counted-option Controller tests and verify GREEN.

### Task 2: Render exact labels and align development Mock

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`

**Interfaces:**
- Consumes: response field `matchedIssueCount`.
- Produces: `筛选数（candidateCount[+]）　计数（matchedIssueCount）`.

- [ ] Change the existing front-end counted-options fixture to include `matchedIssueCount`, then assert the panel text contains `筛选数（3）` and `计数（8）`; run the focused component test and verify RED.
- [ ] Add `headerFilterMatchedIssueCount` state, assign it from the response, reset it on panel close/open as appropriate, and render the two exact labels.
- [ ] Extend Mock `replayHeaderFilterOptionCounts` to union matched row IDs and return `matchedIssueCount`.
- [ ] Add a Mock regression fixture where one issue matches multiple split candidates and assert the distinct count does not increase.
- [ ] Run the focused component and Mock tests and verify GREEN.

### Task 3: Regression, build, and browser verification

**Files:**
- Verify: both repositories and `/Users/java/axon-link-server/src/main/resources/static`

**Interfaces:**
- Produces: current test/build evidence and visible local Mock result.

- [ ] Run all frontend tests with `npm test`; expect zero failures.
- [ ] Run `npm run build`; expect successful output to the backend static directory.
- [ ] Run backend production compile and the related 26-test subset; expect zero failures.
- [ ] Open the field-name filter on `http://127.0.0.1:5173/#replay-issues` and verify `筛选数（6）　计数（100）` plus the unchanged per-option counts.
- [ ] Run `git diff --check` in both repositories and report status without modifying unrelated files.
