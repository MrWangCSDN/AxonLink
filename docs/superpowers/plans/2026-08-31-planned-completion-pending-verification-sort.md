# Planned Completion Pending Verification and Rate Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a “修复待验证” subset count after completion rate in planned-completion details and return developer rows ordered by ascending completion rate with deterministic tie-breaking.

**Architecture:** Extend the existing completion-count value object with `pendingVerificationCount`, calculate it in the same DAO aggregation that produces the four mutually exclusive completion categories, and carry it through service reconciliation and JSON. The service owns developer ordering; Vue renders the returned order and the snapshot generator mirrors the visible table contract. Mock behavior follows the same rules.

**Tech Stack:** Java 17, Spring JDBC, JUnit 5, H2, Vue 3, Vitest, Vite, Canvas 2D.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`

## Global Constraints

- “修复待验证” only counts rows where `defect_repair_date IS NULL AND issue_status='修复待验证'`.
- `pendingVerificationCount` is a subset of `unfinishedCount + overdueUnfinishedCount`; it does not change the completion-rate formula.
- Developer order is completion rate ascending, null completion rate last, then planned total descending, then developer display name ascending.
- Preserve all existing uncommitted backend and frontend changes; do not include unrelated files or create a commit unless explicitly requested.

---

### Task 1: Backend count contract and DAO aggregation

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionCounts.java`
- Modify: `src/main/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDao.java`
- Test: `src/test/java/com/axonlink/ai/replay/dto/ReplayIssueCompletionCountsTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/persistence/ReplayIssueCompletionStatsDaoTest.java`

**Interfaces:**
- Produces: `ReplayIssueCompletionCounts.of(long onTime, long late, long unfinished, long overdue, long pendingVerification)` and flattened JSON property `pendingVerificationCount`.
- Consumes: Existing classified SQL rows including `issue_status`, `defect_repair_date`, and `completion_category`.

- [ ] **Step 1: Write failing DTO and DAO tests**

Add literal assertions proving JSON exposes `pendingVerificationCount`, the DAO counts only unresolved “修复待验证” rows, and fixed rows never enter the subset.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./mvnw -q -Dtest=ReplayIssueCompletionCountsTest,ReplayIssueCompletionStatsDaoTest test`

Expected: compilation or assertion failure because the five-argument factory and pending-verification aggregate do not exist.

- [ ] **Step 3: Implement the minimal count and SQL change**

Add the field to the record, preserve `plannedTotal` and `completionRate` from the four exclusive categories, and aggregate unresolved rows whose status is “修复待验证”.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./mvnw -q -Dtest=ReplayIssueCompletionCountsTest,ReplayIssueCompletionStatsDaoTest test`

Expected: PASS.

### Task 2: Backend hierarchy reconciliation and developer ordering

**Files:**
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsService.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueCompletionStatsServiceTest.java`

**Interfaces:**
- Consumes: `ReplayIssueCompletionCounts.pendingVerificationCount()` from Task 1.
- Produces: Each `ReplayIssueCompletionGroupRow.developers()` in ascending completion-rate order, null last, with deterministic tie-breakers.

- [ ] **Step 1: Write failing service tests**

Seed developers whose rates are `10.00`, `20.00`, equal, and null. Assert exact order and assert pending-verification counts reconcile at developer, group, and summary levels.

- [ ] **Step 2: Run service test and verify RED**

Run: `./mvnw -q -Dtest=ReplayIssueCompletionStatsServiceTest test`

Expected: FAIL because the current service retains DAO name order and drops the subset during summation.

- [ ] **Step 3: Implement ordering and summation**

Extend `sum(...)` with `pendingVerificationCount`. Sort developer rows by completion rate ascending with null last, then planned total descending and developer name ascending. Reject an aggregate where the subset exceeds unresolved counts.

- [ ] **Step 4: Run backend completion-stat tests and verify GREEN**

Run: `./mvnw -q -Dtest=ReplayIssueCompletionCountsTest,ReplayIssueCompletionStatsDaoTest,ReplayIssueCompletionStatsServiceTest,ReplayIssueControllerTest test`

Expected: PASS.

### Task 3: Frontend table and snapshot contract

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.vue`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/completionSnapshot.spec.js`

**Interfaces:**
- Consumes: Dashboard rows with `pendingVerificationCount` already ordered by the backend.
- Produces: Visible and PNG table columns ending with `完成率`, `修复待验证`.

- [ ] **Step 1: Write failing component and snapshot tests**

Assert header order, group/developer subset values, backend order preservation (`10%`, `20%`, null), empty-table colspan, and snapshot header/value rendering.

- [ ] **Step 2: Run focused frontend tests and verify RED**

Run: `npm test -- --run src/components/replay/ReplayPlannedCompletionModal.spec.js src/components/replay/completionSnapshot.spec.js`

Expected: FAIL because the new column is absent and the component currently re-sorts by planned total.

- [ ] **Step 3: Implement minimal rendering changes**

Add the final column to both table row types, change empty colspan from `7` to `8`, and remove the frontend business re-sort so response order is preserved. Extend Canvas widths, headers, and row values with `pendingVerificationCount` without clipping.

- [ ] **Step 4: Run focused frontend tests and verify GREEN**

Run: `npm test -- --run src/components/replay/ReplayPlannedCompletionModal.spec.js src/components/replay/completionSnapshot.spec.js`

Expected: PASS.

### Task 4: Mock parity and full verification

**Files:**
- Modify: `/Users/java/axon-link-frontend/mock/daoIndexMockServer.js`
- Test: `/Users/java/axon-link-frontend/src/components/replay/ReplayPlannedCompletionModal.spec.js`

**Interfaces:**
- Produces: Mock dashboard payload matching the real backend field and ordering contract.

- [ ] **Step 1: Write or extend failing Mock-facing assertions**

Use unresolved Mock rows with both “打开” and “修复待验证” statuses and assert the subset count and ascending-rate order are observable in the rendered modal.

- [ ] **Step 2: Run the test and verify RED**

Run: `npm test -- --run src/components/replay/ReplayPlannedCompletionModal.spec.js`

Expected: FAIL because Mock completion rows currently mark every unresolved issue as “打开” and do not return the subset field.

- [ ] **Step 3: Implement Mock parity**

Generate unresolved “修复待验证” fixtures, calculate `pendingVerificationCount`, and sort each group's developer rows with the same rate/null/tie rules as the backend.

- [ ] **Step 4: Run complete verification**

Run the focused backend suite, full frontend suite, production build, and both repository `git diff --check` commands. All must pass before completion.
