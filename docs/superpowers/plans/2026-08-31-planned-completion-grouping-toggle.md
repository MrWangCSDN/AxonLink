# Planned Completion Grouping Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the planned-completion modal share the page-wide domain/issue-domain grouping switch and keep dashboard drill-down reconciled.

**Architecture:** Add the existing whitelisted `groupBy=domain|issueDomain` contract to planned-completion dashboard and issue drill-down. The parent page owns the grouping state; the modal renders the switch, emits changes, and reloads its current date range while preserving the selected time range.

**Tech Stack:** Java 17, Spring MVC, JdbcTemplate, JUnit 5, Vue 3, Vitest, Vite.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- Default grouping is `domain` after page refresh.
- `issueDomain` uses `COALESCE(NULLIF(TRIM(issue_domain), ''), group_name)`.
- The planned-completion switch stays synchronized with the toolbar and other summary modals.
- Developers continue to come from the transaction-code dynamic match.
- Existing dirty-worktree changes must be preserved; do not reset, clean, commit, or push.

---

### Task 1: Backend planned-completion grouping contract

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDao.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsService.java`
- Modify: `src/main/java/com/axonlink/ai/replay/controller/ReplayIssueController.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDaoTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`

**Interfaces:**
- Consumes: `groupBy=domain|issueDomain`.
- Produces: `dashboard(startDate,endDate,groupBy)` and `issues(...,groupBy,...)` with one grouping expression used by aggregate and drill-down.

- [ ] Write DAO/service/controller tests for issue-domain grouping, empty-value fallback, matching drill-down and HTTP 400 for an unknown value.
- [ ] Run the focused tests and confirm they fail because planned-completion ignores/rejects no grouping parameter.
- [ ] Add a server-side whitelist that chooses either `i.group_name` or `COALESCE(NULLIF(TRIM(i.issue_domain), ''), i.group_name)` and use it in classified source, grouping and drill-down predicates.
- [ ] Thread `groupBy` through controller and service with `domain` as the default.
- [ ] Run the focused tests and confirm they pass.

### Task 2: Frontend synchronized switch and API calls

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.js`
- Modify: `/Users/java/axon-link-frontend/src/api/replayIssues.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Consumes: parent `statisticsGroupBy` and planned-completion APIs accepting `{ groupBy }`.
- Produces: `update:groupBy` emitted by the modal and 4/6 group tabs derived from the selected value.

- [ ] Write failing API tests proving dashboard/drill-down carry `groupBy`.
- [ ] Write failing modal/page tests proving the hint is removed, the two buttons exist, a modal click updates the parent switch, and a parent switch updates the modal.
- [ ] Pass `statisticsGroupBy` into the modal and handle `update:groupBy` through the existing `setStatisticsGroupBy` action.
- [ ] Replace the hint with the compact switch, derive 4/6 group tabs, preserve the date range, and include `groupBy` in dashboard and drill-down requests.
- [ ] Run focused and full frontend tests.

### Task 3: Mock, build and visual verification

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.spec.js`
- Generated: `src/main/resources/static/**`

**Interfaces:**
- Consumes: planned-completion `groupBy` query parameter.
- Produces: four-domain or six-issue-domain dashboard and drill-down responses.

- [ ] Write a failing Mock test for migration/platform groups and issue-domain drill-down.
- [ ] Implement Mock grouping while preserving matched developers.
- [ ] Run all frontend tests, focused backend tests, production build and both repository diff checks.
- [ ] Verify in the local browser that the hint is gone, the switch is in its place, all switches synchronize, and the modal changes between four and six groups without resetting the date range.
